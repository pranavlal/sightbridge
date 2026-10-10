package com.sightbridge.accessibility

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import com.sightbridge.app.MainActivity
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DashboardAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cameraChoicesExposeSingleSelectionAndScrollableControls() {
        compose.onNode(hasText("USB Webcam") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected().assertHasClickAction()
        compose.onNode(hasText("Phone Camera") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Keep stream private to this phone")
            .performScrollTo().assertIsDisplayed().assertIsOn()
        compose.onNodeWithText("Media Server Start").performScrollTo().assertIsDisplayed().assertHasClickAction()
    }

    @Test fun copyUrlAndIndependentControlsAreAccessible() {
        compose.onNodeWithText("Copy Streaming URL").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("http://127.0.0.1:8080/live.mjpeg", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
        compose.onNodeWithContentDescription("Keep stream private to this phone").performScrollTo().performClick()
        compose.onNodeWithText("Copy Streaming URL").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            org.junit.Assert.assertTrue(clipboard.primaryClip?.getItemAt(0)?.text.toString().startsWith("http://127.0.0.1:8080/live.mjpeg?token="))
        }
        compose.onNodeWithText("Media Server Start").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Media Server Stop").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Start AI Processing").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Stop AI Processing").performScrollTo().assertIsNotEnabled()
    }

    @Test fun sectionsHaveHeadingSemantics() {
        compose.onNodeWithText("1. Select Camera Input Source")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("5. System Health Watchdog").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
