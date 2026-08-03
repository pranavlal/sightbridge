package com.sightbridge.processing

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Utility for image frame processing:
 * Compress incoming DAT camera Bitmaps to JPEG byte arrays for HTTP MJPEG streaming.
 */
object FrameProcessor {

    /**
     * Compress a Bitmap to JPEG byte array.
     */
    fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
