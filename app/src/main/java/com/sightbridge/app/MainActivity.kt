package com.sightbridge.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import com.sightbridge.camera.usb.UsbCameraSource
import androidx.compose.ui.unit.dp
import com.sightbridge.camera.api.CameraSource
import com.sightbridge.camera.meta.MetaSdkAdapter
import com.sightbridge.camera.mock.SimulatedCameraSource
import com.sightbridge.camera.phone.CameraXPhoneSource
import com.sightbridge.core.health.HealthLevel
import com.sightbridge.core.health.HealthWatchdog
import com.sightbridge.core.model.CameraSourceState
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.output.voicestream.HttpMjpegVoiceStreamAdapter
import com.sightbridge.output.voicestream.VoiceStreamConfig
import com.sightbridge.output.voicestream.VoiceStreamState
import com.sightbridge.server.MjpegHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.sightbridge.ai.OnDeviceAiProcessor
import com.sightbridge.pipeline.PipelineController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

class MainActivity : ComponentActivity() {

    private lateinit var metaAdapter: MetaSdkAdapter
    private lateinit var simulatedSource: SimulatedCameraSource
    private lateinit var usbSource: UsbCameraSource
    private lateinit var phoneSource: CameraXPhoneSource
    private val healthWatchdog = HealthWatchdog()
    lateinit var pipelineController: PipelineController
        private set
    private lateinit var voiceStreamAdapter: HttpMjpegVoiceStreamAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the handset awake for device testing without changing its lock settings.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        usbSource = UsbCameraSource(this)
        metaAdapter = MetaSdkAdapter(this)
        simulatedSource = SimulatedCameraSource()
        phoneSource = CameraXPhoneSource(this).apply { setLifecycleOwner(this@MainActivity) }
        voiceStreamAdapter = HttpMjpegVoiceStreamAdapter(healthWatchdog)
        pipelineController = PipelineController(usbSource, voiceStreamAdapter, OnDeviceAiProcessor(this), healthWatchdog)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SightBridgeDashboard(
                        metaAdapter = metaAdapter,
                        simulatedSource = simulatedSource,
                        phoneSource = phoneSource,
                        usbSource = usbSource,
                        voiceAdapter = voiceStreamAdapter,
                        controller = pipelineController,
                        healthWatchdog = healthWatchdog,
                        onStartMetaRegistration = { metaAdapter.startRegistration(this) },
                        onStartMetaUnregistration = { metaAdapter.startUnregistration(this) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        healthWatchdog.stopWatchdog()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Wrap each component release independently so one failure does not skip remaining cleanups
            runCatching { pipelineController.release() }
            runCatching { metaAdapter.release() }
            runCatching { simulatedSource.release() }
            runCatching { phoneSource.release() }
            runCatching { usbSource.release() }
            runCatching { voiceStreamAdapter.release() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightBridgeDashboard(
    metaAdapter: MetaSdkAdapter,
    simulatedSource: SimulatedCameraSource,
    phoneSource: CameraXPhoneSource,
    usbSource: UsbCameraSource,
    voiceAdapter: HttpMjpegVoiceStreamAdapter,
    controller: PipelineController,
    healthWatchdog: HealthWatchdog,
    onStartMetaRegistration: () -> Unit,
    onStartMetaUnregistration: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedSourceType by remember { mutableStateOf(CameraSourceType.USB_CAMERA) }
    var activeCameraSource by remember { mutableStateOf<CameraSource>(usbSource) }
    var cameraState by remember { mutableStateOf(CameraSourceState.UNINITIALISED) }
    var voiceState by remember { mutableStateOf(VoiceStreamState.STOPPED) }
    val pipeline by controller.status.collectAsState()
    val aiStatus by controller.ai.status.collectAsState()
    val pipelineStarting = pipeline.busy
    val clipboard = LocalClipboardManager.current
    var bindLocalhostOnly by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("System ready") }
    val lanAccessToken = remember { UUID.randomUUID().toString().replace("-", "") }

    val localStreamUrl = "http://127.0.0.1:8080/live.mjpeg" + if (bindLocalhostOnly) "" else "?token=$lanAccessToken"
    val phoneIp = remember { MjpegHttpServer.getPhoneIpAddress(context) }
    val usbDescription by usbSource.deviceDescription.collectAsState()
    val systemHealth by healthWatchdog.systemHealth.collectAsState()

    // Activity Result Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: true
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        } else true

        if (cameraGranted && bluetoothGranted) {
            statusText = "Permissions granted. Press Media Server Start or Start AI Processing again."
        } else {
            statusText = "Error: Required permissions denied."
        }
    }

    // Function to switch camera sources safely
    fun switchCameraSource(newType: CameraSourceType, newSource: CameraSource) {
        if (selectedSourceType == newType) return
        scope.launch {
            if (controller.selectSource(newSource)) {
                selectedSourceType = newType
                activeCameraSource = newSource
            }
        }
    }

    LaunchedEffect(pipeline.message) { statusText = pipeline.message }
    LaunchedEffect(pipeline.needsPermissions) {
        if (pipeline.needsPermissions) {
            val required = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.CAMERA)
            if (selectedSourceType == CameraSourceType.META_GLASSES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) required.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (required.isNotEmpty()) permissionLauncher.launch(required.toTypedArray())
        }
    }

    // Start active health watchdog monitoring
    LaunchedEffect(Unit) {
        healthWatchdog.startWatchdog(scope)
    }

    // Observe active camera source state: use this.launch to automatically cancel on source switch
    LaunchedEffect(activeCameraSource) {
        launch(Dispatchers.Default) {
            activeCameraSource.state.collect { state ->
                cameraState = state
                healthWatchdog.updateHealth(
                    component = "CameraSource",
                    status = if (state == CameraSourceState.STREAMING) HealthLevel.HEALTHY else HealthLevel.DEGRADED,
                    detailCode = state.name
                )
            }
        }
    }

    // Observe vOICe stream state
    LaunchedEffect(voiceAdapter) {
        launch(Dispatchers.Default) {
            voiceAdapter.state.collect { state ->
                voiceState = state
                healthWatchdog.updateHealth(
                    component = "VoiceStreamAdapter",
                    status = if (state == VoiceStreamState.STREAMING) HealthLevel.HEALTHY else HealthLevel.UNAVAILABLE,
                    detailCode = state.name
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SightBridge") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // TalkBack Live Region Status Banner
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            ) {
                Text(
                    text = "Status: $statusText | Camera: $cameraState",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // 1. Camera Source Selector
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("1. Select Camera Input Source", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(Modifier.selectableGroup()) {
                        listOf(
                            Triple("USB Webcam", CameraSourceType.USB_CAMERA, usbSource),
                            Triple("Meta Glasses", CameraSourceType.META_GLASSES, metaAdapter),
                            Triple("Mock Generator", CameraSourceType.MOCK_SIMULATED, simulatedSource),
                            Triple("Phone Camera", CameraSourceType.PHONE_CAMERA, phoneSource)
                        ).forEach { (label, type, source) ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(
                                        selected = selectedSourceType == type,
                                        enabled = !pipelineStarting && !pipeline.mediaRunning && !pipeline.aiRunning,
                                        role = Role.RadioButton,
                                        onClick = { switchCameraSource(type, source) }
                                    ).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedSourceType == type, onClick = null,
                                    enabled = !pipelineStarting && !pipeline.mediaRunning && !pipeline.aiRunning)
                                Text(label, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    Text(usbDescription, Modifier.semantics { liveRegion = LiveRegionMode.Polite })

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Source State: $cameraState",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    if (selectedSourceType == CameraSourceType.META_GLASSES) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStartMetaRegistration) { Text("Register Meta App") }
                            OutlinedButton(onClick = onStartMetaUnregistration) { Text("Unregister") }
                        }
                    }
                }
            }

            // 2. vOICe HTTP Media Streamer Settings
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("2. The vOICe HTTP Stream Binding", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (bindLocalhostOnly) "Localhost Only (127.0.0.1)" else "Publish on Phone IP (0.0.0.0)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (bindLocalhostOnly) "Private to local phone apps (The vOICe)" else "Accessible by local Wi-Fi devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Keep stream private to this phone" },
                            checked = bindLocalhostOnly,
                            onCheckedChange = { checked ->
                                bindLocalhostOnly = checked
                            },
                            enabled = !pipelineStarting && !pipeline.mediaRunning
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Localhost Endpoint: $localStreamUrl",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!bindLocalhostOnly && phoneIp != null) {
                                Text(
                                    text = "Wi-Fi LAN Endpoint: http://$phoneIp:8080/live.mjpeg?token=$lanAccessToken",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(localStreamUrl))
                            statusText = "Streaming URL copied. Paste it into The vOICe for Android."
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Copy Streaming URL") }
                }
            }

            // 3. Independent media and AI controls
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("3. Media Server", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                controller.startMedia(VoiceStreamConfig(bindLocalhostOnly = bindLocalhostOnly,
                                    accessToken = if (bindLocalhostOnly) null else lanAccessToken))
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !pipelineStarting && !pipeline.mediaRunning
                    ) { Text("Media Server Start") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { controller.stopMedia() } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !pipelineStarting && pipeline.mediaRunning
                    ) { Text("Media Server Stop") }
                    Text("Media server: ${if (pipeline.mediaRunning) "Running" else "Stopped"}", Modifier.padding(top = 8.dp))
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("4. AI Object Detection", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text("Recognizes objects on this device and speaks their left, center, or right position. Does not measure distance or determine a safe route.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    Button(
                        onClick = { scope.launch { controller.startAi() } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !pipelineStarting && !pipeline.aiRunning
                    ) { Text("Start AI Processing") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { controller.stopAi() } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !pipelineStarting && pipeline.aiRunning
                    ) { Text("Stop AI Processing") }
                    Text("AI: ${if (pipeline.aiRunning) "Running" else "Stopped"}", Modifier.padding(top = 8.dp))
                    Text(aiStatus.description, Modifier.padding(top = 8.dp))
                    Text("Frames analyzed: ${aiStatus.processedFrames} · Inference: ${aiStatus.inferenceMs} ms", style = MaterialTheme.typography.bodySmall)
                    if (pipeline.aiRunning && !aiStatus.speechReady) {
                        Text("Speech unavailable. Enable an English text-to-speech voice in Android settings. Results remain available above.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    aiStatus.error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                }
            }

            // 5. System Health Watchdog Overview
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("5. System Health Watchdog", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (systemHealth.isEmpty()) {
                        Text("No components registered", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            systemHealth.values.forEach { health ->
                                Text(
                                    text = "• ${health.component}: ${health.status} (${health.detailCode ?: ""})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (health.status == HealthLevel.HEALTHY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
