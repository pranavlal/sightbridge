package com.sightbridge.ai

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Bundled U2Netp saliency model; no class vocabulary or network calls. */
class ForegroundRegionDetector(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(context.assets.open("u2netp.onnx").use { it.readBytes() }, options)
    }
    private val inputName = session.inputNames.first()
    private val outputName = session.outputNames.first()

    fun detect(upright: Bitmap): List<ObjectBox> {
        val scaled = Bitmap.createScaledBitmap(upright, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        try { scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE) }
        finally { if (scaled !== upright) scaled.recycle() }
        val maxChannel = pixels.maxOf { maxOf((it ushr 16) and 255, (it ushr 8) and 255, it and 255) }.coerceAtLeast(1).toFloat()
        val input = FloatArray(3 * pixels.size)
        val means = floatArrayOf(.485f, .456f, .406f)
        val deviations = floatArrayOf(.229f, .224f, .225f)
        for (i in pixels.indices) for (channel in 0..2) {
            val color = (pixels[i] ushr (16 - channel * 8)) and 255
            input[channel * pixels.size + i] = (color / maxChannel - means[channel]) / deviations[channel]
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor), setOf(outputName)).use { result ->
                val output = result[0] as OnnxTensor
                val values = FloatArray(SIZE * SIZE)
                output.floatBuffer.get(values)
                // Keep absolute scores: min/max rescaling would amplify noise in empty scenes.
                return ForegroundRegions.extract(values, SIZE, SIZE)
            }
        }
    }

    override fun close() { session.close() }
    companion object { private const val SIZE = 320 }
}
