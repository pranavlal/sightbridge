package com.sightbridge.camera.usb

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import com.sightbridge.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.net.Socket

/** Opt in with -e usbCamera true; requires a physically attached UVC webcam. */
class UsbPipelineDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + 40_000
        var retried = false
        while (System.currentTimeMillis() < deadline) {
            clickDialog(automation.rootInActiveWindow)
            Thread.sleep(300)
            val pipeline = compose.activity.pipelineController.status.value
            if (pipeline.needsPermissions && !pipeline.busy && !retried) {
                runCatching { compose.onNodeWithText(button).performScrollTo().performClick() }.onSuccess { retried = true }
            }
            if (ready()) return
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
        compose.waitUntil(20_000) { controller.ai.status.value.spokenUtterances >= 1 }
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
}
