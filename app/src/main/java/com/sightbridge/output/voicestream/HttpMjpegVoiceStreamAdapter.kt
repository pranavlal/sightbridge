package com.sightbridge.output.voicestream

import com.sightbridge.core.health.ComponentHealth
import com.sightbridge.core.health.HealthLevel
import com.sightbridge.core.health.HealthWatchdog
import com.sightbridge.core.model.CameraFrame
import com.sightbridge.frame.buffer.LatestFrameBuffer
import com.sightbridge.processing.FrameProcessor
import com.sightbridge.server.MjpegHttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of VoiceStreamAdapter using MjpegHttpServer and LatestFrameBuffer per Section 20.
 * Decouples image compression & socket transmission onto a background consumer coroutine with active heartbeat recording.
 */
class HttpMjpegVoiceStreamAdapter(
    private val healthWatchdog: HealthWatchdog? = null,
    private val jpegEncoder: (CameraFrame, Int) -> ByteArray = FrameProcessor::compressFrameToJpeg
) : VoiceStreamAdapter {

    private val _state = MutableStateFlow(VoiceStreamState.STOPPED)
    override val state: StateFlow<VoiceStreamState> = _state.asStateFlow()

    private var mjpegServer: MjpegHttpServer? = null
    private val frameBuffer = LatestFrameBuffer(maxStaleAgeMs = 1000)
    private var activeConfig = VoiceStreamConfig()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var consumerJob: Job? = null

    override val droppedFramesCount: Long
        get() = frameBuffer.droppedFramesCount

    override suspend fun initialise(config: VoiceStreamConfig): Result<Unit> {
        if (config.port !in 1..65535 || config.jpegQuality !in 1..100) {
            return Result.failure(IllegalArgumentException("Invalid MJPEG port or JPEG quality"))
        }
        stop() // Safely stop and release any existing server instance before reinitialization
        activeConfig = config
        mjpegServer = MjpegHttpServer(
            port = config.port
        )
        return Result.success(Unit)
    }

    override suspend fun start(): Result<Unit> {
        if (_state.value == VoiceStreamState.STREAMING) return Result.success(Unit)

        _state.value = VoiceStreamState.STARTING
        val server = mjpegServer ?: run {
            _state.value = VoiceStreamState.FAILED
            return Result.failure(IllegalStateException("Server not initialised"))
        }
        
        val startResult = server.start()
        if (startResult.isFailure) {
            _state.value = VoiceStreamState.FAILED
            return startResult
        }

        _state.value = VoiceStreamState.STREAMING

        // Launch background consumer loop to poll latest frames asynchronously
        consumerJob = scope.launch {
            try {
                while (isActive && _state.value == VoiceStreamState.STREAMING) {
                    val frame = frameBuffer.poll()
                    if (frame != null) {
                        try {
                            val jpegBytes = jpegEncoder(frame, activeConfig.jpegQuality)
                            server.pushJpegFrame(jpegBytes)
                            healthWatchdog?.recordHeartbeat("VoiceStreamAdapter", "Frame ${frame.frameId}")
                        } finally {
                            frame.bitmap.recycle()
                        }
                    } else {
                        delay(10) // Rest briefly if no new frame is pending
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _state.value = VoiceStreamState.FAILED
                }
            }
        }

        return Result.success(Unit)
    }

    override suspend fun submitFrame(frame: CameraFrame): Result<Unit> {
        if (_state.value != VoiceStreamState.STREAMING) {
            return Result.failure(IllegalStateException("Adapter not streaming"))
        }

        // Strictly non-blocking submission to latest-frame buffer
        frameBuffer.offer(frame)
        return Result.success(Unit)
    }

    override suspend fun stop(): Result<Unit> {
        consumerJob?.cancelAndJoin()
        consumerJob = null
        mjpegServer?.stop()
        frameBuffer.clear()
        _state.value = VoiceStreamState.STOPPED
        return Result.success(Unit)
    }

    override suspend fun healthCheck(): ComponentHealth {
        val level = if (_state.value == VoiceStreamState.STREAMING) HealthLevel.HEALTHY else HealthLevel.UNAVAILABLE
        return ComponentHealth(
            component = "VoiceStreamAdapter",
            status = level,
            detailCode = "State: ${_state.value}, DroppedFrames: $droppedFramesCount"
        )
    }

    override suspend fun release() {
        stop()
        scope.cancel()
    }
}
