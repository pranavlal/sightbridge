package com.sightbridge.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Detects prominent object regions without asking ML Kit to name them. */
class OnDeviceObjectLocalizer : AutoCloseable {
    private val detector = ObjectDetection.getClient(ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
        .enableMultipleObjects()
        // Classification deliberately remains disabled.
        .build())
    private val direct = Executor { it.run() }

    suspend fun detect(upright: Bitmap): List<ObjectBox> = suspendCoroutine { continuation ->
        // Wait for actual completion, including during stop, before recycling this bitmap.
        detector.process(InputImage.fromBitmap(upright, 0))
            .addOnSuccessListener(direct) { objects ->
                continuation.resume(objects.map { result ->
                    val box = result.boundingBox
                    ObjectBox(
                        (box.left.toFloat() / upright.width).coerceIn(0f, 1f),
                        (box.top.toFloat() / upright.height).coerceIn(0f, 1f),
                        (box.right.toFloat() / upright.width).coerceIn(0f, 1f),
                        (box.bottom.toFloat() / upright.height).coerceIn(0f, 1f))
                }.filter { it.valid })
            }
            .addOnFailureListener(direct) { continuation.resumeWithException(it) }
    }

    override fun close() { detector.close() }
}
