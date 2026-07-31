/*
 * WSCore — event buffering, persistence, batch upload.
 *
 * Design (docs/OBSERVABILITY.md §4):
 *  - Events append to a jsonl file immediately (crash survival)
 *  - Flush every 50 events or 10 s: POST /v1/ingest if an endpoint is set, else local mode (file only)
 *  - Truncate the file on upload success; keep and retry on failure. 2 MB file cap
 */

import Foundation

actor WSCore {
  static let shared = WSCore()

  private var config: WSConfig?
  private var sessionId = UUID()
  private var sessionStart = Date()
  private var pending: [WSEvent] = []
  private var flushTask: Task<Void, Never>?
  private var fileURL: URL?

  private let maxFileBytes = 2_000_000
  private let flushCount = 50
  private let flushSeconds: UInt64 = 10

  // MARK: - Lifecycle

  func start(config: WSConfig) {
    self.config = config
    sessionId = UUID()
    sessionStart = Date()

    let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("WearScope", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    fileURL = dir.appendingPathComponent("events.jsonl")

    add(WSEvent(type: .custom, name: "sdk_start", attrs: [
      "sdk": WSConfig.sdkVersion,
      "mode": config.endpoint == nil ? "local" : "cloud",
      "locale": Locale.current.identifier,
      "timezone": TimeZone.current.identifier,
    ]))

    flushTask?.cancel()
    flushTask = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: (self?.flushSeconds ?? 10) * 1_000_000_000)
        await self?.flush()
      }
    }
  }

  var localFileURL: URL? { fileURL }

  // MARK: - Events

  func add(_ event: WSEvent) {
    guard config != nil else { return }
    pending.append(event)
    appendToFile(event)
    if pending.count >= flushCount {
      Task { await flush() }
    }
  }

  func flush() async {
    guard let config, config.endpoint != nil else {
      pending.removeAll()  // local mode: already written to the file
      return
    }
    guard !pending.isEmpty else { return }
    let batch = pending
    pending.removeAll()

    let envelope = WSEnvelope(
      sdk: .init(name: "wearscope-swift", version: WSConfig.sdkVersion),
      app: config.appInfo,
      device: config.deviceInfo,
      session: .init(id: sessionId, startedAt: sessionStart),
      events: batch)

    guard let url = config.endpoint?.appendingPathComponent("v1/ingest"),
          let body = try? JSONEncoder.gs.encode(envelope) else { return }

    var req = URLRequest(url: url)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.setValue(config.apiKey, forHTTPHeaderField: "X-API-Key")
    req.httpBody = body

    do {
      let (_, resp) = try await URLSession.shared.data(for: req)
      if let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
        truncateFile()
      } else {
        pending.insert(contentsOf: batch, at: 0)  // retry next cycle (still on file too)
      }
    } catch {
      pending.insert(contentsOf: batch, at: 0)
    }
  }

  // MARK: - File persistence

  private func appendToFile(_ event: WSEvent) {
    guard let fileURL, var line = try? JSONEncoder.gs.encode(event) else { return }
    line.append(0x0A)
    if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize,
       size > maxFileBytes {
      truncateFile()  // over the cap: drop the old data (simple policy — ring buffer in v0.2)
    }
    if let handle = try? FileHandle(forWritingTo: fileURL) {
      defer { try? handle.close() }
      _ = try? handle.seekToEnd()
      try? handle.write(contentsOf: line)
    } else {
      try? line.write(to: fileURL)
    }
  }

  private func truncateFile() {
    guard let fileURL else { return }
    try? Data().write(to: fileURL)
  }
}
