package com.sightbridge.frame.buffer

import android.graphics.Bitmap
import com.sightbridge.core.model.CameraFrame
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.core.model.PixelFormat
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class LatestFrameBufferTest {

    @Test
    fun testFrameDroppingPolicy() {
        val buffer = LatestFrameBuffer(maxStaleAgeMs = 1000)

        val frame1 = createDummyFrame(1L, System.nanoTime())
        val frame2 = createDummyFrame(2L, System.nanoTime())

        buffer.offer(frame1)
        buffer.offer(frame2) // Replaces frame1 (frame1 dropped)

        assertEquals(1, buffer.droppedFramesCount)
        assertEquals(2, buffer.totalProducedCount)

        val polled = buffer.poll()
        assertNotNull(polled)
        assertEquals(2L, polled?.frameId)
        assertEquals(1, buffer.totalConsumedCount)
    }

    @Test
    fun testStaleFrameRejection() {
        val buffer = LatestFrameBuffer(maxStaleAgeMs = 100) // 100ms max age
        val oldAcqNanos = System.nanoTime() - 600_000_000L // 600ms old

        val staleFrame = createDummyFrame(10L, oldAcqNanos)
        buffer.offer(staleFrame)

        val polled = buffer.poll()
        assertNull("Stale frame should be rejected and return null", polled)
    }

    private fun createDummyFrame(id: Long, acqNanos: Long): CameraFrame {
        val bitmap = Mockito.mock(Bitmap::class.java)
        return CameraFrame(
            frameId = id,
            source = CameraSourceType.MOCK_SIMULATED,
            acquisitionTimestampNanos = acqNanos,
            receivedTimestampNanos = System.nanoTime(),
            width = 10,
            height = 10,
            rotationDegrees = 0,
            pixelFormat = PixelFormat.RGBA_8888,
            bitmap = bitmap
        )
    }
}
