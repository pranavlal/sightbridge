package com.sightbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private lateinit var metaAdapter: MetaSdkAdapter
    private lateinit var simulatedSource: SimulatedCameraSource
    private lateinit var phoneSource: CameraXPhoneSource
    private val voiceStreamAdapter = HttpMjpegVoiceStreamAdapter()
    private val healthWatchdog = HealthWatchdog()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        metaAdapter = MetaSdkAdapter(this)
        simulatedSource = SimulatedCameraSource()
        phoneSource = CameraXPhoneSource(this)

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
                        voiceAdapter = voiceStreamAdapter,
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
        runBlocking {
            runCatching {
                metaAdapter.release()
                simulatedSource.release()
                phoneSource.release()
                voiceStreamAdapter.release()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightBridgeDashboard(
    metaAdapter: MetaSdkAdapter,
    simulatedSource: SimulatedCameraSource,
    phoneSource: CameraXPhoneSource,
    voiceAdapter: HttpMjpegVoiceStreamAdapter,
    healthWatchdog: HealthWatchdog,
    onStartMetaRegistration: () -> Unit,
    onStartMetaUnregistration: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedSourceType by remember { mutableStateOf(CameraSourceType.MOCK_SIMULATED) }
    var activeCameraSource by remember { mutableStateOf<CameraSource>(simulatedSource) }
    var cameraState by remember { mutableStateOf(CameraSourceState.UNINITIALISED) }
    var voiceState by remember { mutableStateOf(VoiceStreamState.STOPPED) }
    var bindLocalhostOnly by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("System ready") }
    var droppedFrames by remember { mutableLongStateOf(0L) }

    val phoneIp = remember { MjpegHttpServer.getPhoneIpAddress(context) }
    val systemHealth by healthWatchdog.systemHealth.collectAsState()

    // Start active health watchdog monitoring
    LaunchedEffect(Unit) {
        healthWatchdog.startWatchdog(scope)
    }

    // Observe active camera source state on Dispatchers.Default
    LaunchedEffect(activeCameraSource) {
        scope.launch(Dispatchers.Default) {
            activeCameraSource.state.collect { state ->
                cameraState = state
                healthWatchdog.updateHealth(
                    component = "CameraSource",
                    status = if (state == CameraSourceState.STREAMING) HealthLevel.HEALTHY else HealthLevel.DEGRADED,
                    detailCode = state.name
                )
            }
        }
        scope.launch(Dispatchers.Default) {
            activeCameraSource.frames.collect { frame ->
                voiceAdapter.submitFrame(frame)
                droppedFrames = voiceAdapter.droppedFramesCount
            }
        }
    }

    // Observe vOICe stream state
    LaunchedEffect(voiceAdapter) {
        scope.launch(Dispatchers.Default) {
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
                title = { Text("SightBridge - Blind Navigation System") },
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
                    Text("1. Select Camera Input Source", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedSourceType == CameraSourceType.META_GLASSES,
                            onClick = {
                                selectedSourceType = CameraSourceType.META_GLASSES
                                activeCameraSource = metaAdapter
                            },
                            label = { Text("Meta Glasses") }
                        )
                        FilterChip(
                            selected = selectedSourceType == CameraSourceType.MOCK_SIMULATED,
                            onClick = {
                                selectedSourceType = CameraSourceType.MOCK_SIMULATED
                                activeCameraSource = simulatedSource
                            },
                            label = { Text("Mock Generator") }
                        )
                        FilterChip(
                            selected = selectedSourceType == CameraSourceType.PHONE_CAMERA,
                            onClick = {
                                selectedSourceType = CameraSourceType.PHONE_CAMERA
                                activeCameraSource = phoneSource
                            },
                            label = { Text("Phone Camera") }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Source State: $cameraState",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    if (selectedSourceType == CameraSourceType.META_GLASSES) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStartMetaRegistration) { Text("Register Meta App") }
                            OutlinedButton(onClick = onStartMetaUnregistration) { Text("Unregister") }
                        }
                    }
                }
            }

            // 2. vOICe HTTP Media Streamer Settings
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("2. The vOICe HTTP Stream Binding", style = MaterialTheme.typography.titleMedium)
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
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = bindLocalhostOnly,
                            onCheckedChange = { checked ->
                                bindLocalhostOnly = checked
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Localhost Endpoint: http://127.0.0.1:8080/live.mjpeg",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!bindLocalhostOnly && phoneIp != null) {
                                Text(
                                    text = "Wi-Fi LAN Endpoint: http://$phoneIp:8080/live.mjpeg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // 3. Navigation & Stream Controls
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("3. Navigation Pipeline Controller", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                statusText = "Initialising source..."
                                activeCameraSource.initialise()
                                activeCameraSource.connect()

                                voiceAdapter.initialise(VoiceStreamConfig(bindLocalhostOnly = bindLocalhostOnly))
                                voiceAdapter.start()

                                statusText = "Starting camera stream..."
                                activeCameraSource.startStreaming()
                                statusText = "Streaming active!"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = cameraState != CameraSourceState.STREAMING
                    ) {
                        Text(if (cameraState == CameraSourceState.STREAMING) "Streaming Active" else "Start Navigation Pipeline")
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Dropped Frames: $droppedFrames | Status: $statusText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // 4. System Health Watchdog Overview
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("4. System Health Watchdog", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (systemHealth.isEmpty()) {
                        Text("No components registered", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.heightIn(max = 120.dp)
                        ) {
                            items(systemHealth.values.toList()) { health ->
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
