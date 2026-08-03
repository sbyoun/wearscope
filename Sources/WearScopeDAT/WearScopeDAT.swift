/*
 * WearScopeDAT — one-line auto-instrumentation for Meta Wearables DAT.
 *
 *   WearScopeDAT.observe(wearables: Wearables.shared)   // registration, devices, env snapshot
 *   WearScopeDAT.observe(stream: stream)                // state transitions, errors, photos
 *   WearScopeDAT.observeFrames(stream: stream)          // fps + inter-frame p95 (opt-in)
 *
 * Everything lands in the WearScope timeline. Metadata only — never frame/photo payloads.
 */

#if canImport(MWDATCore) && canImport(MWDATCamera)
import CoreMedia
import Foundation
import WearScope
import ImageIO
import MWDATCamera
import MWDATCore

@MainActor
public enum WearScopeDAT {
  /// Version of DAT this adapter is built against — recorded in the env snapshot
  /// so fleet comparisons can segment by SDK version.
  public static let datVersion = "0.8.0"

  private static var tokens: [any AnyListenerToken] = []
  private static var linkObserved = Set<String>()

  // MARK: - Wearables (registration · devices · environment)

  /// Observe registration state and device list; emits an `env` snapshot immediately.
  /// Call once, e.g. right after `Wearables.configure()`:
  /// `WearScopeDAT.observe(wearables: Wearables.shared)`.
  public static func observe(wearables: any WearablesInterface) {
    preflight()
    WearScope.track(.custom, "env", [
      "dat_sdk": datVersion,
      "locale": Locale.current.identifier,
      "timezone": TimeZone.current.identifier,
      "registration": wearables.registrationState.description,
    ])
    tokens.append(wearables.addRegistrationStateListener { state in
      WearScope.track(.sessionState, "registration", ["state": state.description])
    })
    tokens.append(wearables.addDevicesListener { ids in
      // Report the device TYPE, not the display name: names carry a per-unit
      // serial ("RB Meta 029F") and differ across platforms, which would split
      // one model into many keys and make fleet baselines meaningless. Types are
      // stable and identical everywhere — and carry no per-device identifier.
      let models = Set(ids.compactMap {
        wearables.deviceForIdentifier($0).map { canonicalModel($0.deviceType().rawValue) }
      })
      WearScope.track(.custom, "devices", [
        "count": "\(ids.count)",
        "models": models.sorted().joined(separator: ","),   // fleet segment key
      ])
      Task { @MainActor in observeLinks(wearables: wearables, ids: ids) }
    })
    observeLinks(wearables: wearables, ids: wearables.devices)
  }

  /// Config preflight — catches "silent failures" in the first-run timeline.
  /// Every check below cost real hours on hardware: when missing, there is no crash
  /// and no error — the device just never appears.
  private static func preflight() {
    let bundle = Bundle.main
    var issues: [String] = []

    if bundle.object(forInfoDictionaryKey: "NSBluetoothAlwaysUsageDescription") == nil {
      issues.append("NSBluetoothAlwaysUsageDescription missing — BT discovery is silently blocked, devices stays 0 forever")
    }
    if bundle.object(forInfoDictionaryKey: "NSLocalNetworkUsageDescription") == nil {
      issues.append("NSLocalNetworkUsageDescription missing — iOS streaming (Wi-Fi transport) may fail")
    }

    if let mwdat = bundle.object(forInfoDictionaryKey: "MWDAT") as? [String: Any] {
      for key in ["MetaAppID", "ClientToken", "AppLinkURLScheme"] {
        let value = (mwdat[key] as? String) ?? ""
        if value.isEmpty { issues.append("MWDAT.\(key) is empty — check Dev Center credential injection") }
      }
      // AppLinkURLScheme must actually be registered in CFBundleURLTypes for the Meta AI deep link to return
      if let scheme = (mwdat["AppLinkURLScheme"] as? String)?
        .replacingOccurrences(of: "://", with: ""), !scheme.isEmpty {
        let urlTypes = bundle.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        let schemes = urlTypes.flatMap { ($0["CFBundleURLSchemes"] as? [String]) ?? [] }
        if !schemes.contains(scheme) {
          issues.append("AppLinkURLScheme(\(scheme)) not in CFBundleURLTypes — registration/permission deep link cannot return")
        }
      }
    } else {
      issues.append("MWDAT dictionary missing — Info.plist needs MetaAppID/ClientToken/AppLinkURLScheme")
    }

    if issues.isEmpty {
      WearScope.track(.custom, "preflight", ["result": "ok"])
    } else {
      for issue in issues {
        WearScope.trackError(issue, context: "preflight")
      }
    }
  }

  /// Track per-device link state (connected/disconnected) — "registered" is not "connected".
  private static func observeLinks(wearables: any WearablesInterface,
                                   ids: [DeviceIdentifier]) {
    for id in ids {
      let key = "\(id)"
      guard !linkObserved.contains(key),
            let device = wearables.deviceForIdentifier(id) else { continue }
      linkObserved.insert(key)
      let model = canonicalModel(device.deviceType().rawValue)
      trackLink(key: key, device: model, state: "\(device.linkState)")
      tokens.append(device.addLinkStateListener { state in
        Task { @MainActor in trackLink(key: key, device: model, state: "\(state)") }
      })
    }
  }

  /// Deduplicate repeated same-state link callbacks — only transitions are worth a timeline row.
  private static var lastLinkState: [String: String] = [:]

  private static func trackLink(key: String, device: String, state: String) {
    guard lastLinkState[key] != state else { return }
    lastLinkState[key] = state
    WearScope.track(.sessionState, "link", ["device": device, "state": state])
  }

  // MARK: - Stream (state transitions · errors · photo arrivals)

  /// Observe a camera stream: records every state transition (300 ms sampling),
  /// stream errors (with failure-mode explanations), and photo arrivals.
  public static func observe(stream: MWDATCamera.Stream, label: String = "stream") {
    // Listener tokens are scoped to this stream and released when it stops —
    // keeping them alive can retain the old Stream and wedge the next open.
    var streamTokens: [any AnyListenerToken] = []
    streamTokens.append(stream.errorPublisher.listen { error in
      WearScope.trackError(error.description, context: "dat.\(label)")
    })
    streamTokens.append(stream.photoDataPublisher.listen { photo in
      var attrs = ["bytes": "\(photo.data.count)", "label": label]
      if let (w, h) = imageDims(photo.data) {  // actual pixel resolution — header read only (no decoding)
        attrs["w"] = "\(w)"
        attrs["h"] = "\(h)"
      }
      WearScope.track(.photo, "photo_data", attrs)
    })
    Task { @MainActor [weak stream] in
      var last = ""
      var t0 = Date()
      while let s = stream {
        let state = "\(s.state)"
        if state != last {
          let attrs: [String: String] = last.isEmpty
            ? ["state": state, "label": label]
            : ["state": state, "label": label, "prev": last,
               "ms_in_prev": String(Int(Date().timeIntervalSince(t0) * 1000))]
          WearScope.track(.stream, "state", attrs)
          last = state
          t0 = Date()
        }
        if s.state == .stopped { break }
        try? await Task.sleep(nanoseconds: 300_000_000)
      }
      streamTokens.removeAll()  // release subscriptions with the stream
    }
  }

  // MARK: - Frames (fps · inter-frame jitter) — opt-in, subscribes to the frame publisher

  /// Report fps and inter-frame-gap p95 every `reportEvery` seconds while the stream lives.
  /// Answers "is this cadence normal?" (BT-link jitter is a known community pain point).
  public static func observeFrames(stream: MWDATCamera.Stream,
                                   reportEvery seconds: UInt64 = 5,
                                   label: String = "stream") {
    let stats = FrameStats()
    var frameToken: (any AnyListenerToken)? = stream.videoFramePublisher.listen { frame in
      stats.mark(dims: frameDims(frame))
    }
    Task { @MainActor [weak stream] in
      defer { frameToken = nil }  // release the frame subscription with the stream
      while let s = stream, s.state != .stopped {
        try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
        if let report = stats.drain(windowSeconds: Double(seconds)) {
          var attrs = [
            "fps": report.fps, "gap_p95_ms": report.p95, "gap_max_ms": report.max,
            "label": label,
          ]
          if let res = report.res { attrs["res"] = res }  // measured frame resolution
          WearScope.track(.metric, "frames", attrs)
        }
      }
    }
  }

  /// Read only the pixel size from the JPEG header (no full decode).
  nonisolated public static func imageDims(_ data: Data) -> (Int, Int)? {
    guard let src = CGImageSourceCreateWithData(data as CFData, nil),
          let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
          let w = props[kCGImagePropertyPixelWidth] as? Int,
          let h = props[kCGImagePropertyPixelHeight] as? Int else { return nil }
    return (w, h)
  }

  nonisolated private static func frameDims(_ frame: VideoFrame) -> String? {
    guard let desc = CMSampleBufferGetFormatDescription(frame.sampleBuffer) else { return nil }
    let d = CMVideoFormatDescriptionGetDimensions(desc)
    return "\(d.width)x\(d.height)"
  }
}

/// Frame-arrival accounting. Listener may fire on any thread — lock-guarded.
private final class FrameStats: @unchecked Sendable {
  private let lock = NSLock()
  private var lastAt: Date?
  private var gapsMs: [Double] = []
  private var res: String?

  func mark(dims: String? = nil) {
    lock.lock()
    defer { lock.unlock() }
    let now = Date()
    if let last = lastAt { gapsMs.append(now.timeIntervalSince(last) * 1000) }
    if gapsMs.count > 2000 { gapsMs.removeFirst(gapsMs.count - 2000) }
    if let dims { res = dims }
    lastAt = now
  }

  func drain(windowSeconds: Double) -> (fps: String, p95: String, max: String, res: String?)? {
    lock.lock()
    defer { lock.unlock() }
    guard !gapsMs.isEmpty else { return nil }
    let sorted = gapsMs.sorted()
    let p95 = sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))]
    let result = (fps: String(format: "%.1f", Double(gapsMs.count) / windowSeconds),
                  p95: String(Int(p95)),
                  max: String(Int(sorted.last ?? 0)),
                  res: res)
    gapsMs.removeAll()
    return result
  }
}
#endif
