package com.sightbridge.accessibility

import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.sightbridge.app.MainActivity
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.output.voicestream.VoiceStreamConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DashboardAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mergedPackageExcludesUnusedSensitivePermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_PERMISSIONS.toLong()))
        val permissions = info.requestedPermissions.orEmpty().toSet()
        listOf("android.permission.RECORD_AUDIO", "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.BLUETOOTH").forEach {
            assertFalse("Unexpected SDK-contributed permission: $it", it in permissions)
        }
        assertEquals(36, info.applicationInfo!!.minSdkVersion)
        assertEquals(36, info.applicationInfo!!.targetSdkVersion)
    }

    @Before fun ready() {
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        listOf("android.permission.CAMERA", "android.permission.POST_NOTIFICATIONS").forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("pm grant com.sightbridge $permission")).use { it.readBytes() }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("USB Webcam").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            compose.activity.pipelineController.stopMedia()
            compose.activity.pipelineController.stopAi()
            compose.activity.pipelineService.selectSource(CameraSourceType.USB_CAMERA)
        }
    }

    @Test fun initialScreenOnlyExposesCameraChoicesAndProcessingControls() {
        compose.onNode(hasText("USB Webcam") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected().assertHasClickAction()
        compose.onNodeWithText("Phone Camera").performClick().assertIsSelected()
        compose.onNodeWithText("Media Server Start").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Media Server Stop").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Start AI Processing").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Stop AI Processing").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Copy Streaming URL").assertDoesNotExist()
        compose.onNodeWithText("http://127.0.0.1:8080/live.mjpeg", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Keep stream private to this phone").assertDoesNotExist()
        compose.onNodeWithText("System Health Watchdog", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Frames analyzed:", substring = true).assertDoesNotExist()
    }

    @Test fun urlAndCopyAppearOnlyWhileMediaRuns() {
        val controller = compose.activity.pipelineController
        try {
            compose.onNodeWithText("Mock Generator").performClick()
            compose.waitUntil(10_000) {
                compose.activity.pipelineService.sourceType.value == CameraSourceType.MOCK_SIMULATED && !controller.status.value.busy
            }
            compose.onNodeWithText("Media Server Start").performScrollTo().assertIsEnabled().performClick()
            try {
                compose.waitUntil(10_000) { controller.status.value.mediaRunning && !controller.status.value.busy }
            } catch (e: Exception) {
                throw AssertionError("Media start failed: ${controller.status.value}", e)
            }
            compose.onNodeWithText("http://127.0.0.1:8080/live.mjpeg", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Copy Streaming URL").performScrollTo().performClick()
            compose.runOnIdle {
                val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals("http://127.0.0.1:8080/live.mjpeg", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            }
            compose.onNodeWithText("Start AI Processing").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Media Server Stop").performScrollTo().performClick()
            compose.waitUntil(10_000) { !controller.status.value.mediaRunning && !controller.status.value.busy }
            compose.onNodeWithText("Copy Streaming URL").assertDoesNotExist()
            compose.onNodeWithText("http://127.0.0.1:8080/live.mjpeg", substring = true).assertDoesNotExist()
        } finally { runBlocking { controller.stopMedia() } }
    }

    @Test fun failedMediaStartDoesNotExposeUrl() {
        val controller = compose.activity.pipelineController
        // A deliberately invalid port verifies the failure path without USB hardware.
        runBlocking {
            compose.activity.pipelineService.selectSource(CameraSourceType.MOCK_SIMULATED)
            controller.startMedia(VoiceStreamConfig(port = -1))
        }
        assertFalse(controller.status.value.mediaRunning)
        compose.onNodeWithText("Copy Streaming URL").assertDoesNotExist()
    }

    @Test fun sectionsHaveHeadingSemantics() {
        listOf("Camera", "Media Server", "AI").forEach {
            compose.onNodeWithText(it).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }
}
