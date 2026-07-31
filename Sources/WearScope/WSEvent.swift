/*
 * WearScope event model — 1:1 with the server ingest contract (docs/OBSERVABILITY.md §5a).
 * Privacy principle: attrs carries metadata only. No audio/video/photo/transcript payloads.
 */

import Foundation

public enum WSEventType: String, Codable, Sendable {
  case sessionState  // DAT session state transitions
  case stream        // stream lifecycle, fps, resolution
  case photo         // capture latency, size
  case audioRoute    // audio route changes (glasses↔phone fallback)
  case thermal
  case error
  case metric
  case custom
}

public struct WSEvent: Codable, Sendable {
  public let id: UUID
  public let ts: Date
  public let type: WSEventType
  public let name: String
  public let attrs: [String: String]

  public init(type: WSEventType, name: String, attrs: [String: String] = [:]) {
    self.id = UUID()
    self.ts = Date()
    self.type = type
    self.name = name
    self.attrs = attrs
  }
}

/// Ingest batch envelope — POST /v1/ingest body.
struct WSEnvelope: Codable {
  struct SDK: Codable { let name: String; let version: String }
  struct App: Codable { let bundleId: String; let version: String; let build: String }
  struct Device: Codable { let model: String; let os: String }
  struct Session: Codable { let id: UUID; let startedAt: Date }

  let sdk: SDK
  let app: App
  let device: Device
  let session: Session
  let events: [WSEvent]
}

extension JSONEncoder {
  static var gs: JSONEncoder {
    let e = JSONEncoder()
    e.dateEncodingStrategy = .iso8601
    return e
  }
}
