/*
 * WearScope event model — 1:1 with the server ingest contract (same wire format as the iOS SDK).
 * Privacy principle: attrs carries metadata only. No audio/video/photo/transcript payloads.
 */
package io.wearscope

import java.time.Instant
import java.util.UUID
import org.json.JSONObject

enum class WSEventType(val wire: String) {
  SESSION_STATE("sessionState"),
  STREAM("stream"),
  PHOTO("photo"),
  AUDIO_ROUTE("audioRoute"),
  THERMAL("thermal"),
  ERROR("error"),
  METRIC("metric"),
  CUSTOM("custom"),
}

internal class WSEvent(
    val type: WSEventType,
    val name: String,
    val attrs: Map<String, String>,
) {
  val id: String = UUID.randomUUID().toString()
  val ts: Instant = Instant.now()

  fun toJson(): JSONObject =
      JSONObject()
          .put("id", id)
          .put("ts", ts.toString())
          .put("type", type.wire)
          .put("name", name)
          .put("attrs", JSONObject(attrs))
}
