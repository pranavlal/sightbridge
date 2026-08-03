package com.sightbridge.integration

import android.graphics.Bitmap
import com.sightbridge.camera.mock.SimulatedCameraSource
import com.sightbridge.camera.api.StreamConfig
import com.sightbridge.core.health.HealthLevel
import com.sightbridge.core.health.HealthWatchdog
import com.sightbridge.core.model.CameraFrame
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.core.model.PixelFormat
import com.sightbridge.output.voicestream.HttpMjpegVoiceStreamAdapter
import com.sightbridge.output.voicestream.VoiceStreamConfig
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * End-to-End Automated Integration Test for SightBridge Camera-to-MJPEG HTTP Stream Pipeline.
 * Tests frame generation, latest-frame buffer decoupling, HTTP MJPEG stream transmission, and active health watchdog heartbeats.
 */
class EndToEndPipelineTest {

    @Test
    fun testEndToEndCameraToMjpegHttpPipeline() = runBlocking {
        val testPort = 8990
        val healthWatchdog = HealthWatchdog(frameTimeoutMs = 5000)
        val cameraSource = SimulatedCameraSource(targetWidth = 320, targetHeight = 240)
        val voiceAdapter = HttpMjpegVoiceStreamAdapter(healthWatchdog)

        // 1. Initialise & Connect Camera Source
        val initCamResult = cameraSource.initialise()
        assertTrue("Camera source initialization should succeed", initCamResult.isSuccess)

        val connectCamResult = cameraSource.connect()
        assertTrue("Camera source connection should succeed", connectCamResult.isSuccess)

        // 2. Initialise & Start Voice HTTP Streamer
        val initVoiceResult = voiceAdapter.initialise(VoiceStreamConfig(port = testPort, bindLocalhostOnly = true))
        assertTrue("Voice stream adapter initialization should succeed", initVoiceResult.isSuccess)

        val startVoiceResult = voiceAdapter.start()
        assertTrue("Voice stream adapter start should succeed", startVoiceResult.isSuccess)

        // 3. Connect Raw Socket HTTP Client to MJPEG Endpoint (Simulating The vOICe app)
        val socket = withContext(Dispatchers.IO) {
            Socket("127.0.0.1", testPort).apply {
                soTimeout = 3000
            }
        }
        assertTrue("Socket should be connected", socket.isConnected)

        val out = socket.getOutputStream()
        val getRequest = "GET /live.mjpeg HTTP/1.1\r\nHost: 127.0.0.1:$testPort\r\nConnection: close\r\n\r\n"
        withContext(Dispatchers.IO) {
            out.write(getRequest.toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        // 4. Submit test frames continuously while client is connected
        val frameIdCounter = AtomicLong(1)
        val dummyBitmap = Mockito.mock(Bitmap::class.java)
        val framePushJob = launch(Dispatchers.Default) {
            while (isActive) {
                val frameId = frameIdCounter.getAndIncrement()
                val acqNanos = System.nanoTime()
                val frame = CameraFrame(
                    frameId = frameId,
                    source = CameraSourceType.MOCK_SIMULATED,
                    acquisitionTimestampNanos = acqNanos,
                    receivedTimestampNanos = acqNanos,
                    width = 320,
                    height = 240,
                    rotationDegrees = 0,
                    pixelFormat = PixelFormat.RGBA_8888,
                    bitmap = dummyBitmap
                )
                healthWatchdog.recordHeartbeat("CameraSource", "Frame $frameId")
                voiceAdapter.submitFrame(frame)
                delay(33) // ~30 FPS
            }
        }

        // 5. Read stream response bytes and verify HTTP header + JPEG frame payload
        val inputStream: InputStream = socket.getInputStream()
        val buffer = ByteArray(4096)
        val bytesRead = withContext(Dispatchers.IO) { inputStream.read(buffer) }

        assertTrue("Should read MJPEG stream response bytes", bytesRead > 0)
        val payload = String(buffer, 0, bytesRead, Charsets.US_ASCII)
        assertTrue("Stream response should contain HTTP 200 OK header", payload.contains("200 OK"))
        assertTrue("Stream response should contain multipart/x-mixed-replace Content-Type", payload.contains("multipart/x-mixed-replace"))

        // Read frame payload
        val frameBytesRead = withContext(Dispatchers.IO) { inputStream.read(buffer) }
        assertTrue("Should read JPEG frame payload bytes", frameBytesRead > 0)
        val framePayload = String(buffer, 0, frameBytesRead, Charsets.US_ASCII)
        assertTrue("Payload should contain --frame header or image/jpeg",
            framePayload.contains("--frame") || framePayload.contains("image/jpeg") || payload.contains("--frame"))

        // 6. Verify Health Watchdog Status
        val camHealth = healthWatchdog.getHealth("CameraSource")
        assertNotNull(camHealth)
        assertEquals("CameraSource health should be HEALTHY", HealthLevel.HEALTHY, camHealth?.status)

        val voiceHealth = voiceAdapter.healthCheck()
        assertEquals("VoiceStreamAdapter health should be HEALTHY", HealthLevel.HEALTHY, voiceHealth.status)

        // 7. Teardown and Cleanup
        framePushJob.cancel()
        socket.close()

        cameraSource.disconnect()
        cameraSource.release()

        voiceAdapter.stop()
        voiceAdapter.release()
    }
}
