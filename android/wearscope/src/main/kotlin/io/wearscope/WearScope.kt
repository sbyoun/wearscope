/*
 * WearScope — observability SDK for smart-glasses (DAT) apps (Kotlin).
 *
 *   WearScope.start(context, apiKey = "ws_dev")                    // local mode (file logging only)
 *   WearScope.start(context, apiKey = "gs_...", endpoint = "https://...")
 *   WearScope.track(WSEventType.PHOTO, "capture", mapOf("ms" to "401"))
 *   WearScope.trackError(e, context = "camera.session")            // known DAT errors get an explanation attached
 *   WearScope.observeAudioRoutes(context)                          // glasses↔phone route changes
 */
package io.wearscope

import android.content.Context
import android.util.Log
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.io.File
import java.util.Locale
import java.util.TimeZone

object WearScope {
  const val SDK_VERSION = "0.4.0"

  /** Start the SDK. A null endpoint means local mode (events go to file only). */
  @JvmStatic
  fun start(context: Context, apiKey: String, endpoint: String? = null) {
    WSCore.start(context.applicationContext, apiKey, endpoint)
    track(WSEventType.CUSTOM, "sdk_start", mapOf(
        "sdk" to SDK_VERSION,
        "mode" to if (endpoint == null) "local" else "cloud",
        "locale" to Locale.getDefault().toString(),
        "timezone" to TimeZone.getDefault().id,
    ))
  }

  /**
   * Zero-config start — no API key, no signup.
   *
   * First launch asks the cloud for an anonymous project, caches the credentials,
   * and logs the dashboard URL once. Later launches reuse the cached key. If the
   * server is unreachable the SDK runs in local mode and retries next launch, so
   * instrumentation never blocks or crashes an app.
   *
   * Pass [endpoint] to self-host. Data is claimable into an account later.
   */
  @JvmStatic
  @JvmOverloads
  fun start(context: Context, endpoint: String? = null) {
    val app = context.applicationContext
    val target = endpoint ?: WSProvisioning.DEFAULT_ENDPOINT
    WSProvisioning.cached(app)?.let { cached ->
      WSProvisioning.announce(cached, fresh = false)
      start(app, cached.apiKey, target)
      return
    }
    // Buffer locally from the very first event; upload begins once a key exists.
    start(app, "", null)
    Thread {
      val name = runCatching {
        app.packageManager.getApplicationLabel(app.applicationInfo).toString()
      }.getOrDefault(app.packageName)
      val creds = WSProvisioning.provision(app, target, name)
      if (creds == null) {
        Log.i("WearScope", "Cloud unavailable — local mode (events kept on device)")
      } else {
        WSProvisioning.announce(creds, fresh = true)
        WSCore.adopt(creds.apiKey, target)
      }
    }.apply { isDaemon = true }.start()
  }

  /** Record an event. attrs is metadata only (no payloads — privacy principle). */
  @JvmStatic
  fun track(type: WSEventType, name: String, attrs: Map<String, String> = emptyMap()) {
    WSCore.add(WSEvent(type, name, attrs))
  }

  /** Record an error — known DAT failure modes get an explanation attached automatically. */
  @JvmStatic
  fun trackError(description: String, context: String) {
    val attrs = buildMap {
      put("context", context)
      put("description", description.take(500))
      WSErrorDecoder.explain(description)?.let { put("explain", it) }
    }
    WSCore.add(WSEvent(WSEventType.ERROR, context, attrs))
  }

  @JvmStatic
  fun trackError(throwable: Throwable, context: String) =
      trackError(throwable.toString(), context)

  /** Duration helper: call the returned lambda on completion to record elapsed ms. */
  @JvmStatic
  fun measure(
      type: WSEventType,
      name: String,
      attrs: Map<String, String> = emptyMap(),
  ): (Map<String, String>) -> Unit {
    val t0 = System.currentTimeMillis()
    return { extra ->
      track(type, name, attrs + extra + ("ms" to (System.currentTimeMillis() - t0).toString()))
    }
  }

  /** Attempt an immediate upload (cloud mode). */
  @JvmStatic fun flush() = WSCore.flush()

  /** Local event file (jsonl) — for debugging and sharing. */
  @JvmStatic fun exportFile(): File? = WSCore.exportFile()

  // MARK: - Audio route observation (detects the silent glasses↔phone mic fallback)

  /** Records one snapshot of the current route at start, then every device add/remove after. */
  @JvmStatic
  fun observeAudioRoutes(context: Context) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    trackRoutes(am, reason = "initial")
    am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) =
          trackRoutes(am, reason = "added:" + added.joinToString(",") { describe(it) })

      override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) =
          trackRoutes(am, reason = "removed:" + removed.joinToString(",") { describe(it) })
    }, null)
  }

  private fun trackRoutes(am: AudioManager, reason: String) {
    val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString(",") { describe(it) }
    val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        .joinToString(",") { describe(it) }
    track(WSEventType.AUDIO_ROUTE, "route", mapOf(
        "reason" to reason.take(200),
        "inputs" to inputs.take(300),   // glasses show as BT_SCO(device name) — the fallback-detection key
        "outputs" to outputs.take(300),
    ))
  }

  private fun describe(device: AudioDeviceInfo): String {
    val type = when (device.type) {
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
      AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
      AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
      AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
      else -> "T${device.type}"
    }
    return "$type(${device.productName})"
  }
}
