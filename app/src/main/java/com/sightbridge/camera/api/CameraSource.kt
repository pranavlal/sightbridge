package com.sightbridge.camera.api

import com.sightbridge.core.model.CameraCapabilities
import com.sightbridge.core.model.CameraFrame
import com.sightbridge.core.model.CameraSourceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class PermissionResult(
    val granted: Boolean,
    val deniedPermissions: List<String> = emptyList()
)

data class StreamConfig(
    val width: Int = 896,
    val height: Int = 504,
    val targetFps: Int = 24
)

/**
 * Camera-independent camera source contract per Section 7 of specification.
 */
interface CameraSource {
    val state: StateFlow<CameraSourceState>
    val frames: Flow<CameraFrame>
    val capabilities: CameraCapabilities

    suspend fun initialise(): Result<Unit>
    suspend fun requestPermissions(): PermissionResult
    suspend fun connect(): Result<Unit>
    suspend fun startStreaming(config: StreamConfig = StreamConfig()): Result<Unit>
    suspend fun stopStreaming(): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    suspend fun release()
}
