/*
 * BenchEngine — stream & photo benchmarks over the DAT camera.
 *
 * Everything is auto-instrumented via WearScopeDAT (observe/observeFrames);
 * the UI numbers below are computed independently so the app is useful even
 * before you look at the timeline.
 */

import Foundation
import WearScope
import WearScopeDAT
import MWDATCamera
import MWDATCore
import SwiftUI

@MainActor
final class BenchEngine: ObservableObject {
  @Published private(set) var registrationState = "-"
  @Published private(set) var deviceName: String?
  @Published private(set) var glassesLink = "no device"
  private var linkToken: (any AnyListenerToken)?
  @Published private(set) var running = false
  @Published private(set) var lines: [String] = []
  @Published private(set) var summary: String?
  /// Latest run, keyed by the metric names the public leaderboard uses.
  @Published private(set) var lastRun: [String: Int] = [:]

  /// Bench results feed the public leaderboard so everyone gets baselines.
  /// Opt out here and this device stops contributing (it still measures locally).
  @AppStorage("wearbench.shareResults") var shareResults = true

  private var manager: DeviceSessionManager?
  private var tokens: [any AnyListenerToken] = []

  // MARK: - Setup / registration

  func configure() {
    guard manager == nil else { return }
    do {
      try Wearables.configure()
    } catch {
      note("Wearables.configure failed: \(error)")
      return
    }
    manager = DeviceSessionManager(wearables: Wearables.shared)
    WearScopeDAT.observe(wearables: Wearables.shared)  // ← SDK: registration/devices/env
    registrationState = Wearables.shared.registrationState.description
    tokens.append(Wearables.shared.addRegistrationStateListener { [weak self] state in
      Task { @MainActor in self?.registrationState = state.description }
    })
    tokens.append(Wearables.shared.addDevicesListener { [weak self] ids in
      Task { @MainActor in self?.trackFirstDevice(ids) }
    })
    trackFirstDevice(Wearables.shared.devices)
  }

  /// Track the first device's name and link state — the UI distinguishes registered ≠ connected.
  private func trackFirstDevice(_ ids: [DeviceIdentifier]) {
    guard let id = ids.first, let device = Wearables.shared.deviceForIdentifier(id) else {
      deviceName = nil
      glassesLink = "no device"
      linkToken = nil
      return
    }
    deviceName = device.nameOrId()
    glassesLink = Self.linkLabel(device.linkState)
    linkToken = device.addLinkStateListener { [weak self] state in
      Task { @MainActor in self?.glassesLink = Self.linkLabel(state) }
    }
  }

  private static func linkLabel(_ state: LinkState) -> String {
    switch state {
    case .connected: return "connected ✅"
    case .connecting: return "connecting…"
    case .disconnected: return "disconnected ⚠️"
    @unknown default: return "\(state)"
    }
  }

  func register() async {
    configure()
    do {
      try await Wearables.shared.startRegistration()
    } catch RegistrationError.alreadyRegistered {
      note("Already registered — if no device appears, unregister and register again")
    } catch {
      note("Registration failed: \(error)")
      WearScope.trackError("\(error)", context: "bench.register")  // inspect the cause in the server timeline
    }
  }

  /// Unregister — the only way to reset the "registered but no device appears" state.
  /// Caution: can also affect connections of other apps using the same MetaAppID.
  func unregister() async {
    configure()
    do {
      try await Wearables.shared.startUnregistration()
      note("Unregistered — use [Register glasses] to register again")
      WearScope.track(.sessionState, "registration", ["state": "unregistered_by_user"])
    } catch {
      note("Unregister failed: \(error)")
      WearScope.trackError("\(error)", context: "bench.unregister")
    }
  }

  // MARK: - Bench

  /// low → high stream bench + 3 stills. Results go to both the UI (summary) and the WearScope timeline.
  func runBench() async {
    configure()
    guard !running, let manager else { return }
    running = true
    lines = []
    summary = nil
    defer { running = false }

    WearScope.track(.custom, "bench_start",
                     ["devices": "\(Wearables.shared.devices.count)"])

    // Glasses camera permission (sample pattern) — record the whole flow in the timeline
    do {
      var st = try await Wearables.shared.checkPermissionStatus(.camera)
      WearScope.track(.custom, "permission", ["perm": "camera", "phase": "check", "status": "\(st)"])
      if st != .granted {
        note("Requesting camera permission — approve it in the Meta AI app")
        st = try await Wearables.shared.requestPermission(.camera)
        WearScope.track(.custom, "permission", ["perm": "camera", "phase": "request", "status": "\(st)"])
      }
      guard st == .granted else {
        note("Camera permission denied — stopping")
        WearScope.trackError("camera permission not granted: \(st)", context: "bench.permission")
        return
      }
    } catch {
      note("Permission check failed: \(error)")
      WearScope.trackError("\(error)", context: "bench.permission")
      return
    }

    let session: DeviceSession
    do {
      note("Opening session…")
      session = try await manager.getSession()
    } catch {
      note("Session failed: \(error.description)")
      return
    }

    var report: [String] = []

    // Stream per resolution: open time + 8 s frame measurement
    for (name, res) in [("low", StreamingResolution.low), ("high", .high)] {
      note("[\(name)] opening stream (first run includes Wi-Fi join)…")
      guard let result = await streamBench(session: session, name: name, resolution: res) else {
        report.append("\(name): failed")
        continue
      }
      let line = "\(name): open \(result.openMs)ms · \(result.fps)fps · gap p95 \(result.p95)ms/max \(result.maxGap)ms"
      note("→ " + line)
      report.append(line)
      lastRun["warm_open"] = result.openMs
      if shareResults {
        WearScope.track(.metric, "bench_stream", [
        "res": name, "open_ms": "\(result.openMs)", "fps": result.fps,
          "gap_p95_ms": "\(result.p95)", "gap_max_ms": "\(result.maxGap)",
        ])
      }
    }

    // Still bench: 3 shots from the low warm stream
    note("[photo] 3 stills…")
    if let photoLines = await photoBench(session: session) {
      report.append(contentsOf: photoLines)
    } else {
      report.append("photo: failed")
    }

    summary = report.joined(separator: "\n")
    WearScope.flush()
  }

  private struct StreamResult { let openMs: Int; let fps: String; let p95: Int; let maxGap: Int }

  private func streamBench(session: DeviceSession, name: String,
                           resolution: StreamingResolution) async -> StreamResult? {
    let cfg = StreamConfiguration(videoCodec: .raw, resolution: resolution, frameRate: 24)
    guard let st = try? session.addStream(config: cfg) else { return nil }
    WearScopeDAT.observe(stream: st, label: name)          // ← SDK: state transitions, errors, photos
    WearScopeDAT.observeFrames(stream: st, label: name)    // ← SDK: fps, jitter

    let counter = FrameCounter()
    let frameToken = st.videoFramePublisher.listen { _ in counter.mark() }
    _ = frameToken  // keep alive

    let t0 = Date()
    st.start()
    var waited = 0
    while st.state != .streaming && waited < 30000 {
      try? await Task.sleep(nanoseconds: 200_000_000)
      waited += 200
    }
    guard st.state == .streaming else {
      st.stop()
      return nil
    }
    let openMs = Int(Date().timeIntervalSince(t0) * 1000)

    counter.reset()
    try? await Task.sleep(nanoseconds: 8_000_000_000)  // 8 s measurement
    let m = counter.snapshot(windowSeconds: 8)

    st.stop()
    var stopWait = 0
    while st.state != .stopped && stopWait < 5000 {
      try? await Task.sleep(nanoseconds: 200_000_000)
      stopWait += 200
    }
    return StreamResult(openMs: openMs, fps: m.fps, p95: m.p95, maxGap: m.maxGap)
  }

  private func photoBench(session: DeviceSession) async -> [String]? {
    let cfg = StreamConfiguration(videoCodec: .raw, resolution: .low, frameRate: 24)
    guard let st = try? session.addStream(config: cfg) else { return nil }

    var photoSeq = 0
    var lastBytes = 0
    var lastDims = ""
    let token = st.photoDataPublisher.listen { photo in
      let dims = WearScopeDAT.imageDims(photo.data).map { "\($0.0)×\($0.1)" } ?? "?"
      Task { @MainActor in
        photoSeq += 1
        lastBytes = photo.data.count
        lastDims = dims
      }
    }
    _ = token

    st.start()
    var waited = 0
    while st.state != .streaming && waited < 30000 {
      try? await Task.sleep(nanoseconds: 200_000_000)
      waited += 200
    }
    guard st.state == .streaming else { st.stop(); return nil }
    defer { st.stop() }

    var out: [String] = []
    for i in 1...3 {
      let seq0 = photoSeq
      let t0 = Date()
      guard st.capturePhoto(format: .jpeg) else {
        out.append("photo \(i): request rejected")
        continue
      }
      var w = 0
      while photoSeq == seq0 && w < 15000 {
        try? await Task.sleep(nanoseconds: 100_000_000)
        w += 100
      }
      if photoSeq > seq0 {
        let ms = Int(Date().timeIntervalSince(t0) * 1000)
        out.append("photo \(i): \(ms)ms · \(lastBytes / 1024)KB · \(lastDims)")
        lastRun["capture"] = ms
        if shareResults {
          WearScope.track(.photo, "bench_capture",
                          ["ms": "\(ms)", "bytes": "\(lastBytes)", "res": lastDims])
        }
      } else {
        out.append("photo \(i): not delivered within 15s")
      }
    }
    return out
  }

  private func note(_ message: String) {
    lines.append(message)
  }
}

/// Frame-arrival accounting (listener may fire on any thread) — lock-guarded.
private final class FrameCounter: @unchecked Sendable {
  private let lock = NSLock()
  private var lastAt: Date?
  private var gapsMs: [Double] = []

  func mark() {
    lock.lock()
    defer { lock.unlock() }
    let now = Date()
    if let last = lastAt { gapsMs.append(now.timeIntervalSince(last) * 1000) }
    lastAt = now
  }

  func reset() {
    lock.lock()
    gapsMs.removeAll()
    lastAt = nil
    lock.unlock()
  }

  func snapshot(windowSeconds: Double) -> (fps: String, p95: Int, maxGap: Int) {
    lock.lock()
    defer { lock.unlock() }
    guard !gapsMs.isEmpty else { return ("0", 0, 0) }
    let sorted = gapsMs.sorted()
    let p95 = sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))]
    return (String(format: "%.1f", Double(gapsMs.count) / windowSeconds),
            Int(p95), Int(sorted.last ?? 0))
  }
}
