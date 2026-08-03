package com.sightbridge.core.health

import org.junit.Assert.*
import org.junit.Test

class HealthWatchdogTest {

    @Test
    fun testUpdateHealthAndGet() {
        val watchdog = HealthWatchdog()
        watchdog.updateHealth("CameraSource", HealthLevel.HEALTHY, "Connected")

        val health = watchdog.getHealth("CameraSource")
        assertNotNull(health)
        assertEquals(HealthLevel.HEALTHY, health?.status)
        assertEquals("Connected", health?.detailCode)
    }

    @Test
    fun testStalenessDetection() {
        val watchdog = HealthWatchdog(frameTimeoutMs = 1000)
        val startNanos = System.nanoTime()

        watchdog.updateHealth("CameraSource", HealthLevel.HEALTHY, "Streaming")
        
        // Simulate checking staleness 2 seconds later
        val futureNanos = startNanos + 2_000_000_000L
        watchdog.checkStaleness(currentMonotonicNanos = futureNanos)

        val health = watchdog.getHealth("CameraSource")
        assertEquals(HealthLevel.DEGRADED, health?.status)
        assertTrue(health?.detailCode?.contains("Stale") == true)
    }
}
