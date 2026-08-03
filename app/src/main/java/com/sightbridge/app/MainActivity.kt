package com.sightbridge.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.RegistrationState
import com.sightbridge.processing.FrameProcessor
import com.sightbridge.server.MjpegHttpServer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val mjpegServer = MjpegHttpServer(port = 8080, bindToLocalhostOnly = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DatStreamerScreen(
                        mjpegServer = mjpegServer,
                        onStartRegistration = { Wearables.startRegistration(this) },
                        onStartUnregistration = { Wearables.startUnregistration(this) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegServer.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatStreamerScreen(
    mjpegServer: MjpegHttpServer,
    onStartRegistration: () -> Unit,
    onStartUnregistration: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regState by remember { mutableStateOf<RegistrationState?>(null) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var statusText by remember { mutableStateOf("Ready to start DAT camera stream") }
    var isStreaming by remember { mutableStateOf(false) }

    var bindLocalhostOnly by remember { mutableStateOf(true) }
    var isServerRunning by remember { mutableStateOf(false) }
    val phoneIp = remember { MjpegHttpServer.getPhoneIpAddress(context) }

    LaunchedEffect(Unit) {
        scope.launch {
            Wearables.registrationState.collect { state ->
                regState = state
            }
        }
        scope.launch {
            Wearables.devices.collect { deviceSet ->
                devices = deviceSet.toList()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SightBridge - DAT HTTP Streamer") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Wearables Registration
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1. Meta Wearables Connection",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Registration: ${regState ?: "Disconnected"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStartRegistration) {
                            Text("Register App")
                        }
                        OutlinedButton(onClick = onStartUnregistration) {
                            Text("Unregister")
                        }
                    }
                }
            }

            // Section 2: HTTP MJPEG Server Binding Settings
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "2. HTTP MJPEG Server Settings",
                        style = MaterialTheme.typography.titleMedium
                    )
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
                                text = if (bindLocalhostOnly)
                                    "Restricted to apps on this phone (e.g. The vOICe)"
                                else
                                    "Accessible by all devices on local Wi-Fi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = bindLocalhostOnly,
                            onCheckedChange = { checked ->
                                bindLocalhostOnly = checked
                                mjpegServer.bindToLocalhostOnly = checked
                                if (isServerRunning) {
                                    mjpegServer.stop()
                                    mjpegServer.start()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Stream URLs Display
                    Text(
                        text = "Active Stream Endpoint:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Localhost: http://127.0.0.1:8080/live.mjpeg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!bindLocalhostOnly && phoneIp != null) {
                                Text(
                                    text = "Wi-Fi LAN:   http://$phoneIp:8080/live.mjpeg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (!isServerRunning) {
                                mjpegServer.start()
                                isServerRunning = true
                            } else {
                                mjpegServer.stop()
                                isServerRunning = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isServerRunning) "Stop HTTP Server" else "Start HTTP MJPEG Server")
                    }
                }
            }

            // Section 3: Camera Stream Control
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "3. Glasses Stream Controller",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                statusText = "Creating session with AutoDeviceSelector..."
                                val sessionResult = Wearables.createSession(AutoDeviceSelector())
                                sessionResult.fold(
                                    onSuccess = { session ->
                                        session.start()
                                        statusText = "Session started. Adding camera stream..."
                                        val config = StreamConfiguration(
                                            videoQuality = VideoQuality.MEDIUM,
                                            frameRate = 24
                                        )
                                        session.addStream(config).fold(
                                            onSuccess = { stream ->
                                                isStreaming = true
                                                statusText = "Streaming active at 24 FPS!"

                                                // Automatically start HTTP server if not running
                                                if (!isServerRunning) {
                                                    mjpegServer.start()
                                                    isServerRunning = true
                                                }

                                                // Collect frames and push to HTTP MJPEG Server
                                                scope.launch {
                                                    stream.videoStream.collect { videoFrame ->
                                                        val bitmap: Bitmap? = videoFrame.bitmap
                                                        if (bitmap != null && isServerRunning) {
                                                            val jpegBytes = FrameProcessor.compressBitmapToJpeg(bitmap)
                                                            mjpegServer.pushJpegFrame(jpegBytes)
                                                        }
                                                    }
                                                }

                                                stream.start()
                                            },
                                            onFailure = { error, _ ->
                                                statusText = "Stream Error: ${error.description}"
                                            }
                                        )
                                    },
                                    onFailure = { error ->
                                        statusText = "Session Error: ${error.description}"
                                    }
                                )
                            }
                        },
                        enabled = !isStreaming,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isStreaming) "Streaming to HTTP..." else "Start Glasses Stream")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
