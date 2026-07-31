import SwiftUI

struct ContentView: View {
  @StateObject private var bench = BenchEngine()
  @StateObject private var audio = AudioCheck()

  var body: some View {
    NavigationStack {
      List {
        Section("Connection") {
          LabeledContent("Registration", value: bench.registrationState)
          LabeledContent("Glasses", value: bench.deviceName ?? "-")
          LabeledContent("Link", value: bench.glassesLink)
          Button("Register glasses (opens Meta AI)") {
            Task { await bench.register() }
          }
          Button("Unregister", role: .destructive) {
            Task { await bench.unregister() }
          }
        }

        Section("Camera bench — stream open · fps · jitter · still latency") {
          Button(bench.running ? "Running…" : "Run bench (low → high → 3 stills)") {
            Task { await bench.runBench() }
          }
          .disabled(bench.running)
          ForEach(bench.lines.indices, id: \.self) { i in
            Text(bench.lines[i]).font(.caption).foregroundStyle(.secondary)
          }
          if let summary = bench.summary {
            Text(summary)
              .font(.caption.monospaced())
              .textSelection(.enabled)
          }
        }

        Section("Audio check — which mic is live right now") {
          Text(audio.routeInfo).font(.caption).foregroundStyle(.secondary)
          Button(audio.running ? "Measuring…" : "Mic check (2 s recording)") {
            Task { await audio.runMicCheck() }
          }
          .disabled(audio.running)
          if let report = audio.report {
            Text(report).font(.caption).textSelection(.enabled)
          }
          Button("Play test tone (current output route)") { audio.playTone() }
        }

        Section("WearScope") {
          NavigationLink("View event timeline") { TimelineView() }
        }
      }
      .navigationTitle("WearBench")
      .onAppear {
        bench.configure()
        audio.refreshRoute()
      }
    }
  }
}
