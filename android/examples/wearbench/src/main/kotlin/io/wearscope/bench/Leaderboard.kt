/*
 * Leaderboard — "your number vs the fleet".
 *
 * A measurement alone says nothing: is a 15 s stream open normal, or is this rig
 * broken? WearScope's public baselines answer that, and this screen puts your run
 * next to them. No auth: the data is public.
 */
package io.wearscope.bench

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class FleetGroup(val key: String, val n: Int, val p50: Int, val p90: Int)

data class FleetMetric(val metric: String, val unit: String, val groups: List<FleetGroup>)

object Leaderboard {
  private const val URL_STR = "https://gs.foldalpha.com/public/leaderboard"

  /** Fetch public baselines. Must be called off the main thread; returns empty on failure. */
  fun fetch(): List<FleetMetric> = try {
    val conn = URL(URL_STR).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    val body = if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
    conn.disconnect()
    body?.let { parse(JSONObject(it)) } ?: emptyList()
  } catch (_: Exception) {
    emptyList()
  }

  /** Server shape: { "<metric>_ms": { name, attr, groups: [{key, n, p50, p90, max}] }, … } */
  private fun parse(json: JSONObject): List<FleetMetric> =
      json.keys().asSequence().mapNotNull { key ->
        val block = json.optJSONObject(key) ?: return@mapNotNull null
        val raw = block.optJSONArray("groups") ?: return@mapNotNull null
        val groups = (0 until raw.length()).mapNotNull { i ->
          val g = raw.optJSONObject(i) ?: return@mapNotNull null
          val k = g.optString("key").ifBlank { return@mapNotNull null }
          FleetGroup(k, g.optInt("n"), g.optInt("p50"), g.optInt("p90"))
        }
        if (groups.isEmpty()) null
        else FleetMetric(
            metric = block.optString("name").ifBlank { key },
            unit = if (block.optString("attr") == "ms") "ms" else "",
            groups = groups)
      }.sortedBy { it.metric }.toList()
}
