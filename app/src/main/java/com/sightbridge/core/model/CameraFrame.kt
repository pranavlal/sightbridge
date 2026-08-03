package com.sightbridge.core.model

import android.graphics.Bitmap

data class FrameMetadata(
    val estimatedFrameAgeMs: Long = 0,
    val isSimulated: Boolean = false,
    val batteryLevel: Int? = null,
    val signalQuality: Float? = null,
    val customInfo: Map<String, String> = emptyMap()
)

data class CameraFrame(
    val frameId: Long,
    val source: CameraSourceType,
    val acquisitionTimestampNanos: Long,
    val receivedTimestampNanos: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val pixelFormat: PixelFormat,
    val bitmap: Bitmap,
    val metadata: FrameMetadata = FrameMetadata()
) {
    /**
     * Calculates the age of the frame relative to a given monotonic time (in nanoseconds).
     */
    fun calculateAgeMs(currentMonotonicNanos: Long = System.nanoTime()): Long {
        return (currentMonotonicNanos - acquisitionTimestampNanos) / 1_000_000
    }
}
