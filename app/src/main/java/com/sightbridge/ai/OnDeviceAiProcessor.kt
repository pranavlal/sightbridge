package com.sightbridge.ai

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.sightbridge.core.model.CameraFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class AiStatus(
    val running: Boolean = false,
    val processedFrames: Long = 0,
    val description: String = "AI stopped",
    val inferenceMs: Long = 0,
    val sceneChecks: Long = 0,
    val unchangedFramesSkipped: Long = 0,
    val inferenceTrigger: String = "Waiting for first frame",
    val speechReady: Boolean = false,
    val spokenUtterances: Long = 0,
    val accessibilityAnnouncements: Long = 0,
    val model: String = "EfficientDet-Lite2",
    val recognitionMs: Long = 0,
    val localizationMs: Long = 0,
    val foregroundMs: Long = 0,
    val localizationReady: Boolean = false,
    val localizationWarning: String? = null,
    val objects: List<LocatedObject> = emptyList(),
    val error: String? = null
)

internal fun canSpeakOfflineWith(voice: Voice?): Boolean = voice != null &&
    !voice.isNetworkConnectionRequired &&
    voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

/** Owns copies of sampled camera frames; never retains a media-server bitmap. */
class OnDeviceAiProcessor(private val context: Context,
                          private val modelAssetPath: String = "efficientdet_lite2.tflite") {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableStatus = MutableStateFlow(AiStatus())
    val status = mutableStatus.asStateFlow()
    private var detector: ObjectDetector? = null
    private var localizer: OnDeviceObjectLocalizer? = null
    private var foregroundDetector: ForegroundRegionDetector? = null
    private val recognition = RecognitionFusion()
    @Volatile private var confirmationNeeded = false
    @Volatile private var confirmationChecksRemaining = 0
    private var speech: TextToSpeech? = null
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var ttsReady = false
    private var work: Job? = null
    private val busy = AtomicBoolean(false)
    private var lastSampleMs = Long.MIN_VALUE
    private val sceneGate = SceneChangeGate()
    private var lastSpeechMs = 0L
    private var lastSpoken = ""

    suspend fun start(): Result<Unit> = runCatching {
        withContext(dispatcher) {
            if (detector == null) {
                detector = ObjectDetector.createFromOptions(context,
                    ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAssetPath).build())
                        .setRunningMode(RunningMode.IMAGE).setScoreThreshold(0.5f).setMaxResults(15).build())
            }
            if (localizer == null) localizer = OnDeviceObjectLocalizer()
            if (foregroundDetector == null) foregroundDetector = ForegroundRegionDetector(context)
            recognition.reset()
        }
        val speechReady = initialiseSpeech()
        lastSampleMs = Long.MIN_VALUE
        sceneGate.reset()
        confirmationNeeded = false
        confirmationChecksRemaining = 0
        lastSpeechMs = 0
        lastSpoken = ""
        mutableStatus.value = AiStatus(running = true, description = "AI started. Waiting for camera frames", speechReady = speechReady,
            model = if (modelAssetPath.contains("lite2")) "EfficientDet-Lite2" else "EfficientDet-Lite0")
    }

    private suspend fun initialiseSpeech(): Boolean = withContext(Dispatchers.Main) {
        // Let the screen reader own speech; do not start a competing TTS engine.
        if (screenReaderActive()) {
            speech?.stop()
            return@withContext true
        }
        if (speech != null) return@withContext ttsReady
        val ready = CompletableDeferred<Int>()
        speech = TextToSpeech(context.applicationContext) { ready.complete(it) }
        val initialized = withTimeoutOrNull(10_000) { ready.await() } == TextToSpeech.SUCCESS
        // Respect the voice selected in the phone's text-to-speech settings.
        val defaultVoice = if (initialized) speech?.defaultVoice ?: speech?.voice else null
        val success = canSpeakOfflineWith(defaultVoice) &&
            speech?.setVoice(checkNotNull(defaultVoice)) == TextToSpeech.SUCCESS
        ttsReady = success
        if (!success) { speech?.shutdown(); speech = null }
        else speech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                mutableStatus.update { if (it.running) it.copy(spokenUtterances = it.spokenUtterances + 1) else it }
            }
            override fun onError(utteranceId: String?) {
                ttsReady = false
                mutableStatus.update { it.copy(speechReady = false, error = "Speech output failed. Detection results remain available on screen.") }
            }
        })
        success
    }

    private fun screenReaderActive(): Boolean = accessibility.isEnabled &&
        accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()

    /** Runs on the main thread and rechecks the route when TalkBack is toggled. */
    private suspend fun speakDescription(description: String): Boolean {
        if (screenReaderActive()) {
            speech?.stop()
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                packageName = context.packageName
                className = OnDeviceAiProcessor::class.java.name
                text.add(description)
            }
            accessibility.sendAccessibilityEvent(event)
            // Accessibility delivery does not report when TalkBack finishes speaking.
            mutableStatus.update { it.copy(speechReady = true, accessibilityAnnouncements = it.accessibilityAnnouncements + 1) }
            return true
        }
        val ready = initialiseSpeech()
        mutableStatus.update { it.copy(speechReady = ready) }
        return ready && speech?.speak(description, TextToSpeech.QUEUE_FLUSH, null, "sightbridge-detection") == TextToSpeech.SUCCESS
    }

    /** Called by the single camera collector before transferring the original to HTTP. */
    @Synchronized
    fun offer(frame: CameraFrame) {
        val now = SystemClock.elapsedRealtime()
        if (!mutableStatus.value.running || (lastSampleMs != Long.MIN_VALUE && now - lastSampleMs < 250) || !busy.compareAndSet(false, true)) return
        lastSampleMs = now
        val luma: IntArray
        val geometry = "${frame.source}:${frame.width}x${frame.height}:${frame.rotationDegrees}"
        val decision: SceneChangeGate.Decision
        try {
            val thumbnail = Bitmap.createScaledBitmap(frame.bitmap, SceneChangeGate.WIDTH, SceneChangeGate.HEIGHT, true)
            try {
                val pixels = IntArray(SceneChangeGate.WIDTH * SceneChangeGate.HEIGHT)
                thumbnail.getPixels(pixels, 0, SceneChangeGate.WIDTH, 0, 0, SceneChangeGate.WIDTH, SceneChangeGate.HEIGHT)
                luma = IntArray(pixels.size) { i ->
                    val pixel = pixels[i]
                    (77 * ((pixel ushr 16) and 255) + 150 * ((pixel ushr 8) and 255) + 29 * (pixel and 255)) ushr 8
                }
            } finally { if (thumbnail !== frame.bitmap) thumbnail.recycle() }
            decision = sceneGate.evaluate(luma, geometry, now,
                confirmationNeeded = confirmationNeeded && confirmationChecksRemaining > 0)
            mutableStatus.update { it.copy(sceneChecks = it.sceneChecks + 1,
                unchangedFramesSkipped = it.unchangedFramesSkipped + if (decision == SceneChangeGate.Decision.UNCHANGED) 1 else 0) }
            if (decision == SceneChangeGate.Decision.UNCHANGED || decision == SceneChangeGate.Decision.RATE_LIMITED) {
                busy.set(false)
                return
            }
        } catch (e: Exception) {
            busy.set(false)
            mutableStatus.update { it.copy(error = "Scene comparison failed: ${e.message}") }
            return
        }
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
                val recognitionMs = SystemClock.elapsedRealtime() - start
                val observations = result.detections().mapNotNull { detection ->
                    detection.categories().maxByOrNull { it.score() }?.let {
                        val box = detection.boundingBox()
                        RecognitionObservation(it.categoryName(), it.score(), ObjectBox(
                            (box.left / input.width).coerceIn(0f, 1f), (box.top / input.height).coerceIn(0f, 1f),
                            (box.right / input.width).coerceIn(0f, 1f), (box.bottom / input.height).coerceIn(0f, 1f)))
                    }
                }
                val localizationStart = SystemClock.elapsedRealtime()
                var localizationWarning: String? = null
                val boxes = try {
                    checkNotNull(localizer).detect(input)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    localizationWarning = "Position-only detection unavailable. Named recognition is still running."
                    android.util.Log.w("SightBridgeAI", "On-device localization failed", e)
                    emptyList()
                }
                currentCoroutineContext().ensureActive()
                val localizationMs = SystemClock.elapsedRealtime() - localizationStart
                val foregroundStart = SystemClock.elapsedRealtime()
                val visualBoxes = try {
                    checkNotNull(foregroundDetector).detect(input)
                } catch (e: Exception) {
                    localizationWarning = "Some position-only cues are unavailable. Recognition is still running."
                    android.util.Log.w("SightBridgeAI", "Foreground segmentation failed", e)
                    emptyList()
                }
                val foregroundMs = SystemClock.elapsedRealtime() - foregroundStart
                val fused = recognition.update(observations, boxes, SystemClock.elapsedRealtime(), geometry, visualBoxes)
                confirmationNeeded = fused.needsConfirmation
                // At most two follow-up analyses per scene/refresh, including static scenes.
                confirmationChecksRemaining = if (decision == SceneChangeGate.Decision.CONFIRMATION_CHECK)
                    (confirmationChecksRemaining - 1).coerceAtLeast(0) else 2
                sceneGate.markAnalyzed(luma, geometry, now)
                val checking = fused.objects.isEmpty() && fused.needsConfirmation && confirmationChecksRemaining > 0
                val description = if (checking) "Checking objects" else DetectionSummary.describe(fused.objects)
                if (mutableStatus.value.running) {
                    mutableStatus.update { it.copy(processedFrames = it.processedFrames + 1,
                        description = description, inferenceMs = SystemClock.elapsedRealtime() - start,
                        recognitionMs = recognitionMs, localizationMs = localizationMs, foregroundMs = foregroundMs,
                        localizationReady = localizationWarning == null, localizationWarning = localizationWarning,
                        objects = fused.objects,
                        inferenceTrigger = when (decision) {
                            SceneChangeGate.Decision.INITIAL -> "First frame"
                            SceneChangeGate.Decision.SCENE_CHANGE -> "Scene changed"
                            SceneChangeGate.Decision.CONFIRMATION_CHECK -> "Confirming objects"
                            else -> "Periodic refresh"
                        }, error = if (it.speechReady) null else it.error) }
                    withContext(Dispatchers.Main) {
                        val current = SystemClock.elapsedRealtime()
                        if (mutableStatus.value.running && !checking &&
                            current - lastSpeechMs >= 6_000 && (description != lastSpoken || current - lastSpeechMs >= 15_000)) {
                            val accepted = speakDescription(description)
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
        withContext(dispatcher) {
            detector?.close(); detector = null
            localizer?.close(); localizer = null
            foregroundDetector?.close(); foregroundDetector = null
            recognition.reset()
        }
        withContext(Dispatchers.Main) { speech?.shutdown(); speech = null; ttsReady = false }
        scope.cancel()
        dispatcher.close()
    }
}
