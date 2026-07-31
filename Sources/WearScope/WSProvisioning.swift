/*
 * Zero-config provisioning — no signup, no dashboard visit before you have data.
 *
 * On first launch without an API key, the SDK asks the server for an anonymous
 * project, stores the credentials locally, and prints the dashboard URL once.
 * The key can later be claimed into an account server-side; nothing is lost.
 */

import Foundation

/// Credentials obtained from (or cached for) anonymous provisioning.
struct WSCredentials: Codable, Sendable {
  let apiKey: String
  let projectId: String?
  let dashboardURL: String?
}

enum WSProvisioning {
  /// Default cloud endpoint — zero-config sends here unless the app overrides it.
  static let defaultEndpoint = URL(string: "https://gs.foldalpha.com")!

  private static var storeURL: URL? {
    guard let dir = try? FileManager.default.url(
      for: .applicationSupportDirectory, in: .userDomainMask,
      appropriateFor: nil, create: true) else { return nil }
    let ours = dir.appendingPathComponent("WearScope", isDirectory: true)
    try? FileManager.default.createDirectory(at: ours, withIntermediateDirectories: true)
    return ours.appendingPathComponent("credentials.json")
  }

  static func cached() -> WSCredentials? {
    guard let url = storeURL, let data = try? Data(contentsOf: url) else { return nil }
    return try? JSONDecoder().decode(WSCredentials.self, from: data)
  }

  private static func store(_ creds: WSCredentials) {
    guard let url = storeURL, let data = try? JSONEncoder().encode(creds) else { return }
    try? data.write(to: url, options: .atomic)
  }

  /// Request an anonymous project. Returns nil when the server is unreachable or
  /// declines — the caller stays in local mode and retries on the next launch.
  static func provision(endpoint: URL, appName: String) async -> WSCredentials? {
    var req = URLRequest(url: endpoint.appendingPathComponent("v1/projects/anonymous"))
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = try? JSONSerialization.data(withJSONObject: ["name": appName])
    req.timeoutInterval = 15

    guard let (data, resp) = try? await URLSession.shared.data(for: req),
          let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let key = json["api_key"] as? String, !key.isEmpty else { return nil }

    let creds = WSCredentials(
      apiKey: key,
      projectId: json["project_id"] as? String,
      dashboardURL: json["dashboard_url"] as? String)
    store(creds)
    return creds
  }

  /// One-line console banner so a human (or the agent that wired this up) knows where to look.
  static func announce(_ creds: WSCredentials, fresh: Bool) {
    let where_ = creds.dashboardURL ?? defaultEndpoint.absoluteString
    print("[WearScope] \(fresh ? "Anonymous project created" : "Reporting") → \(where_)")
  }
}
