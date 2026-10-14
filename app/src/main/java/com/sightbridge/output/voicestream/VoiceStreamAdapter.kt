package com.sightbridge.output.voicestream

import com.sightbridge.core.health.ComponentHealth
import com.sightbridge.core.model.CameraFrame
import kotlinx.coroutines.flow.StateFlow

enum class VoiceStreamState {
    STOPPED,
    STARTING,
    STREAMING,
    RECONNECTING,
    FAILED
}

data class VoiceStreamConfig(
    val port: Int = 8080,
    val jpegQuality: Int = 80
)

/**
 * Adapter interface for the secondary vOICe HTTP streaming path per Section 20.
 */
interface VoiceStreamAdapter {
    val state: StateFlow<VoiceStreamState>
    val droppedFramesCount: Long

    suspend fun initialise(config: VoiceStreamConfig = VoiceStreamConfig()): Result<Unit>
    suspend fun start(): Result<Unit>
    suspend fun submitFrame(frame: CameraFrame): Result<Unit>
    suspend fun stop(): Result<Unit>
    suspend fun healthCheck(): ComponentHealth
    suspend fun release()
}
