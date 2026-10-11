package com.sightbridge.accessibility

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.sightbridge.app.MainActivity
import com.sightbridge.app.PipelineService
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.output.voicestream.VoiceStreamConfig
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.Socket

class BackgroundPipelineTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun ready() {
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        listOf("android.permission.CAMERA", "android.permission.POST_NOTIFICATIONS").forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("pm grant com.sightbridge $permission")).use { it.readBytes() }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("USB Webcam").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            compose.activity.pipelineController.stopMedia()
            compose.activity.pipelineController.stopAi()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(15_000) { while (!condition()) delay(100) }
    }

    private fun assertJpegStream() {
        Socket("127.0.0.1", 8080).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write("GET /mjpeg HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            var previous = -1
            repeat(1_000_000) {
                val value = input.read()
                if (previous == 0xff && value == 0xd8) return
                if (value < 0) fail("Stream ended before a JPEG frame")
                previous = value
            }
            fail("No JPEG frame received")
        }
    }

    @Test fun phoneCameraKeepsProducingFramesWithoutTheDashboard() = runBlocking {
        val service = compose.activity.pipelineService
        val controller = service.controller
        try {
            service.selectSource(CameraSourceType.PHONE_CAMERA)
            controller.startMedia(VoiceStreamConfig())
            assertTrue(controller.status.value.toString(), controller.status.value.mediaRunning)
            assertJpegStream()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Copy Streaming URL").fetchSemanticsNodes().isNotEmpty() }
            assertSame("Recreation must retain the active pipeline", controller, compose.activity.pipelineController)
            assertEquals(CameraSourceType.PHONE_CAMERA, compose.activity.pipelineService.sourceType.value)
            // Destroy the UI completely, leaving only the started foreground service.
            compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            assertEquals(Lifecycle.State.STARTED, service.lifecycle.currentState)
            val before = service.health.getHealth("CameraSource")?.lastSuccessNanos ?: 0
            awaitCondition { (service.health.getHealth("CameraSource")?.lastSuccessNanos ?: 0) > before }
            assertJpegStream()
            assertTrue(controller.status.value.mediaRunning)
            controller.stopMedia()
            assertFalse(controller.status.value.mediaRunning)
        } finally {
            // The service may already be destroyed after stopping the last consumer.
            if (controller.status.value.mediaRunning) controller.stopMedia()
        }
    }

    @Test fun aiAndMediaStayIndependentAndNotificationCanStopBoth() = runBlocking {
        val service = compose.activity.pipelineService
        val controller = service.controller
        try {
            service.selectSource(CameraSourceType.MOCK_SIMULATED)
            controller.startAi()
            assertTrue(controller.status.value.toString(), controller.status.value.aiRunning)
            assertFalse(controller.status.value.mediaRunning)
            awaitCondition { controller.ai.status.value.processedFrames > 0 }
            controller.startMedia(VoiceStreamConfig())
            assertTrue(controller.status.value.mediaRunning)
            controller.stopMedia()
            val before = controller.ai.status.value.processedFrames
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            awaitCondition { controller.ai.status.value.processedFrames > before }
            assertTrue(controller.status.value.aiRunning)
            assertFalse(controller.status.value.mediaRunning)
            service.startService(Intent(service, PipelineService::class.java).setAction("com.sightbridge.STOP_PIPELINE"))
            awaitCondition { !controller.status.value.aiRunning && !controller.status.value.mediaRunning && !controller.status.value.busy }
        } finally {
            controller.stopMedia()
            controller.stopAi()
            // Compose's teardown needs an active UI after the background assertion.
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
}
