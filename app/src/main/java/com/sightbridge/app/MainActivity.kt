package com.sightbridge.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.pipeline.PipelineController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var runtime by mutableStateOf<PipelineService?>(null)
    private var bound = false
    val pipelineService: PipelineService get() = checkNotNull(runtime)
    val pipelineController: PipelineController get() = checkNotNull(runtime).controller
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = (binder as PipelineService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bound = bindService(Intent(this, PipelineService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent {
            // Supply the activity owner explicitly. The older Compose UI/newer
            // Lifecycle bridge otherwise relies on reflection stripped by R8.
            CompositionLocalProvider(LocalLifecycleOwner provides this@MainActivity) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        runtime?.let { service ->
                            SightBridgeDashboard(service,
                                onStartMetaRegistration = { service.meta.startRegistration(this) },
                                onStartMetaUnregistration = { service.meta.startUnregistration(this) })
                        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading SightBridge") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        bound = false
        runtime = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightBridgeDashboard(
    service: PipelineService,
    onStartMetaRegistration: () -> Unit,
    onStartMetaUnregistration: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val controller = service.controller
    val pipeline by controller.status.collectAsStateWithLifecycle()
    val aiStatus by controller.ai.status.collectAsStateWithLifecycle()
    val selectedSourceType by service.sourceType.collectAsStateWithLifecycle()
    val usbDescription by service.usb.deviceDescription.collectAsStateWithLifecycle()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val streamUrl = "http://127.0.0.1:8080/live.mjpeg"

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val requiredGranted = permissions.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }
        permissionMessage = if (requiredGranted) "Permissions granted. Press the start button again."
            else "Required permissions denied. Allow camera access to start processing."
    }

    fun hasStartPermissions(): Boolean {
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (selectedSourceType == CameraSourceType.META_GLASSES) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val request = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionRequested(context)) {
            context.getSharedPreferences("permissions", Context.MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            request.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (request.isNotEmpty()) {
            permissionLauncher.launch(request.toTypedArray())
            return false
        }
        permissionMessage = null
        return true
    }

    Scaffold(topBar = { TopAppBar(title = { Text("SightBridge") }) }) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Camera", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            "USB Webcam" to CameraSourceType.USB_CAMERA,
                            "Meta Glasses" to CameraSourceType.META_GLASSES,
                            "Mock Generator" to CameraSourceType.MOCK_SIMULATED,
                            "Phone Camera" to CameraSourceType.PHONE_CAMERA
                        ).forEach { (label, type) ->
                            val enabled = !pipeline.busy && !pipeline.mediaRunning && !pipeline.aiRunning
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = selectedSourceType == type, enabled = enabled, role = Role.RadioButton,
                                    onClick = { scope.launch { service.selectSource(type) } }).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedSourceType == type, onClick = null, enabled = enabled)
                                Text(label, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    if (selectedSourceType == CameraSourceType.META_GLASSES) {
                        Button(onClick = onStartMetaRegistration) { Text("Register Meta App") }
                        OutlinedButton(onClick = onStartMetaUnregistration) { Text("Unregister") }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Media Server", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        if (hasStartPermissions()) service.startMedia()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !pipeline.busy && !pipeline.mediaRunning) {
                        Text("Media Server Start")
                    }
                    OutlinedButton(onClick = { service.stopMedia() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !pipeline.busy && pipeline.mediaRunning) {
                        Text("Media Server Stop")
                    }
                    if (pipeline.mediaRunning) {
                        Text("Streaming URL for the vOICe: $streamUrl")
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(streamUrl))
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy Streaming URL") }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { if (hasStartPermissions()) service.startAi() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !pipeline.busy && !pipeline.aiRunning) {
                        Text("Start AI Processing")
                    }
                    OutlinedButton(onClick = { service.stopAi() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !pipeline.busy && pipeline.aiRunning) {
                        Text("Stop AI Processing")
                    }
                    if (pipeline.aiRunning) {
                        Text(aiStatus.description)
                        aiStatus.localizationWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!aiStatus.speechReady) Text("Speech unavailable. Enable an English text-to-speech voice in Android settings.", color = MaterialTheme.colorScheme.error)
                    }
                    aiStatus.error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                }
            }

            // Operational feedback appears only after an action; no startup diagnostics.
            val message = permissionMessage ?: pipeline.message.takeUnless { it == "System ready" }
            message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (pipeline.message.startsWith("Error:") && selectedSourceType == CameraSourceType.USB_CAMERA) Text(usbDescription)
        }
    }
}

private fun notificationPermissionRequested(context: Context): Boolean =
    context.getSharedPreferences("permissions", Context.MODE_PRIVATE).getBoolean("notification_requested", false)
