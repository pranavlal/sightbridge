package com.sightbridge.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.sightbridge.core.model.CameraFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class AiStatus(
    val running: Boolean = false,
    val processedFrames: Long = 0,
    val description: String = "AI stopped",
    val inferenceMs: Long = 0,
    val speechReady: Boolean = false,
    val spokenUtterances: Long = 0,
    val error: String? = null
)

/** Owns copies of sampled camera frames; never retains a media-server bitmap. */
class OnDeviceAiProcessor(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableStatus = MutableStateFlow(AiStatus())
    val status = mutableStatus.asStateFlow()
    private var detector: ObjectDetector? = null
    private var speech: TextToSpeech? = null
    private var work: Job? = null
    private val busy = AtomicBoolean(false)
    private var lastSampleMs = 0L
    private var lastSpeechMs = 0L
    private var lastSpoken = ""

    suspend fun start(): Result<Unit> = runCatching {
        withContext(dispatcher) {
            if (detector == null) {
                detector = ObjectDetector.createFromOptions(context,
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                        .setRunningMode(RunningMode.IMAGE).setScoreThreshold(0.5f).setMaxResults(5).build())
            }
        }
        val speechReady = initialiseSpeech()
        lastSampleMs = 0
        lastSpeechMs = 0
        lastSpoken = ""
        mutableStatus.value = AiStatus(running = true, description = "AI started. Waiting for camera frames", speechReady = speechReady)
    }

    private suspend fun initialiseSpeech(): Boolean = withContext(Dispatchers.Main) {
        if (speech != null) return@withContext mutableStatus.value.speechReady
        val ready = CompletableDeferred<Int>()
        speech = TextToSpeech(context.applicationContext) { ready.complete(it) }
        val initialized = withTimeoutOrNull(10_000) { ready.await() } == TextToSpeech.SUCCESS
        val language = if (initialized) speech?.setLanguage(Locale.ENGLISH) else TextToSpeech.LANG_NOT_SUPPORTED
        val offlineVoice = if (initialized) speech?.voices?.firstOrNull {
            it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        } else null
        val success = initialized && language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED &&
            offlineVoice != null && speech?.setVoice(offlineVoice) == TextToSpeech.SUCCESS
        if (!success) { speech?.shutdown(); speech = null }
        else speech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                mutableStatus.update { if (it.running) it.copy(spokenUtterances = it.spokenUtterances + 1) else it }
            }
            override fun onError(utteranceId: String?) {
                mutableStatus.update { it.copy(speechReady = false, error = "Speech output failed. Detection results remain available on screen.") }
            }
        })
        success
    }

    /** Called by the single camera collector before transferring the original to HTTP. */
    @Synchronized
    fun offer(frame: CameraFrame) {
        val now = SystemClock.elapsedRealtime()
        if (!mutableStatus.value.running || now - lastSampleMs < 1_000 || !busy.compareAndSet(false, true)) return
        lastSampleMs = now
        val copy = try {
            frame.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: error("Cannot copy camera frame")
        } catch (e: Exception) {
            busy.set(false)
            mutableStatus.value = mutableStatus.value.copy(error = "AI frame copy failed: ${e.message}")
            return
        }
        work = scope.launch {
            var rotated: Bitmap? = null
            var image: com.google.mediapipe.framework.image.MPImage? = null
            try {
                if (!mutableStatus.value.running) return@launch
                val rotation = ((frame.rotationDegrees % 360) + 360) % 360
                val input = if (rotation == 0) copy else Bitmap.createBitmap(copy, 0, 0, copy.width, copy.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true).also { rotated = it }
                image = BitmapImageBuilder(input).build()
                val start = SystemClock.elapsedRealtime()
                val result = checkNotNull(detector).detect(image)
                val description = DetectionSummary.describe(result.detections().mapNotNull { detection ->
                    detection.categories().maxByOrNull { it.score() }?.let {
                        DetectedObject(it.categoryName(), it.score(), detection.boundingBox().centerX() / input.width)
                    }
                })
                if (mutableStatus.value.running) {
                    mutableStatus.update { it.copy(processedFrames = it.processedFrames + 1,
                        description = description, inferenceMs = SystemClock.elapsedRealtime() - start, error = if (it.speechReady) null else it.error) }
                    withContext(Dispatchers.Main) {
                        val current = SystemClock.elapsedRealtime()
                        if (mutableStatus.value.running && mutableStatus.value.speechReady &&
                            current - lastSpeechMs >= 6_000 && (description != lastSpoken || current - lastSpeechMs >= 15_000)) {
                            val accepted = speech?.speak(description, TextToSpeech.QUEUE_FLUSH, null, "sightbridge-detection") == TextToSpeech.SUCCESS
                            if (accepted) { lastSpoken = description; lastSpeechMs = current }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableStatus.value = mutableStatus.value.copy(running = false, error = "AI inference failed: ${e.message}")
            } finally {
                image?.close()
                if (rotated !== copy) rotated?.recycle()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (!copy.isRecycled) copy.recycle()
                busy.set(false)
            }
        }
    }

    suspend fun stop() {
        val pending = synchronized(this) {
            mutableStatus.value = mutableStatus.value.copy(running = false, description = "AI stopped")
            work.also { work = null }
        }
        pending?.cancelAndJoin()
        withContext(Dispatchers.Main) { speech?.stop() }
    }

    suspend fun release() {
        stop()
        withContext(dispatcher) { detector?.close(); detector = null }
        withContext(Dispatchers.Main) { speech?.shutdown(); speech = null }
        scope.cancel()
        dispatcher.close()
    }
}
