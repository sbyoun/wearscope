/*
 * BenchViewModel — camera bench (stream open, fps, jitter, stills) and audio check.
 * Instrumentation records WearScopeDAT auto-observation plus the bench's own metrics (same design as iOS BenchEngine).
 */
package io.wearscope.bench

import android.app.Activity
import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import io.wearscope.WSEventType
import io.wearscope.WearScope
import io.wearscope.dat.WearScopeDAT
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BenchViewModel(app: Application) : AndroidViewModel(app) {
  val registration = MutableStateFlow("-")
  val deviceName = MutableStateFlow<String?>(null)
  val link = MutableStateFlow("no device")
  val lines = MutableStateFlow<List<String>>(emptyList())
  val summary = MutableStateFlow<String?>(null)
  val running = MutableStateFlow(false)
  val audioReport = MutableStateFlow<String?>(null)

  private var deviceId: DeviceIdentifier? = null
  /** Wired up by MainActivity — needs the Meta AI deep-link round trip, so only possible with an Activity context. */
  var requestCameraPermission: (suspend () -> Boolean)? = null

  fun startObserving() {
    viewModelScope.launch {
      Wearables.registrationState.collect { registration.value = it.name.lowercase() }
    }
    viewModelScope.launch {
      Wearables.devices.collect { ids ->
        deviceId = ids.firstOrNull()
        if (ids.isEmpty()) {
          deviceName.value = null
          link.value = "no device"
        } else {
          Wearables.devicesMetadata[ids.first()]?.let { flow ->
            launch {
              flow.collect { device ->
                deviceName.value = device.name
                link.value = when (device.linkState) {
                  LinkState.CONNECTED -> "connected ✅"
                  LinkState.CONNECTING -> "connecting…"
                  else -> "disconnected ⚠️"
                }
              }
            }
          }
        }
      }
    }
  }

  fun register(activity: Activity) {
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: Activity) {
    Wearables.startUnregistration(activity)
    note("Unregister requested — register again once it completes")
  }

  // MARK: - Bench

  fun runBench() {
    if (running.value) return
    viewModelScope.launch(Dispatchers.Default) {
      running.value = true
      lines.value = emptyList()
      summary.value = null
      try {
        runBenchInner()
      } finally {
        running.value = false
        WearScope.flush()
      }
    }
  }

  private suspend fun runBenchInner() {
    WearScope.track(WSEventType.CUSTOM, "bench_start",
        mapOf("devices" to if (deviceId == null) "0" else "1"))
    val id = deviceId ?: run { note("No device — connect and register the glasses first"); return }

    // Glasses camera permission (Meta AI round trip)
    val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
    WearScope.track(WSEventType.CUSTOM, "permission",
        mapOf("perm" to "camera", "phase" to "check", "status" to status.toString().lowercase()))
    if (status != PermissionStatus.Granted) {
      note("Requesting camera permission — approve it in the Meta AI app")
      val granted = requestCameraPermission?.invoke() ?: false
      WearScope.track(WSEventType.CUSTOM, "permission",
          mapOf("perm" to "camera", "phase" to "request", "status" to if (granted) "granted" else "denied"))
      if (!granted) {
        note("Camera permission denied — stopping")
        WearScope.trackError("camera permission not granted", "bench.permission")
        return
      }
    }

    note("Opening session…")
    val session = Wearables.createSession(SpecificDeviceSelector(id)).getOrNull() ?: run {
      note("Session creation failed")
      WearScope.trackError("createSession failed", "bench.session")
      return
    }
    WearScopeDAT.observeSession(session)
    session.start()   // Android DAT requires an explicit start to reach STARTED

    val report = mutableListOf<String>()
    for ((label, quality) in listOf("medium" to VideoQuality.MEDIUM, "high" to VideoQuality.HIGH)) {
      note("[$label] opening stream…")
      val r = streamBench(session, label, quality)
      if (r == null) report.add("$label: failed") else {
        note("→ $r")
        report.add(r)
      }
    }

    note("[photo] 3 stills…")
    report.addAll(photoBench(session))

    summary.value = report.joinToString("\n")
    session.stop()
  }

  private object StartedSignal : Throwable()

  private suspend fun awaitStarted(session: DeviceSession, timeoutMs: Long): Boolean =
      withTimeoutOrNull(timeoutMs) {
        try {
          session.state.collect { if (it == DeviceSessionState.STARTED) throw StartedSignal }
          false
        } catch (_: StartedSignal) {
          true
        }
      } ?: false

  private suspend fun streamBench(session: DeviceSession, label: String, quality: VideoQuality): String? {
    if (!awaitStarted(session, 20_000)) { note("Timed out waiting for session STARTED"); return null }
    val stream = session
        .addStream(StreamConfiguration(videoQuality = quality, frameRate = 15, compressVideo = false))
        .getOrNull() ?: return null
    WearScopeDAT.observeStream(stream, label)
    WearScopeDAT.observeFrames(stream, label = label)
    stream.start()

    var frames = 0
    var res = ""
    val frameJob = viewModelScope.launch(Dispatchers.Default) {
      stream.videoStream.collect { f ->
        frames += 1
        res = "${f.width}x${f.height}"
      }
    }

    val t0 = System.currentTimeMillis()
    var waited = 0L
    while (stream.state.value != StreamState.STREAMING && waited < 30_000) {
      delay(200); waited += 200
    }
    if (stream.state.value != StreamState.STREAMING) {
      frameJob.cancel(); stream.stop()
      WearScope.trackError("stream start timeout ($waited ms)", "bench.stream")
      return null
    }
    val openMs = System.currentTimeMillis() - t0

    frames = 0
    delay(8_000)  // 8 s measurement
    val fps = frames / 8.0
    frameJob.cancel()
    stream.stop()
    var stopWait = 0L
    while (stream.state.value != StreamState.STOPPED && stopWait < 5_000) { delay(200); stopWait += 200 }

    WearScope.track(WSEventType.METRIC, "bench_stream", mapOf(
        "res" to label, "open_ms" to openMs.toString(),
        "fps" to String.format(java.util.Locale.US, "%.1f", fps), "frame_res" to res))
    return "$label: open ${openMs}ms · ${String.format(java.util.Locale.US, "%.1f", fps)}fps · $res"
  }

  private suspend fun photoBench(session: DeviceSession): List<String> {
    if (!awaitStarted(session, 20_000)) return listOf("photo: session wait failed")
    val stream = session
        .addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15, compressVideo = false))
        .getOrNull() ?: return listOf("photo: stream failed")
    WearScopeDAT.observeStream(stream, "photo")
    stream.start()
    var waited = 0L
    while (stream.state.value != StreamState.STREAMING && waited < 30_000) { delay(200); waited += 200 }
    if (stream.state.value != StreamState.STREAMING) { stream.stop(); return listOf("photo: stream start failed") }

    val out = mutableListOf<String>()
    for (i in 1..3) {
      val t0 = System.currentTimeMillis()
      var line = "photo $i: failed"
      stream.capturePhoto()
          .onSuccess { photo ->
            val ms = System.currentTimeMillis() - t0
            WearScopeDAT.trackPhoto(photo, ms)  // records latency, size, measured resolution
            line = "photo $i: ${ms}ms"
          }
          .onFailure { e, _ -> WearScope.trackError(e.toString(), "bench.photo") }
      out.add(line)
      note(line)
    }
    stream.stop()
    return out
  }

  // MARK: - Audio check

  fun runAudioCheck() {
    viewModelScope.launch(Dispatchers.Default) {
      val am = getApplication<Application>().getSystemService(Application.AUDIO_SERVICE) as AudioManager
      // If glasses (SCO) are present, set them as the communication device — verifies the HFP mic path
      val sco = am.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
      }
      sco?.let { am.setCommunicationDevice(it) }
      delay(500)

      val sampleRate = 16_000
      val bufSize = AudioRecord.getMinBufferSize(
          sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val rec = try {
        AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
      } catch (e: SecurityException) {
        audioReport.value = "Microphone permission denied"; return@launch
      }
      if (rec.state != AudioRecord.STATE_INITIALIZED) {
        audioReport.value = "AudioRecord initialization failed"; return@launch
      }
      rec.startRecording()
      var sumSq = 0.0
      var count = 0L
      val buf = ShortArray(2048)
      val t0 = System.currentTimeMillis()
      while (System.currentTimeMillis() - t0 < 2_000) {  // 2 s recording
        val n = rec.read(buf, 0, buf.size)
        for (j in 0 until n) { val v = buf[j] / 32768.0; sumSq += v * v }
        count += n
      }
      rec.stop(); rec.release()
      am.clearCommunicationDevice()

      val db = if (count > 0) 20 * log10(sqrt(sumSq / count).coerceAtLeast(1e-9)) else -120.0
      val viaGlasses = sco != null
      val text = (if (viaGlasses) "✅ Glasses (SCO) mic" else "⚠️ Phone mic (no SCO device)") +
          " · ${sampleRate}Hz · avg ${db.toInt()}dBFS" + if (db < -50) " (near-silent — speak while measuring)" else ""
      audioReport.value = text
      WearScope.track(WSEventType.AUDIO_ROUTE, "bench_mic", mapOf(
          "via_glasses" to viaGlasses.toString(), "sample_rate" to sampleRate.toString(),
          "avg_dbfs" to db.toInt().toString()))
    }
  }

  fun playTone() {
    ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        .startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
    WearScope.track(WSEventType.AUDIO_ROUTE, "bench_tone", emptyMap())
  }

  private fun note(message: String) {
    lines.value = lines.value + message
  }
}
