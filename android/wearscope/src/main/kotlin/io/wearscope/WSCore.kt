/*
 * WSCore — event buffering, persistence, batch upload (same design as the iOS WSCore).
 *
 *  - Events append to a jsonl file immediately (crash survival)
 *  - Flush every 50 events or 10 s: POST /v1/ingest if an endpoint is set, else local mode (file only)
 *  - Truncate the file on upload success; keep and retry on failure. 2 MB file cap
 *
 * Zero dependencies: HttpURLConnection + org.json + a single-thread executor.
 */
package io.wearscope

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal object WSCore {
  private const val MAX_FILE_BYTES = 2_000_000L
  private const val FLUSH_COUNT = 50
  private const val FLUSH_SECONDS = 10L

  private val executor = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "WearScope").apply { isDaemon = true }
  }

  private var apiKey = ""
  private var endpoint: String? = null
  private var sessionId = UUID.randomUUID().toString()
  private var sessionStart: Instant = Instant.now()
  private val pending = mutableListOf<WSEvent>()

  private var file: File? = null
  private var appJson = JSONObject()
  private var deviceJson = JSONObject()

  fun start(context: Context, apiKey: String, endpoint: String?) {
    this.apiKey = apiKey
    this.endpoint = endpoint?.trimEnd('/')
    sessionId = UUID.randomUUID().toString()
    sessionStart = Instant.now()

    val dir = File(context.filesDir, "wearscope").apply { mkdirs() }
    file = File(dir, "events.jsonl")

    val pm = context.packageManager
    val info = pm.getPackageInfo(context.packageName, 0)
    appJson = JSONObject()
        .put("bundleId", context.packageName)
        .put("version", info.versionName ?: "0")
        .put("build", info.longVersionCode.toString())
    deviceJson = JSONObject()
        .put("model", Build.MODEL)
        .put("os", "Android ${Build.VERSION.RELEASE}")

    executor.scheduleAtFixedRate({ flushNow() }, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS)
  }

  fun add(event: WSEvent) {
    executor.execute {
      pending.add(event)
      appendToFile(event)
      if (pending.size >= FLUSH_COUNT) flushNow()
    }
  }

  fun flush() {
    executor.execute { flushNow() }
  }

  fun exportFile(): File? = file

  /**
   * Switch a running local-mode session to cloud mode once credentials arrive
   * (zero-config provisioning). Events buffered so far upload on the next flush.
   */
  fun adopt(apiKey: String, endpoint: String) {
    executor.execute {
      this.apiKey = apiKey
      this.endpoint = endpoint.trimEnd('/')
    }
    add(WSEvent(WSEventType.CUSTOM, "provisioned", mapOf("mode" to "cloud")))
  }

  // MARK: - Internals (all on the executor thread only)

  private fun flushNow() {
    val ep = endpoint
    if (ep == null) {  // local mode: already written to the file
      pending.clear()
      return
    }
    if (pending.isEmpty()) return
    val batch = pending.toList()
    pending.clear()

    val envelope = JSONObject()
        .put("sdk", JSONObject().put("name", "wearscope-kotlin").put("version", WearScope.SDK_VERSION))
        .put("app", appJson)
        .put("device", deviceJson)
        .put("session", JSONObject().put("id", sessionId).put("startedAt", sessionStart.toString()))
        .put("events", JSONArray(batch.map { it.toJson() }))

    try {
      val conn = URL("$ep/v1/ingest").openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("X-API-Key", apiKey)
      conn.doOutput = true
      conn.connectTimeout = 10_000
      conn.readTimeout = 10_000
      conn.outputStream.use { it.write(envelope.toString().toByteArray()) }
      val code = conn.responseCode
      conn.disconnect()
      if (code in 200..299) {
        truncateFile()
      } else {
        pending.addAll(0, batch)  // retry next cycle (still on file too)
      }
    } catch (_: Exception) {
      pending.addAll(0, batch)
    }
  }

  private fun appendToFile(event: WSEvent) {
    val f = file ?: return
    if (f.length() > MAX_FILE_BYTES) truncateFile()  // over the cap: drop the old data (simple policy)
    runCatching { f.appendText(event.toJson().toString() + "\n") }
  }

  private fun truncateFile() {
    runCatching { file?.writeText("") }
  }
}
