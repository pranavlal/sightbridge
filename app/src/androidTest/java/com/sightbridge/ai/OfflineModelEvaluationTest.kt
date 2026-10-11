package com.sightbridge.ai

import android.Manifest
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** No reference photos are bundled or uploaded; optional inputs stay in app-private storage. */
class OfflineModelEvaluationTest {
    private fun report(text: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
    }

    @Test fun bundledModelsAndPositionDetectorRunLocally() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        if (InstrumentationRegistry.getArguments().getString("assertOffline") == "true") {
            assertEquals("Offline-check APK must have no network permission", PackageManager.PERMISSION_DENIED,
                context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName))
        }
        val reference = File(context.filesDir, "ai-reference.jpg")
        val bitmap = if (reference.exists()) checkNotNull(BitmapFactory.decodeFile(reference.path))
            else Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
        val image = BitmapImageBuilder(bitmap).build()
        val allObservations = mutableMapOf<String, List<RecognitionObservation>>()
        try {
            for (asset in listOf("efficientdet_lite0.tflite", "efficientdet_lite2.tflite")) {
                val detector = ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(asset).build())
                    .setRunningMode(RunningMode.IMAGE).setScoreThreshold(.5f).setMaxResults(15).build())
                try {
                    val times = mutableListOf<Long>()
                    repeat(5) { index ->
                        val start = SystemClock.elapsedRealtime()
                        val result = detector.detect(image)
                        val elapsed = SystemClock.elapsedRealtime() - start
                        if (index > 0) times.add(elapsed)
                        val observations = result.detections().mapNotNull { detection ->
                            detection.categories().maxByOrNull { it.score() }?.let { category ->
                                val box = detection.boundingBox()
                                RecognitionObservation(category.categoryName(), category.score(), ObjectBox(
                                    (box.left / bitmap.width).coerceIn(0f, 1f), (box.top / bitmap.height).coerceIn(0f, 1f),
                                    (box.right / bitmap.width).coerceIn(0f, 1f), (box.bottom / bitmap.height).coerceIn(0f, 1f)))
                            }
                        }
                        allObservations[asset] = observations
                    }
                    report("$asset: warm mean ${times.average()} ms; raw observations ${allObservations[asset]}")
                } finally { detector.close() }
            }
            val localizer = OnDeviceObjectLocalizer()
            try {
                val times = mutableListOf<Long>()
                var boxes = emptyList<ObjectBox>()
                repeat(5) { index ->
                    val start = SystemClock.elapsedRealtime()
                    boxes = localizer.detect(bitmap)
                    if (index > 0) times.add(SystemClock.elapsedRealtime() - start)
                }
                report("Position-only detector: warm mean ${times.average()} ms; regions $boxes")
                val foreground = ForegroundRegionDetector(context)
                val visualBoxes: List<ObjectBox>
                try {
                    val foregroundTimes = mutableListOf<Long>()
                    var latest = emptyList<ObjectBox>()
                    repeat(5) { index ->
                        val start = SystemClock.elapsedRealtime()
                        latest = foreground.detect(bitmap)
                        if (index > 0) foregroundTimes.add(SystemClock.elapsedRealtime() - start)
                    }
                    visualBoxes = latest
                    report("Foreground detector: warm mean ${foregroundTimes.average()} ms; visual regions $visualBoxes")
                    if (InstrumentationRegistry.getArguments().getString("expectRegion") == "true") {
                        assertTrue("Reference view must yield an independent position cue", boxes.isNotEmpty() || visualBoxes.isNotEmpty())
                    }
                    val blank = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
                    try { assertTrue("Blank scene must not invent a visual region", foreground.detect(blank).isEmpty()) }
                    finally { blank.recycle() }
                } finally { foreground.close() }
                for ((model, observations) in allObservations) {
                    val fusion = RecognitionFusion()
                    var result = RecognitionResult(emptyList(), false)
                    repeat(3) { result = fusion.update(observations, boxes, it * 1000L, "reference", visualBoxes) }
                    report("$model conservative output: ${DetectionSummary.describe(result.objects)}")
                }
            } finally { localizer.close() }
        } finally { image.close(); bitmap.recycle() }
    }
}
