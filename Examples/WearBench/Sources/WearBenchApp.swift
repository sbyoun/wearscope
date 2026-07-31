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
    // Zero-config: no key, no signup — the first run provisions an anonymous
    // project and prints the dashboard URL. This is the whole setup.
    WearScope.start()
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
