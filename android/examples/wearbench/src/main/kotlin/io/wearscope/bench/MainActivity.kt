/*
 * WearBench (Android) — WearScope integration example + hardware diagnostics.
 * Four init lines are the entire SDK integration (after runtime permissions are granted):
 *   WearScope.start / observeAudioRoutes / Wearables.initialize / WearScopeDAT.observeWearables
 */
package io.wearscope.bench

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import io.wearscope.WearScope
import io.wearscope.dat.WearScopeDAT
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

class MainActivity : ComponentActivity() {

  private var pendingPermission: CompletableDeferred<Boolean>? = null

  private val wearablesPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        pendingPermission?.complete(
            result.getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted)
        pendingPermission = null
      }

  private val runtimePermissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.entries.all { it.value }) initializeStack()
      }

  private var initialized = false
  private lateinit var benchVm: BenchViewModel

  private fun initializeStack() {
    if (initialized) return
    initialized = true
    // ↓ the entire SDK integration (local mode if the BuildConfig keys are absent)
    WearScope.start(this, BuildConfig.WS_API_KEY.ifBlank { "ws_dev" },
        BuildConfig.WS_ENDPOINT.ifBlank { null })
    WearScope.observeAudioRoutes(this)
    Wearables.initialize(this)
    WearScopeDAT.observeWearables(this)
    benchVm.startObserving()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val vm: BenchViewModel = viewModel()
      benchVm = vm
      vm.requestCameraPermission = {
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        wearablesPermissionLauncher.launch(Permission.CAMERA)
        deferred.await()
      }
      MaterialTheme { BenchScreen(vm, onRegister = { vm.register(this) },
          onUnregister = { vm.unregister(this) }) }
    }
    runtimePermissions.launch(arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA))
  }
}

@Composable
private fun BenchScreen(vm: BenchViewModel, onRegister: () -> Unit, onUnregister: () -> Unit) {
  val registration by vm.registration.collectAsState()
  val deviceName by vm.deviceName.collectAsState()
  val link by vm.link.collectAsState()
  val lines by vm.lines.collectAsState()
  val summary by vm.summary.collectAsState()
  val running by vm.running.collectAsState()
  val audioReport by vm.audioReport.collectAsState()
  var showTimeline by remember { mutableStateOf(false) }

  Scaffold { inner ->
    if (showTimeline) {
      TimelineScreen(Modifier.padding(inner), onBack = { showTimeline = false })
      return@Scaffold
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(inner).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item { Text("WearBench", style = MaterialTheme.typography.headlineSmall) }

      item { Text("Connection", style = MaterialTheme.typography.titleMedium) }
      item { Text("Registration: $registration · Glasses: ${deviceName ?: "-"}") }
      item { Text("Link: $link") }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = onRegister) { Text("Register glasses") }
          OutlinedButton(onClick = onUnregister) { Text("Unregister") }
        }
      }
      item { HorizontalDivider() }

      item { Text("Camera bench", style = MaterialTheme.typography.titleMedium) }
      item {
        Button(onClick = { vm.runBench() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
          Text(if (running) "Running…" else "Run bench (medium → high → 3 stills)")
        }
      }
      items(lines) { line ->
        Text(line, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      summary?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
      item { HorizontalDivider() }

      item { Text("Audio check", style = MaterialTheme.typography.titleMedium) }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { vm.runAudioCheck() }) { Text("Mic check (2 s)") }
          OutlinedButton(onClick = { vm.playTone() }) { Text("Test tone") }
        }
      }
      audioReport?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
      item { HorizontalDivider() }

      item { OutlinedButton(onClick = { showTimeline = true }) { Text("View event timeline") } }
    }
  }
}

@Composable
private fun TimelineScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
  val events = remember {
    WearScope.exportFile()?.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
      runCatching { JSONObject(line) }.getOrNull()
    }?.reversed() ?: emptyList()
  }
  Column(modifier.fillMaxSize().padding(16.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Timeline (${events.size})", style = MaterialTheme.typography.titleMedium)
      OutlinedButton(onClick = onBack) { Text("Close") }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      items(events) { e ->
        val type = e.optString("type")
        val attrs = e.optJSONObject("attrs") ?: JSONObject()
        val explain = attrs.optString("explain")
        Column {
          Text("${e.optString("ts").substringAfter('T').take(8)}  $type/${e.optString("name")}",
              style = MaterialTheme.typography.bodySmall,
              color = if (type == "error") MaterialTheme.colorScheme.error
                      else MaterialTheme.colorScheme.onSurface)
          val rest = attrs.keys().asSequence().filter { it != "explain" }
              .map { "$it=${attrs.optString(it)}" }.sorted().joinToString(" · ")
          if (rest.isNotEmpty()) {
            Text(rest, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (explain.isNotEmpty()) {
            Text("💡 $explain", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }
}
