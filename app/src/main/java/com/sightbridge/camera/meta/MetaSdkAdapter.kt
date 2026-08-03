package com.sightbridge.camera.meta

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import com.meta.wearable.dat.core.Wearables
import com.sightbridge.camera.api.CameraSource
import com.sightbridge.camera.api.PermissionResult
import com.sightbridge.camera.api.StreamConfig
import com.sightbridge.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Meta Wearables Device Access Toolkit (DAT) SDK Adapter.
 * Encapsulates all DAT SDK calls inside com.sightbridge.camera.meta per Section 8 of specification.
 */
class MetaSdkAdapter(
    private val context: Context
) : CameraSource {

    private val _state = MutableStateFlow(CameraSourceState.UNINITIALISED)
    override val state: StateFlow<CameraSourceState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)
    override val frames: SharedFlow<CameraFrame> = _frames.asSharedFlow()

    override val capabilities: CameraCapabilities = CameraCapabilities(
        supportedResolutions = listOf(Pair(896, 504), Pair(1280, 720), Pair(640, 360)),
        supportedFrameRates = listOf(15, 24, 30),
        supportsHardwareAcceleration = true,
        isSimulated = false
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var streamJob: Job? = null
    private val frameIdCounter = AtomicLong(0)

    override suspend fun initialise(): Result<Unit> {
        return try {
            Wearables.initialize(context)
            _state.value = CameraSourceState.READY
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = CameraSourceState.FAILED
            Result.failure(e)
        }
    }

    override suspend fun requestPermissions(): PermissionResult {
        return PermissionResult(granted = true)
    }

    fun startRegistration(activity: Activity) {
        runCatching { Wearables.startRegistration(activity) }
    }

    fun startUnregistration(activity: Activity) {
        runCatching { Wearables.startUnregistration(activity) }
    }

    override suspend fun connect(): Result<Unit> {
        _state.value = CameraSourceState.CONNECTING
        _state.value = CameraSourceState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun startStreaming(config: StreamConfig): Result<Unit> {
        _state.value = CameraSourceState.STARTING_STREAM

        return try {
            // Reflected DAT SDK invocation to maintain stability across DAT SDK preview builds
            val selectorClass = Class.forName("com.meta.wearable.dat.core.selectors.AutoDeviceSelector")
            val selector = selectorClass.getDeclaredConstructor().newInstance()

            val createSessionMethod = Wearables::class.java.getMethod("createSession", selectorClass)
            val sessionResult = createSessionMethod.invoke(null, selector)

            if (sessionResult != null) {
                _state.value = CameraSourceState.STREAMING
            } else {
                _state.value = CameraSourceState.FAILED
            }

            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = CameraSourceState.FAILED
            Result.failure(e)
        }
    }

    fun emitFrameForTesting(bitmap: Bitmap) {
        val frameId = frameIdCounter.incrementAndGet()
        val acqNanos = System.nanoTime()
        val cameraFrame = CameraFrame(
            frameId = frameId,
            source = CameraSourceType.META_GLASSES,
            acquisitionTimestampNanos = acqNanos,
            receivedTimestampNanos = acqNanos,
            width = bitmap.width,
            height = bitmap.height,
            rotationDegrees = 0,
            pixelFormat = PixelFormat.RGBA_8888,
            bitmap = bitmap
        )
        _frames.tryEmit(cameraFrame)
    }

    override suspend fun stopStreaming(): Result<Unit> {
        streamJob?.cancel()
        streamJob = null
        _state.value = CameraSourceState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        stopStreaming()
        _state.value = CameraSourceState.DISCONNECTED
        return Result.success(Unit)
    }

    override suspend fun release() {
        disconnect()
        scope.cancel()
        _state.value = CameraSourceState.RELEASED
    }
}
