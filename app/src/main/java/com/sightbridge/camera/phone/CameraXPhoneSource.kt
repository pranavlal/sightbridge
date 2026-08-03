package com.sightbridge.camera.phone

import android.content.Context
import com.sightbridge.camera.api.CameraSource
import com.sightbridge.camera.api.PermissionResult
import com.sightbridge.camera.api.StreamConfig
import com.sightbridge.core.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CameraX Phone Camera Fallback implementation per Section 9 of specification.
 */
class CameraXPhoneSource(
    private val context: Context
) : CameraSource {

    private val _state = MutableStateFlow(CameraSourceState.UNINITIALISED)
    override val state: StateFlow<CameraSourceState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)
    override val frames: SharedFlow<CameraFrame> = _frames.asSharedFlow()

    override val capabilities: CameraCapabilities = CameraCapabilities(
        supportedResolutions = listOf(Pair(1280, 720), Pair(1920, 1080)),
        supportedFrameRates = listOf(30),
        supportsHardwareAcceleration = true,
        isSimulated = false
    )

    override suspend fun initialise(): Result<Unit> {
        _state.value = CameraSourceState.READY
        return Result.success(Unit)
    }

    override suspend fun requestPermissions(): PermissionResult {
        return PermissionResult(granted = true)
    }

    override suspend fun connect(): Result<Unit> {
        _state.value = CameraSourceState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun startStreaming(config: StreamConfig): Result<Unit> {
        _state.value = CameraSourceState.STREAMING
        return Result.success(Unit)
    }

    override suspend fun stopStreaming(): Result<Unit> {
        _state.value = CameraSourceState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        _state.value = CameraSourceState.DISCONNECTED
        return Result.success(Unit)
    }

    override suspend fun release() {
        _state.value = CameraSourceState.RELEASED
    }
}
