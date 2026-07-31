/*
 * WearScope — observability SDK for smart-glasses (DAT) apps. docs/OBSERVABILITY.md
 *
 * Usage:
 *   WearScope.start(apiKey: "ws_dev")                       // local mode (file logging only)
 *   WearScope.start(apiKey: "gs_...", endpoint: serverURL)  // cloud upload
 *   WearScope.track(.photo, "capture", ["ms": "401", "bytes": "48000"])
 *   WearScope.trackError(error, context: "hud.send")        // known DAT errors get an explanation attached
 */

import Foundation
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AVFAudio)
import AVFAudio
#endif

public struct WSConfig: Sendable {
  public static let sdkVersion = "0.4.0"

  let apiKey: String
  let endpoint: URL?
  let appInfo: WSEnvelope.App
  let deviceInfo: WSEnvelope.Device

  init(apiKey: String, endpoint: URL?) {
    self.apiKey = apiKey
    self.endpoint = endpoint
    let bundle = Bundle.main
    appInfo = .init(
      bundleId: bundle.bundleIdentifier ?? "unknown",
      version: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0",
      build: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0")
    #if canImport(UIKit)
    var model = ""
    var systemInfo = utsname()
    uname(&systemInfo)
    withUnsafeBytes(of: &systemInfo.machine) { bytes in
      model = String(decoding: bytes.prefix(while: { $0 != 0 }), as: UTF8.self)
    }
    deviceInfo = .init(model: model, os: "iOS \(UIDevice.current.systemVersion)")
    #else
    deviceInfo = .init(model: "unknown", os: "unknown")
    #endif
  }
}

public enum WearScope {

  /// Start the SDK. A nil endpoint means local mode (events go to file only).
  public static func start(apiKey: String, endpoint: URL? = nil) {
    let config = WSConfig(apiKey: apiKey, endpoint: endpoint)
    Task { await WSCore.shared.start(config: config) }
  }

  /// Record an event. attrs is metadata only (no payloads — privacy principle).
  public static func track(_ type: WSEventType, _ name: String, _ attrs: [String: String] = [:]) {
    Task { await WSCore.shared.add(WSEvent(type: type, name: name, attrs: attrs)) }
  }

  /// Record an error — known DAT failure modes get an explanation attached automatically.
  public static func trackError(_ error: Error, context: String) {
    trackError("\(error)", context: context)
  }

  public static func trackError(_ description: String, context: String) {
    var attrs = ["context": context, "description": String(description.prefix(500))]
    if let explanation = WSErrorDecoder.explain(description) {
      attrs["explain"] = explanation
    }
    Task { await WSCore.shared.add(WSEvent(type: .error, name: context, attrs: attrs)) }
  }

  /// Duration helper: call the returned closure on completion to record elapsed ms.
  public static func measure(_ type: WSEventType, _ name: String,
                             _ attrs: [String: String] = [:]) -> (@Sendable ([String: String]) -> Void) {
    let t0 = Date()
    return { extra in
      var merged = attrs.merging(extra) { _, new in new }
      merged["ms"] = String(Int(Date().timeIntervalSince(t0) * 1000))
      track(type, name, merged)
    }
  }

  #if canImport(AVFAudio) && os(iOS)
  /// Auto-observe audio routes — built to catch the silent glasses(HFP)↔phone mic fallback.
  /// Records one snapshot of the current route at start, then every change after.
  public static func observeAudioRoutes() {
    trackCurrentRoute(reason: "initial")
    NotificationCenter.default.addObserver(
      forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
    ) { note in
      let raw = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 0
      let reason = AVAudioSession.RouteChangeReason(rawValue: raw)
      trackCurrentRoute(reason: routeReasonName(reason))
    }
  }

  private static func trackCurrentRoute(reason: String) {
    let route = AVAudioSession.sharedInstance().currentRoute
    let fmt: ([AVAudioSessionPortDescription]) -> String = { ports in
      ports.map { "\($0.portType.rawValue)(\($0.portName))" }.joined(separator: ",")
    }
    track(.audioRoute, "route", [
      "reason": reason,
      "inputs": fmt(route.inputs),    // glasses show as BluetoothHFP(device name) — the fallback-detection key
      "outputs": fmt(route.outputs),
    ])
  }

  private static func routeReasonName(_ r: AVAudioSession.RouteChangeReason?) -> String {
    switch r {
    case .newDeviceAvailable: return "newDeviceAvailable"
    case .oldDeviceUnavailable: return "oldDeviceUnavailable"
    case .categoryChange: return "categoryChange"
    case .override: return "override"
    case .wakeFromSleep: return "wakeFromSleep"
    case .noSuitableRouteForCategory: return "noSuitableRouteForCategory"
    case .routeConfigurationChange: return "routeConfigurationChange"
    default: return "unknown"
    }
  }
  #endif

  /// Attempt an immediate upload (cloud mode).
  public static func flush() {
    Task { await WSCore.shared.flush() }
  }

  /// Local event file (jsonl) — for debugging and sharing.
  public static func exportURL() async -> URL? {
    await WSCore.shared.localFileURL
  }
}
