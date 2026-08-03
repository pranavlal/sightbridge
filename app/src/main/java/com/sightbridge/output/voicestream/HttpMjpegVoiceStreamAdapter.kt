package com.sightbridge.output.voicestream

import com.sightbridge.core.health.ComponentHealth
import com.sightbridge.core.health.HealthLevel
import com.sightbridge.core.model.CameraFrame
import com.sightbridge.frame.buffer.LatestFrameBuffer
import com.sightbridge.processing.FrameProcessor
import com.sightbridge.server.MjpegHttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of VoiceStreamAdapter using MjpegHttpServer and LatestFrameBuffer per Section 20.
 * Operates independently of perception pipeline and prevents unbounded queuing.
 */
class HttpMjpegVoiceStreamAdapter : VoiceStreamAdapter {

    private val _state = MutableStateFlow(VoiceStreamState.STOPPED)
    override val state: StateFlow<VoiceStreamState> = _state.asStateFlow()

    private var mjpegServer: MjpegHttpServer? = null
    private val frameBuffer = LatestFrameBuffer(maxStaleAgeMs = 1000)
    private var activeConfig = VoiceStreamConfig()

    override val droppedFramesCount: Long
        get() = frameBuffer.droppedFramesCount

    override suspend fun initialise(config: VoiceStreamConfig): Result<Unit> {
        activeConfig = config
        mjpegServer = MjpegHttpServer(port = config.port, bindToLocalhostOnly = config.bindLocalhostOnly)
        return Result.success(Unit)
    }

    override suspend fun start(): Result<Unit> {
        _state.value = VoiceStreamState.STARTING
        mjpegServer?.start()
        _state.value = VoiceStreamState.STREAMING
        return Result.success(Unit)
    }

    override suspend fun submitFrame(frame: CameraFrame): Result<Unit> {
        if (_state.value != VoiceStreamState.STREAMING) {
            return Result.failure(IllegalStateException("Adapter not streaming"))
        }

        // Non-blocking submission to latest-frame buffer
        frameBuffer.offer(frame)

        // Poll latest fresh frame and push to HTTP server
        val freshFrame = frameBuffer.poll()
        if (freshFrame != null) {
            val jpegBytes = FrameProcessor.compressBitmapToJpeg(freshFrame.bitmap, activeConfig.jpegQuality)
            mjpegServer?.pushJpegFrame(jpegBytes)
        }

        return Result.success(Unit)
    }

    override suspend fun stop(): Result<Unit> {
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
    }
}
