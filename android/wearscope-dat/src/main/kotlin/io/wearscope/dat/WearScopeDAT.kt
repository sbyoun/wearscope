/*
 * WearScopeDAT — one-line auto-instrumentation for Meta Wearables DAT (Android).
 *
 *   WearScopeDAT.observeWearables(context)      // preflight + registration, devices, links, env
 *   WearScopeDAT.observeSession(session)        // session state transitions (dwell time), errors
 *   WearScopeDAT.observeStream(stream)          // stream state transitions, errors
 *   WearScopeDAT.observeFrames(stream)          // fps + inter-frame p95 + measured resolution
 *   WearScopeDAT.trackPhoto(photo, ms)          // still latency, size, resolution
 *
 * Everything lands in the WearScope timeline. Metadata only — never frame/photo payloads.
 */
package io.wearscope.dat

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.DeviceIdentifier
import io.wearscope.WSEventType
import io.wearscope.WearScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object WearScopeDAT {
  /** Version of DAT this adapter is built against — recorded in env as a fleet segment key. */
  const val DAT_VERSION = "0.8.0"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val linkStates = ConcurrentHashMap<String, String>()
  private val observedDevices = ConcurrentHashMap.newKeySet<String>()
  /** Last known session state per label — used to spot session/stream contradictions. */
  private val sessionStates = ConcurrentHashMap<String, String>()
  private val orphanReported = ConcurrentHashMap.newKeySet<String>()

  // MARK: - Wearables (preflight · registration · devices · links · env)

  /** Once at app start — call right after `Wearables.initialize(context)`. */
  fun observeWearables(context: Context) {
    preflight(context)
    WearScope.track(WSEventType.CUSTOM, "env", mapOf(
        "dat_sdk" to DAT_VERSION,
        "locale" to java.util.Locale.getDefault().toString(),
        "timezone" to java.util.TimeZone.getDefault().id,
    ))
    scope.launch {
      Wearables.registrationState.collect { state ->
        WearScope.track(WSEventType.SESSION_STATE, "registration",
            mapOf("state" to state.name.lowercase()))
      }
    }
    scope.launch {
      Wearables.registrationErrorStream.collect { error ->
        WearScope.trackError(error.toString(), "dat.registration")
      }
    }
    scope.launch {
      Wearables.devices.collect { ids ->
        WearScope.track(WSEventType.CUSTOM, "devices", mapOf("count" to ids.size.toString()))
        ids.forEach { observeDevice(it) }
      }
    }
    observeThermal()
  }

  /** Track per-device name and link state (connected/disconnected) — "registered" is not "connected". */
  private fun observeDevice(id: DeviceIdentifier) {
    val key = id.toString()
    if (!observedDevices.add(key)) return
    val flow = Wearables.devicesMetadata[id] ?: return
    scope.launch {
      var named = false
      flow.collect { device ->
        // Report the device TYPE, not the display name: names carry a per-unit
        // serial ("RB Meta 029F") and differ across platforms, which would split
        // one model into many keys and make fleet baselines meaningless.
        val model = canonicalModel(device.deviceType.name)
        if (!named) {
          named = true
          WearScope.track(WSEventType.CUSTOM, "devices", mapOf("models" to model, "count" to "1"))
        }
        val link = device.linkState.name.lowercase()
        if (linkStates.put(key, link) != link) {
          WearScope.track(WSEventType.SESSION_STATE, "link",
              mapOf("device" to model, "state" to link))
        }
      }
    }
  }

  // MARK: - Thermal (glasses throttling shows up as fps drops and stalled streams)

  /**
   * Observe the glasses' thermal level. Throttling is a common hidden cause of
   * degraded streaming, and nothing else in the app surfaces it.
   */
  fun observeThermal() {
    scope.launch {
      Wearables.devices.collect { ids ->
        ids.firstOrNull()?.let { id ->
          launch {
            var last = ""
            Wearables.getDeviceState(id).collect { state ->
              val level = state.thermalLevel.name.lowercase()
              if (level == last) return@collect
              last = level
              WearScope.track(WSEventType.THERMAL, "level", mapOf("level" to level))
              if (level in setOf("severe", "critical", "emergency", "shutdown")) {
                WearScope.trackError(
                    "glasses thermal level $level — expect throttled fps and stalled streams",
                    "dat.thermal")
              }
            }
          }
        }
      }
    }
  }

  /**
   * Canonical model slug — platforms spell the same device differently
   * ("Ray-Ban Meta" vs "RAY_BAN_META"), which would split one model into several
   * fleet groups. Lowercase alphanumerics only.
   */
  private fun canonicalModel(raw: String): String =
      raw.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "unknown" }

  // MARK: - Config preflight — catches "silent failures" in the first-run timeline

  private fun preflight(context: Context) {
    val issues = mutableListOf<String>()
    val pm = context.packageManager

    val requested = runCatching {
      pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
          .requestedPermissions?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())
    for (perm in listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.INTERNET")) {
      if (perm !in requested) {
        issues.add("$perm missing from the manifest — device discovery/upload fails silently")
      }
    }

    val meta = runCatching {
      pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    }.getOrNull()
    for (key in listOf("com.meta.wearable.mwdat.APPLICATION_ID", "com.meta.wearable.mwdat.CLIENT_TOKEN")) {
      // A numeric-looking manifest value is bundled as Int/Long, so getString() returns null
      // even when the credential is present — read it type-agnostically.
      val value = meta?.get(key)?.toString().orEmpty()
      if (value.isBlank() || value.startsWith("\${")) {
        issues.add("meta-data $key is empty — check Dev Center credential injection (manifestPlaceholders)")
      }
    }

    if (issues.isEmpty()) {
      WearScope.track(WSEventType.CUSTOM, "preflight", mapOf("result" to "ok"))
    } else {
      issues.forEach { WearScope.trackError(it, "preflight") }
    }
  }

  // MARK: - Session · Stream

  /** Record session state transitions (with dwell time per state) and errors. */
  fun observeSession(session: DeviceSession, label: String = "session") {
    scope.launch {
      var last = ""
      var t0 = System.currentTimeMillis()
      session.state.collect { state ->
        val name = state.name.lowercase()
        if (name != last) {
          val attrs = buildMap {
            put("state", name)
            put("label", label)
            if (last.isNotEmpty()) {
              put("prev", last)
              put("ms_in_prev", (System.currentTimeMillis() - t0).toString())
            }
          }
          WearScope.track(WSEventType.SESSION_STATE, "session", attrs)
          sessionStates[label] = name
          last = name
          t0 = System.currentTimeMillis()
        }
      }
    }
    scope.launch {
      session.errors.collect { error ->
        WearScope.trackError(error.toString(), "dat.$label")
      }
    }
  }

  /** Record stream state transitions (dwell time) and errors. */
  fun observeStream(stream: Stream, label: String = "stream") {
    // A stream that sits in a transitional state while everything else claims to be
    // healthy is the shape of most "it just never starts" reports: surface the
    // contradiction instead of waiting for a timeout.
    scope.launch {
      var reported = false
      var seen = ""
      var since = System.currentTimeMillis()
      while (isActive && stream.state.value != StreamState.STOPPED) {
        val name = stream.state.value.name.lowercase()
        if (name != seen) { seen = name; since = System.currentTimeMillis(); reported = false }
        val stuckMs = System.currentTimeMillis() - since
        if (!reported && stuckMs > 15_000 && name != "streaming") {
          reported = true
          val session = sessionStates.values.firstOrNull()
          WearScope.trackError(
              "stream stuck in $name for ${stuckMs / 1000}s" +
                  (session?.let { " while session is $it" } ?: ""),
              "dat.$label.stall")
          WearScope.track(WSEventType.STREAM, "stall",
              mapOf("state" to name, "label" to label, "ms" to stuckMs.toString()))
        }
        delay(1_000)
      }
    }
    scope.launch {
      var last = ""
      var t0 = System.currentTimeMillis()
      stream.state.collect { state ->
        val name = state.name.lowercase()
        if (name != last) {
          val attrs = buildMap {
            put("state", name)
            put("label", label)
            if (last.isNotEmpty()) {
              put("prev", last)
              put("ms_in_prev", (System.currentTimeMillis() - t0).toString())
            }
          }
          WearScope.track(WSEventType.STREAM, "state", attrs)
          last = name
          t0 = System.currentTimeMillis()
        }
      }
    }
    scope.launch {
      stream.errorStream.collect { error ->
        WearScope.trackError(error.toString(), "dat.$label")
      }
    }
  }

  /** Report fps, inter-frame p95, measured resolution periodically (BT-link jitter is a community FAQ). */
  fun observeFrames(stream: Stream, reportEverySeconds: Long = 5, label: String = "stream") {
    val stats = FrameStats()
    scope.launch {
      stream.videoStream.collect { frame ->
        stats.mark("${frame.width}x${frame.height}")
        // Frames after stop mean the glasses camera was never released; the next
        // open then hangs. Report once — invisible from the app side otherwise.
        if (stream.state.value == StreamState.STOPPED && orphanReported.add(label)) {
          WearScope.trackError(
              "frames still arriving after the stream stopped — the glasses camera was not " +
                  "released; the next stream open may hang (power-cycle the glasses to recover)",
              "dat.$label.orphan")
        }
      }
    }
    scope.launch {
      while (isActive && stream.state.value != StreamState.STOPPED) {
        delay(reportEverySeconds * 1000)
        stats.drain(reportEverySeconds.toDouble())?.let { r ->
          val attrs = buildMap {
            put("fps", r.fps)
            put("gap_p95_ms", r.p95)
            put("gap_max_ms", r.max)
            put("label", label)
            r.res?.let { put("res", it) }
          }
          WearScope.track(WSEventType.METRIC, "frames", attrs)
        }
      }
    }
  }

  // MARK: - Photos (Android capturePhoto is a suspend Result — the caller passes the result in)

  /** Record a still result: latency, size, measured resolution. Never records the payload. */
  fun trackPhoto(photo: PhotoData, ms: Long, label: String = "photo") {
    val attrs = mutableMapOf("ms" to ms.toString(), "label" to label)
    when (photo) {
      is PhotoData.Bitmap -> {
        attrs["res"] = "${photo.bitmap.width}x${photo.bitmap.height}"
        attrs["bytes"] = photo.bitmap.byteCount.toString()
      }
      is PhotoData.HEIC -> {
        val buf = photo.data.duplicate()
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        attrs["bytes"] = bytes.size.toString()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }  // header only
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth > 0) attrs["res"] = "${opts.outWidth}x${opts.outHeight}"
      }
    }
    WearScope.track(WSEventType.PHOTO, "capture", attrs)
  }
}

/** Frame-arrival accounting — listener may fire on any thread, lock-guarded. */
private class FrameStats {
  private val lock = Any()
  private var lastAt = 0L
  private val gapsMs = mutableListOf<Double>()
  private var res: String? = null

  fun mark(dims: String?) = synchronized(lock) {
    val now = System.currentTimeMillis()
    if (lastAt != 0L) gapsMs.add((now - lastAt).toDouble())
    if (gapsMs.size > 2000) gapsMs.subList(0, gapsMs.size - 2000).clear()
    dims?.let { res = it }
    lastAt = now
  }

  data class Report(val fps: String, val p95: String, val max: String, val res: String?)

  fun drain(windowSeconds: Double): Report? = synchronized(lock) {
    if (gapsMs.isEmpty()) return null
    val sorted = gapsMs.sorted()
    val p95 = sorted[minOf(sorted.size - 1, (sorted.size * 0.95).toInt())]
    val report = Report(
        fps = String.format(java.util.Locale.US, "%.1f", gapsMs.size / windowSeconds),
        p95 = p95.toInt().toString(),
        max = (sorted.last()).toInt().toString(),
        res = res)
    gapsMs.clear()
    report
  }
}
