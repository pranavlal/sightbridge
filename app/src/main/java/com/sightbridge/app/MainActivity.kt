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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.pipeline.PipelineController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var runtime by mutableStateOf<PipelineService?>(null)
    private var bound = false
    private var permissionMessage by mutableStateOf<String?>(null)
    private var pendingStart: (() -> Unit)? = null
    private var pendingSource: CameraSourceType? = null
    private var androidPermissionRequestInProgress = false
    private var metaPermissionCheckInProgress = false
    private var metaPermissionPromptInProgress = false
    val pipelineService: PipelineService get() = checkNotNull(runtime)
    val pipelineController: PipelineController get() = checkNotNull(runtime).controller
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = (binder as PipelineService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null }
    }

    private val androidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            androidPermissionRequestInProgress = false
            getSharedPreferences("permissions", Context.MODE_PRIVATE).edit()
                .putBoolean("first_run_requested", true).apply()
            val neededForStart = if (runtime?.sourceType?.value == CameraSourceType.META_GLASSES)
                Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.CAMERA
            if (pendingStart != null && result[neededForStart] == false) {
                pendingStart = null
                pendingSource = null
                permissionMessage = "Permission declined. Allow it in Android settings to start this camera."
                return@registerForActivityResult
            }
            permissionMessage = if (result.values.any { !it })
                "Some Android permissions were declined. You can allow them when starting a camera."
                else null
            if (pendingStart != null) continuePendingStart()
        }
    private val metaCameraPermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            metaPermissionPromptInProgress = false
            result.fold(
                onSuccess = { status ->
                    if (status == PermissionStatus.Granted) {
                        permissionMessage = null
                        runPendingStart()
                    } else {
                        pendingStart = null
                        pendingSource = null
                        permissionMessage = "Meta glasses camera access was declined."
                    }
                },
                onFailure = { error, _ ->
                    pendingStart = null
                    pendingSource = null
                    permissionMessage = error.description
                },
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bound = bindService(Intent(this, PipelineService::class.java), connection, Context.BIND_AUTO_CREATE)
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                if (state == RegistrationState.REGISTERED && pendingStart != null) continuePendingStart()
            }
        }
        lifecycleScope.launch {
            Wearables.registrationErrorStream.collect { error ->
                pendingStart = null
                pendingSource = null
                permissionMessage = error.description
            }
        }
        val preferences = getSharedPreferences("permissions", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("first_run_requested", false)) {
            val missing = listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                androidPermissionRequestInProgress = true
                androidPermissionLauncher.launch(missing.toTypedArray())
            }
            else preferences.edit().putBoolean("first_run_requested", true).apply()
        }
        setContent {
            // Supply the activity owner explicitly. The older Compose UI/newer
            // Lifecycle bridge otherwise relies on reflection stripped by R8.
            CompositionLocalProvider(LocalLifecycleOwner provides this@MainActivity) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        runtime?.let { service ->
                            SightBridgeDashboard(service,
                                onStartMetaRegistration = {
                                    if (Wearables.registrationState.value != RegistrationState.REGISTERED &&
                                        Wearables.registrationState.value != RegistrationState.REGISTERING) {
                                        service.meta.startRegistration(this)
                                    }
                                },
                                onStartMetaUnregistration = { service.meta.startUnregistration(this) },
                                onStartMedia = { requestStart(service.sourceType.value) { service.startMedia() } },
                                onStartAi = { requestStart(service.sourceType.value) { service.startAi() } },
                                permissionMessage = permissionMessage)
                        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading SightBridge") }
                    }
                }
            }
        }
    }

    private fun requestStart(source: CameraSourceType, action: () -> Unit) {
        pendingStart = action
        pendingSource = source
        continuePendingStart(source)
    }

    private fun runPendingStart() {
        if (pendingStart == null) return
        if (pendingSource != runtime?.sourceType?.value) {
            pendingStart = null
            pendingSource = null
            permissionMessage = "Camera selection changed. Press Start again."
            return
        }
        val action = pendingStart
        pendingStart = null
        pendingSource = null
        action?.invoke()
    }

    private fun continuePendingStart(source: CameraSourceType? = runtime?.sourceType?.value) {
        if (pendingStart == null) return
        if (androidPermissionRequestInProgress || metaPermissionCheckInProgress || metaPermissionPromptInProgress) return
        val currentSource = source ?: return
        if (pendingSource != currentSource) {
            runPendingStart()
            return
        }
        val required = if (currentSource == CameraSourceType.META_GLASSES)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, required) != PackageManager.PERMISSION_GRANTED) {
            permissionMessage = "Allow the requested Android permission to start this camera."
            androidPermissionRequestInProgress = true
            androidPermissionLauncher.launch(arrayOf(required))
            return
        }
        if (currentSource == CameraSourceType.META_GLASSES) {
            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                permissionMessage = "Complete registration in the Meta AI app, then return to SightBridge."
                if (Wearables.registrationState.value != RegistrationState.REGISTERING)
                    runtime?.meta?.startRegistration(this)
                return
            }
            metaPermissionCheckInProgress = true
            lifecycleScope.launch {
                try {
                    runCatching { runtime?.meta?.requestPermissions() }.fold(
                        onSuccess = { permission ->
                            if (permission?.granted == true) {
                                permissionMessage = null
                                runPendingStart()
                            } else if (permission?.deniedPermissions?.contains("Meta glasses camera") == true) {
                                permissionMessage = "Allow glasses camera access in Meta AI."
                                metaPermissionPromptInProgress = true
                                metaCameraPermissionLauncher.launch(Permission.CAMERA)
                            } else {
                                pendingStart = null
                                pendingSource = null
                                permissionMessage = "Meta glasses permissions are unavailable. Reconnect and try again."
                            }
                        },
                        onFailure = { error ->
                            pendingStart = null
                            pendingSource = null
                            permissionMessage = error.message ?: "Could not check Meta glasses camera access."
                        }
                    )
                } finally {
                    metaPermissionCheckInProgress = false
                }
            }
        } else {
            permissionMessage = null
            runPendingStart()
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
    onStartMetaUnregistration: () -> Unit,
    onStartMedia: () -> Unit,
    onStartAi: () -> Unit,
    permissionMessage: String?,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val controller = service.controller
    val pipeline by controller.status.collectAsStateWithLifecycle()
    val aiStatus by controller.ai.status.collectAsStateWithLifecycle()
    val selectedSourceType by service.sourceType.collectAsStateWithLifecycle()
    val usbDescription by service.usb.deviceDescription.collectAsStateWithLifecycle()
    val registration by Wearables.registrationState.collectAsStateWithLifecycle()
    val metaError by service.meta.error.collectAsStateWithLifecycle()
    val streamUrl = "http://localhost:8080/mjpeg"

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
                        Text(if (registration == RegistrationState.REGISTERED)
                            "Connected to Meta AI. Select Start to use the glasses camera."
                            else "Connect to Meta AI to use the glasses camera.")
                        if (registration != RegistrationState.REGISTERED) {
                            Button(onClick = onStartMetaRegistration, enabled = registration != RegistrationState.REGISTERING) {
                                Text("Connect Meta Glasses")
                            }
                        }
                        if (registration == RegistrationState.REGISTERED) {
                            OutlinedButton(onClick = onStartMetaUnregistration) { Text("Disconnect Meta Glasses") }
                        }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Media Server", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onStartMedia, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !pipeline.busy && !pipeline.mediaRunning) {
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
                    Button(onClick = onStartAi,
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
                        if (!aiStatus.speechReady) Text("Speech unavailable. Choose a downloaded text-to-speech voice in Android settings.", color = MaterialTheme.colorScheme.error)
                    }
                    aiStatus.error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                }
            }

            // Operational feedback appears only after an action; no startup diagnostics.
            val message = permissionMessage ?: pipeline.message.takeUnless { it == "System ready" }
            message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (selectedSourceType == CameraSourceType.META_GLASSES) {
                metaError?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
            }
            if (pipeline.message.startsWith("Error:") && selectedSourceType == CameraSourceType.USB_CAMERA) Text(usbDescription)
        }
    }
}
