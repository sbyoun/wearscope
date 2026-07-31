/*
 * TimelineView — in-app viewer for the WearScope local event file (jsonl).
 * Shows what the SDK captured without any server — a demo of the SDK in itself.
 */

import WearScope
import SwiftUI

struct TimelineEvent: Decodable, Identifiable {
  let id: UUID
  let ts: Date
  let type: String
  let name: String
  let attrs: [String: String]
}

struct TimelineView: View {
  @State private var events: [TimelineEvent] = []
  @State private var fileURL: URL?

  var body: some View {
    List(events.reversed()) { event in
      VStack(alignment: .leading, spacing: 2) {
        HStack {
          Text("\(event.type)/\(event.name)")
            .font(.caption.bold())
            .foregroundStyle(event.type == "error" ? .red : .primary)
          Spacer()
          Text(event.ts, format: .dateTime.hour().minute().second())
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        if let explain = event.attrs["explain"] {
          Text("💡 " + explain).font(.caption2).foregroundStyle(.red)
        }
        let rest = event.attrs.filter { $0.key != "explain" }
          .map { "\($0.key)=\($0.value)" }.sorted().joined(separator: " · ")
        if !rest.isEmpty {
          Text(rest).font(.caption2).foregroundStyle(.secondary)
        }
      }
    }
    .navigationTitle("Timeline (\(events.count))")
    .toolbar {
      if let fileURL {
        ShareLink(item: fileURL) { Image(systemName: "square.and.arrow.up") }
      }
      Button("Refresh") { Task { await load() } }
    }
    .task { await load() }
  }

  private func load() async {
    guard let url = await WearScope.exportURL(),
          let text = try? String(contentsOf: url, encoding: .utf8) else { return }
    fileURL = url
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    events = text.split(separator: "\n").compactMap {
      try? decoder.decode(TimelineEvent.self, from: Data($0.utf8))
    }
  }
}
