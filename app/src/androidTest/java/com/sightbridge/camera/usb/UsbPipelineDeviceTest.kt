package com.sightbridge.camera.usb

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import com.sightbridge.app.MainActivity
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.net.Socket

/** Opt in with -e usbCamera true; requires a physically attached UVC webcam. */
class UsbPipelineDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun waitForDashboard() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Media Server Start").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Explicit deployment opt-in: leave the user's USB stream and AI running. */
    @Test fun leavePipelineRunningAfterDeployment() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("leaveRunning") == "true")
        startWithDialogs("Media Server Start") { compose.activity.pipelineController.status.value.mediaRunning }
        assertJpegStream()
        startWithDialogs("Start AI Processing") { compose.activity.pipelineController.status.value.aiRunning }
        compose.waitUntil(30_000) { compose.activity.pipelineController.ai.status.value.processedFrames >= 2 }
        val ai = compose.activity.pipelineController.ai.status.value
        assertTrue(ai.localizationReady)
        assertTrue("AI fallback must initialize: $ai", ai.localizationWarning.isNullOrBlank())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\nDeployment AI status: $ai\n")
        })
    }

    private fun clickDialog(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString().orEmpty()
        if (text in listOf("While using the app", "Allow", "OK", "Only this time") && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) if (clickDialog(node.getChild(i))) return true
        return false
    }

    private fun startWithDialogs(button: String, ready: () -> Boolean) {
        compose.onNodeWithText(button).performScrollTo().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline = System.currentTimeMillis() + 40_000
        var retried = false
        while (System.currentTimeMillis() < deadline) {
            clickDialog(automation.rootInActiveWindow)
            Thread.sleep(300)
            val pipeline = compose.activity.pipelineController.status.value
            if (pipeline.needsPermissions && !pipeline.busy && !retried) {
                runCatching { compose.onNodeWithText(button).performScrollTo().performClick() }.onSuccess { retried = true }
            }
            // Running is published before the command releases its busy state.
            // Wait for enabled controls before issuing the next UI action.
            if (ready() && !pipeline.busy) return
        }
        fail("Start failed: ${compose.activity.pipelineController.status.value}; ${compose.activity.pipelineController.ai.status.value}")
    }

    private fun assertJpegStream() {
        Socket("127.0.0.1", 8080).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().write("GET /live.mjpeg HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            var previous = -1
            var found = false
            for (i in 0 until 1_000_000) {
                val value = input.read()
                if (value < 0) break
                if (previous == 0xff && value == 0xd8) { found = true; break }
                previous = value
            }
            assertTrue("USB pipeline did not deliver a JPEG frame", found)
        }
    }

    @Test fun webcamProducesJpegFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usbCamera") == "true")
        startWithDialogs("Media Server Start") { compose.activity.pipelineController.status.value.mediaRunning }
        assertJpegStream()
        compose.onNodeWithText("Media Server Stop").performScrollTo().performClick()
    }

    @Test fun aiAndMediaRunIndependentlyOnTheWebcam() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usbCamera") == "true")
        val controller = compose.activity.pipelineController
        startWithDialogs("Start AI Processing") { controller.status.value.aiRunning }
        compose.waitUntil(30_000) { controller.ai.status.value.processedFrames >= 2 }
        assertFalse("AI-only start must not start the HTTP server", controller.status.value.mediaRunning)
        assertTrue("English speech engine should initialize on the handset", controller.ai.status.value.speechReady)
        compose.waitUntil(20_000) {
            controller.ai.status.value.let { it.spokenUtterances + it.accessibilityAnnouncements >= 1 }
        }
        assertTrue("Position-only detector must initialize on the handset", controller.ai.status.value.localizationReady)
        startWithDialogs("Media Server Start") { controller.status.value.mediaRunning }
        assertJpegStream()
        compose.onNodeWithText("Media Server Stop").performScrollTo().performClick()
        compose.waitUntil(10_000) { !controller.status.value.mediaRunning && !controller.status.value.busy }
        val before = controller.ai.status.value.processedFrames
        compose.waitUntil(15_000) { controller.ai.status.value.processedFrames > before }
        assertTrue(controller.status.value.aiRunning)
        startWithDialogs("Media Server Start") { controller.status.value.mediaRunning }
        compose.onNodeWithText("Stop AI Processing").performScrollTo().performClick()
        compose.waitUntil(10_000) { !controller.status.value.aiRunning && !controller.status.value.busy }
        assertTrue(controller.status.value.mediaRunning)
        assertJpegStream()
        compose.onNodeWithText("Media Server Stop").performScrollTo().performClick()
    }
    @Test fun staticFramesSkipInferenceAndChangesAndRefreshRunIt() {
        val ai = com.sightbridge.ai.OnDeviceAiProcessor(compose.activity)
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.DKGRAY)
        kotlinx.coroutines.runBlocking {
            try {
                ai.start().getOrThrow()
                fun offer() {
                    ai.offer(com.sightbridge.core.model.CameraFrame(1,
                        com.sightbridge.core.model.CameraSourceType.MOCK_SIMULATED,
                        System.nanoTime(), System.nanoTime(), 64, 48, 0,
                        com.sightbridge.core.model.PixelFormat.RGBA_8888, bitmap))
                }
                fun waitForFrames(count: Long, timeoutMs: Long = 12_000) {
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (ai.status.value.processedFrames < count && System.currentTimeMillis() < deadline) {
                        offer()
                        Thread.sleep(250)
                    }
                    assertTrue("AI did not analyze $count frames: ${ai.status.value}", ai.status.value.processedFrames >= count)
                }
                waitForFrames(1)
                repeat(6) { offer(); Thread.sleep(250) }
                assertEquals(1L, ai.status.value.processedFrames)
                assertTrue(ai.status.value.unchangedFramesSkipped > 0)
                assertFalse("The thumbnail must not recycle its source bitmap", bitmap.isRecycled)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                waitForFrames(2)
                assertEquals("Scene changed", ai.status.value.inferenceTrigger)
                waitForFrames(3)
                assertEquals("Periodic refresh", ai.status.value.inferenceTrigger)
            } finally { ai.release(); bitmap.recycle() }
        }
    }

}
