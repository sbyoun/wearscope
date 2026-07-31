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
  }

  /** Track per-device name and link state (connected/disconnected) — "registered" is not "connected". */
  private fun observeDevice(id: DeviceIdentifier) {
    val key = id.toString()
    if (!observedDevices.add(key)) return
    val flow = Wearables.devicesMetadata[id] ?: return
    scope.launch {
      var named = false
      flow.collect { device ->
        if (!named) {
          named = true
          // glasses model name — the core segment key for fleet comparisons
          WearScope.track(WSEventType.CUSTOM, "devices", mapOf("names" to device.name, "count" to "1"))
        }
        val link = device.linkState.name.lowercase()
        if (linkStates.put(key, link) != link) {
          WearScope.track(WSEventType.SESSION_STATE, "link",
              mapOf("device" to device.name, "state" to link))
        }
      }
    }
  }

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
      val value = meta?.getString(key) ?: ""
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
