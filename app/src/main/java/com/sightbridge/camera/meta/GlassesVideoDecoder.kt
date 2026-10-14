package com.sightbridge.camera.meta

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import com.meta.wearable.dat.camera.types.VideoFrame

/** Decodes DAT's HEVC frames to owned bitmaps for the existing camera pipeline. */
internal class GlassesVideoDecoder : AutoCloseable {
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private val outputInfo = MediaCodec.BufferInfo()

    fun decode(frame: VideoFrame, onBitmap: (Bitmap) -> Unit) {
        if (frame.width <= 0 || frame.height <= 0) return
        if (codec == null || width != frame.width || height != frame.height) {
            close()
            width = frame.width
            height = frame.height
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                configure(format, null, null, 0)
                start()
            }
        }
        val decoder = checkNotNull(codec)
        drain(decoder, onBitmap)
        val data = frame.buffer.duplicate().apply { rewind() }
        val inputIndex = decoder.dequeueInputBuffer(100_000)
        check(inputIndex >= 0) { "Meta video decoder did not accept a frame" }
        val input = checkNotNull(decoder.getInputBuffer(inputIndex))
        input.clear()
        require(data.remaining() <= input.remaining()) { "Meta video frame exceeds decoder input" }
        val size = data.remaining()
        input.put(data)
        decoder.queueInputBuffer(inputIndex, 0, size, frame.presentationTimeUs,
            if (frame.isCodecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        drain(decoder, onBitmap)
    }

    private fun drain(decoder: MediaCodec, onBitmap: (Bitmap) -> Unit) {
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(outputInfo, 0)
            if (outputIndex < 0) break
            try {
                if (outputInfo.size > 0 &&
                    outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val image = decoder.getOutputImage(outputIndex)
                        ?: throw IllegalStateException("Meta video decoder did not provide an image")
                    image.use { onBitmap(it.toBitmap()) }
                }
            } finally {
                decoder.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    override fun close() {
        val decoder = codec
        codec = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        width = 0
        height = 0
    }
}

private fun Image.toBitmap(): Bitmap {
    val crop = cropRect
    val planes = planes
    require(planes.size == 3) { "Meta decoder returned an unsupported image format" }
    val pixels = IntArray(crop.width() * crop.height())
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val y = yPlane.buffer
    val u = uPlane.buffer
    val v = vPlane.buffer
    var out = 0
    for (row in crop.top until crop.bottom) {
        for (column in crop.left until crop.right) {
            val yy = y.get(row * yPlane.rowStride + column * yPlane.pixelStride).toInt() and 0xff
            val uvRow = row / 2
            val uvColumn = column / 2
            val uu = (u.get(uvRow * uPlane.rowStride + uvColumn * uPlane.pixelStride).toInt() and 0xff) - 128
            val vv = (v.get(uvRow * vPlane.rowStride + uvColumn * vPlane.pixelStride).toInt() and 0xff) - 128
            val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
            val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
            val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
            pixels[out++] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
}
