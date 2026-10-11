package com.sightbridge.pipeline

import com.sightbridge.ai.OnDeviceAiProcessor
import com.sightbridge.camera.api.CameraSource
import com.sightbridge.core.health.HealthWatchdog
import com.sightbridge.core.model.CameraSourceState
import com.sightbridge.output.voicestream.HttpMjpegVoiceStreamAdapter
import com.sightbridge.output.voicestream.VoiceStreamConfig
import com.sightbridge.output.voicestream.VoiceStreamState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PipelineStatus(val mediaRunning: Boolean = false, val aiRunning: Boolean = false, val busy: Boolean = false,
    val message: String = "System ready", val needsPermissions: Boolean = false)

/** Serializes lifecycle changes and fans one camera stream out to independent consumers. */
class PipelineController(
    initialSource: CameraSource,
    private val media: HttpMjpegVoiceStreamAdapter,
    val ai: OnDeviceAiProcessor,
    private val health: HealthWatchdog,
    private val beforeStart: suspend () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(PipelineStatus())
    val status = mutableStatus.asStateFlow()
    private var source = initialSource
    private var frameJob: Job? = null
    private var cameraObserver: Job? = null

    init {
        observeCamera()
        scope.launch {
            ai.status.collect { state ->
                if (!state.running && state.error != null && mutableStatus.value.aiRunning) {
                    stopAi()
                    mutableStatus.value = mutableStatus.value.copy(message = state.error)
                }
                if (state.running && state.sceneChecks > 0) health.recordHeartbeat("AI", "Scene check ${state.sceneChecks}; analyzed ${state.processedFrames}")
            }
        }
        scope.launch {
            media.state.collect { state ->
                if (state == VoiceStreamState.FAILED && mutableStatus.value.mediaRunning) {
                    stopMedia()
                    mutableStatus.value = mutableStatus.value.copy(message = "Media server failed. Start again to retry.")
                }
            }
        }
    }

    private fun observeCamera() {
        cameraObserver?.cancel()
        val observed = source
        cameraObserver = scope.launch {
            observed.state.collect { state ->
                health.updateHealth("CameraSource", if (state == CameraSourceState.STREAMING) com.sightbridge.core.health.HealthLevel.HEALTHY else com.sightbridge.core.health.HealthLevel.DEGRADED, state.name)
                if (state in listOf(CameraSourceState.DISCONNECTED, CameraSourceState.FAILED) &&
                    (mutableStatus.value.mediaRunning || mutableStatus.value.aiRunning)) {
                    mutex.withLock {
                        if (source === observed && observed.state.value in listOf(CameraSourceState.DISCONNECTED, CameraSourceState.FAILED)) {
                            media.stop(); ai.stop(); stopCamera()
                            mutableStatus.value = PipelineStatus(message = "Camera disconnected or failed. Reconnect and start again.")
                        }
                    }
                }
            }
        }
    }

    suspend fun selectSource(next: CameraSource): Boolean = mutex.withLock {
        if (mutableStatus.value.mediaRunning || mutableStatus.value.aiRunning) return@withLock false
        mutableStatus.value = mutableStatus.value.copy(busy = true)
        try {
            stopCamera()
            source = next
            observeCamera()
            true
        } finally { mutableStatus.value = mutableStatus.value.copy(busy = false) }
    }

    private suspend fun ensureCamera() {
        if (source.state.value == CameraSourceState.STREAMING) return
        source.initialise().getOrThrow()
        source.connect().getOrThrow()
        frameJob?.cancelAndJoin()
        frameJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.frames.collect { frame ->
                health.recordHeartbeat("CameraSource", "Frame ${frame.frameId}")
                ai.offer(frame) // copies sampled frames before the original changes ownership
                if (media.submitFrame(frame).isFailure) frame.bitmap.recycle()
            }
        }
        source.startStreaming().getOrThrow()
    }

    suspend fun startMedia(config: VoiceStreamConfig) = command {
        if (mutableStatus.value.mediaRunning) return@command
        beforeStart()
        media.initialise(config).getOrThrow()
        media.start().getOrThrow()
        try {
            ensureCamera()
            mutableStatus.value = mutableStatus.value.copy(mediaRunning = true, message = "Media server running")
        } catch (e: Exception) { media.stop(); throw e }
    }

    suspend fun startAi() = command {
        if (mutableStatus.value.aiRunning) return@command
        beforeStart()
        ai.start().getOrThrow()
        try {
            ensureCamera()
            mutableStatus.value = mutableStatus.value.copy(aiRunning = true, message = "AI processing running")
        } catch (e: Exception) { ai.stop(); throw e }
    }

    suspend fun stopMedia() = command {
        media.stop()
        mutableStatus.value = mutableStatus.value.copy(mediaRunning = false, message = "Media server stopped")
        if (!mutableStatus.value.aiRunning) stopCamera()
    }

    suspend fun stopAi() = command {
        ai.stop()
        health.updateHealth("AI", com.sightbridge.core.health.HealthLevel.UNAVAILABLE, "Stopped")
        mutableStatus.value = mutableStatus.value.copy(aiRunning = false, message = "AI processing stopped")
        if (!mutableStatus.value.mediaRunning) stopCamera()
    }

    private suspend fun stopCamera() {
        runCatching { source.stopStreaming() }
        runCatching { source.disconnect() }
        frameJob?.cancelAndJoin()
        frameJob = null
    }

    private suspend fun command(block: suspend () -> Unit) = mutex.withLock {
        mutableStatus.value = mutableStatus.value.copy(busy = true, needsPermissions = false)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!mutableStatus.value.mediaRunning && !mutableStatus.value.aiRunning) stopCamera()
            mutableStatus.value = mutableStatus.value.copy(message = "Error: ${e.message ?: "Operation failed"}", needsPermissions = e is SecurityException)
        } finally { mutableStatus.value = mutableStatus.value.copy(busy = false) }
    }

    suspend fun release() {
        cameraObserver?.cancel()
        mutex.withLock { media.stop(); ai.release(); stopCamera() }
        scope.cancel()
    }
}
