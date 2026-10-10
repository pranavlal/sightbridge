package com.sightbridge.output.voicestream

import com.sightbridge.core.health.HealthLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceStreamAdapterTest {

    @Test
    fun testAdapterLifecycleAndHealth() = runTest {
        val adapter = HttpMjpegVoiceStreamAdapter(jpegEncoder = { _, _ -> byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()) })
        val config = VoiceStreamConfig(port = 8888, bindLocalhostOnly = true)

        adapter.initialise(config)
        assertEquals(VoiceStreamState.STOPPED, adapter.state.value)

        adapter.start()
        assertEquals(VoiceStreamState.STREAMING, adapter.state.value)

        val health = adapter.healthCheck()
        assertEquals("VoiceStreamAdapter", health.component)
        assertEquals(HealthLevel.HEALTHY, health.status)

        adapter.stop()
        assertEquals(VoiceStreamState.STOPPED, adapter.state.value)
    }
}
