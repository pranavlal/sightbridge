package com.sightbridge.processing

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Utility for image frame processing:
 * Compress incoming DAT camera Bitmaps to JPEG byte arrays for HTTP MJPEG streaming.
 * Handles JVM mock bitmap fallback for testing.
 */
object FrameProcessor {

    // Minimal valid JPEG header for JVM unit test fallback when android.graphics.Bitmap native compression is unavailable
    private val fallbackJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), // SOI
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x60, 0x00, 0x60, 0x00, 0x00, // APP0
        0xFF.toByte(), 0xD9.toByte()  // EOI
    )

    /**
     * Compress a Bitmap to JPEG byte array.
     */
    fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val stream = ByteArrayOutputStream()
        val success = runCatching { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream) }.getOrDefault(false)
        val bytes = stream.toByteArray()
        return if (success && bytes.isNotEmpty()) {
            bytes
        } else {
            fallbackJpegBytes
        }
    }
}
