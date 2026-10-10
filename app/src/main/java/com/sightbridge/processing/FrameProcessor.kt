package com.sightbridge.processing

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Utility for image frame processing:
 * Compress incoming DAT camera Bitmaps to JPEG byte arrays for HTTP MJPEG streaming.
 * Fails closed if Android cannot encode a real JPEG.
 */
object FrameProcessor {

    /**
     * Compress a Bitmap to JPEG byte array.
     */
    fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        require(quality in 1..100) { "JPEG quality must be between 1 and 100" }
        val stream = ByteArrayOutputStream()
        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        check(success && bytes.size > 4) { "Bitmap JPEG compression failed" }
        check(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xFF.toByte() && bytes.last() == 0xD9.toByte()) {
            "Bitmap compressor returned invalid JPEG markers"
        }
        return bytes
    }

    fun compressFrameToJpeg(frame: com.sightbridge.core.model.CameraFrame, quality: Int = 80): ByteArray {
        val rotation = ((frame.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return compressBitmapToJpeg(frame.bitmap, quality)
        val rotated = Bitmap.createBitmap(
            frame.bitmap,
            0,
            0,
            frame.bitmap.width,
            frame.bitmap.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true
        )
        return try {
            compressBitmapToJpeg(rotated, quality)
        } finally {
            if (rotated !== frame.bitmap) rotated.recycle()
        }
    }
}
