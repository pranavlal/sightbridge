package com.sightbridge.accessibility

import android.app.UiAutomation
import androidx.test.runner.AndroidJUnitRunner

/** Keep spoken accessibility services active whenever a device test uses UiAutomation. */
class AccessibilityPreservingTestRunner : AndroidJUnitRunner() {
    override fun getUiAutomation(): UiAutomation =
        super.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    override fun getUiAutomation(flags: Int): UiAutomation =
        super.getUiAutomation(flags or UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
}
