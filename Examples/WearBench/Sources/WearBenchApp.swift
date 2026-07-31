/*
 * WearBench — WearScope example & diagnostics app.
 *
 * Demonstrates the full SDK integration in ~4 lines (see init below),
 * and doubles as a hardware check: stream open time, fps/jitter by
 * resolution, photo latency, mic route & level — all recorded to the
 * WearScope timeline you can browse in the app (no server required).
 */

import WearScope
import MWDATCore
import SwiftUI

@main
struct WearBenchApp: App {
  init() {
    // Key/endpoint come from Secrets.xcconfig via Info.plist; without a key we
    // run in local mode (events recorded to file only — timeline still works).
    let key = Bundle.main.object(forInfoDictionaryKey: "WSAPIKey") as? String
    let endpoint = (Bundle.main.object(forInfoDictionaryKey: "WSEndpoint") as? String)
      .flatMap(URL.init(string:))
    if let key, !key.isEmpty {
      WearScope.start(apiKey: key, endpoint: endpoint)
    } else {
      WearScope.start(apiKey: "ws_dev")  // local mode
    }
    WearScope.observeAudioRoutes()       // glasses↔phone mic fallback detection
  }

  var body: some Scene {
    WindowGroup {
      ContentView()
        .onOpenURL { url in
          Task { _ = try? await Wearables.shared.handleUrl(url) }
        }
    }
  }
}
