/*
 * Leaderboard — "your number vs the fleet".
 *
 * A measurement alone says nothing: is a 15 s stream open normal, or is this rig
 * broken? WearScope's public baselines answer that, and this screen puts your run
 * next to them. No auth: the data is public.
 */

import Foundation
import SwiftUI

struct FleetGroup: Identifiable {
  let key: String       // glasses model
  let n: Int
  let p50: Int
  let p90: Int
  var id: String { key }
}

struct FleetMetric: Identifiable {
  let metric: String    // "warm_open" | "capture" | "frames"
  let unit: String
  let groups: [FleetGroup]
  var id: String { metric }
}

@MainActor
final class Leaderboard: ObservableObject {
  @Published private(set) var metrics: [FleetMetric] = []
  @Published private(set) var loading = false
  @Published private(set) var error: String?

  private static let url = URL(string: "https://gs.foldalpha.com/public/leaderboard")!

  func load() async {
    loading = true
    defer { loading = false }
    error = nil
    do {
      let (data, _) = try await URLSession.shared.data(from: Self.url)
      guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        error = "unexpected response"
        return
      }
      metrics = Self.parse(json)
      if metrics.isEmpty { error = "no public data yet" }
    } catch {
      self.error = error.localizedDescription
    }
  }

  /// Server shape: { "<metric>_ms": { name, attr, groups: [{key, n, p50, p90, max}] }, … }
  private static func parse(_ json: [String: Any]) -> [FleetMetric] {
    json.compactMap { key, value -> FleetMetric? in
      guard let block = value as? [String: Any],
            let raw = block["groups"] as? [[String: Any]] else { return nil }
      let groups = raw.compactMap { g -> FleetGroup? in
        guard let k = g["key"] as? String, let n = g["n"] as? Int else { return nil }
        return FleetGroup(key: k, n: n,
                          p50: (g["p50"] as? Int) ?? 0, p90: (g["p90"] as? Int) ?? 0)
      }
      guard !groups.isEmpty else { return nil }
      let name = (block["name"] as? String) ?? key
      let unit = ((block["attr"] as? String) == "ms") ? "ms" : ""
      return FleetMetric(metric: name, unit: unit, groups: groups)
    }
    .sorted { $0.metric < $1.metric }
  }
}

struct LeaderboardView: View {
  @StateObject private var board = Leaderboard()
  /// Values from the run just finished, e.g. ["capture": 512, "warm_open": 6100].
  let mine: [String: Int]

  var body: some View {
    List {
      Section {
        Text("Public baselines from every device reporting to WearScope. "
             + "Your run is compared against the same metric across the fleet.")
          .font(.caption)
          .foregroundStyle(.secondary)
      }
      ForEach(board.metrics) { metric in
        Section("\(metric.metric) (\(metric.unit))") {
          if let value = mine[metric.metric] {
            LabeledContent("this device", value: "\(value)\(metric.unit)")
              .font(.callout.bold())
          }
          ForEach(metric.groups) { g in
            LabeledContent(g.key.count > 18 ? String(g.key.prefix(18)) + "…" : g.key) {
              Text("p50 \(g.p50) · p90 \(g.p90) · n=\(g.n)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
          }
        }
      }
      if let error = board.error {
        Section { Text(error).font(.caption).foregroundStyle(.secondary) }
      }
    }
    .navigationTitle("Fleet comparison")
    .toolbar {
      Button("Refresh") { Task { await board.load() } }.disabled(board.loading)
    }
    .task { await board.load() }
  }
}
