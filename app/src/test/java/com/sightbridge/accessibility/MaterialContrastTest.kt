package com.sightbridge.accessibility

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialContrastTest {
    @Test fun dashboardTextPairsMeetWcagAa() {
        val colors = lightColorScheme()
        val pairs = listOf(
            colors.onSurface to colors.surface,
            colors.onSurfaceVariant to colors.surface,
            colors.onSurfaceVariant to colors.surfaceVariant,
            colors.onPrimaryContainer to colors.primaryContainer,
            colors.onSecondaryContainer to colors.secondaryContainer,
            colors.onPrimary to colors.primary,
            colors.primary to colors.surface,
            colors.secondary to colors.surface,
            colors.tertiary to colors.surface,
            colors.error to colors.surface
        )
        pairs.forEach { (text, background) ->
            val first = text.luminance() + 0.05f
            val second = background.luminance() + 0.05f
            val ratio = maxOf(first, second) / minOf(first, second)
            assertTrue("Text contrast $ratio must meet 4.5:1", ratio >= 4.5f)
        }
    }
}
