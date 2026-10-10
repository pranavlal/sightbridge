package com.sightbridge.camera.meta

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
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
    private var session: DeviceSession? = null
    private var datStream: Stream? = null
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
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!bluetoothGranted) {
            return PermissionResult(false, listOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
        val cameraGranted = Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            onSuccess = { it is PermissionStatus.Granted },
            onFailure = { _, _ -> false }
        )
        return PermissionResult(
            granted = cameraGranted,
            deniedPermissions = if (cameraGranted) emptyList() else listOf("Meta camera access")
        )
    }

    fun startRegistration(activity: Activity) {
        runCatching { Wearables.startRegistration(activity) }
    }

    fun startUnregistration(activity: Activity) {
        runCatching { Wearables.startUnregistration(activity) }
    }

    override suspend fun connect(): Result<Unit> {
        val perm = requestPermissions()
        if (!perm.granted) {
            _state.value = CameraSourceState.PERMISSION_REQUIRED
            return Result.failure(SecurityException("Required Android Bluetooth or Meta camera permission not granted; register the app and allow camera access in Meta AI"))
        }
        _state.value = CameraSourceState.CONNECTING
        _state.value = CameraSourceState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun startStreaming(config: StreamConfig): Result<Unit> {
        if (_state.value == CameraSourceState.STREAMING) return Result.success(Unit)
        _state.value = CameraSourceState.STARTING_STREAM

        return try {
            stopDatResources()
            val createdSession = Wearables.createSession(AutoDeviceSelector()).fold(
                onSuccess = { it },
                onFailure = { error, cause -> throw IllegalStateException(error.description, cause) }
            )
            createdSession.start()

            val quality = when {
                config.width >= 1280 || config.height >= 720 -> VideoQuality.HIGH
                config.width <= 640 || config.height <= 360 -> VideoQuality.LOW
                else -> VideoQuality.MEDIUM
            }
            val fps = listOf(2, 7, 15, 24, 30).minBy { kotlin.math.abs(it - config.targetFps) }
            val createdStream = createdSession.addStream(
                StreamConfiguration(videoQuality = quality, frameRate = fps, compressVideo = false)
            ).fold(
                onSuccess = { it },
                onFailure = { error, cause ->
                    createdSession.stop()
                    throw IllegalStateException(error.description, cause)
                }
            )

            createdStream.start().fold(
                onSuccess = { Unit },
                onFailure = { error, cause ->
                    createdStream.close()
                    createdSession.stop()
                    throw IllegalStateException(error.description, cause)
                }
            )
            session = createdSession
            datStream = createdStream
            streamJob = scope.launch {
                createdStream.videoStream.collect { frame ->
                    if (frame.isCompressed || frame.width <= 0 || frame.height <= 0) return@collect
                    val expectedBytes = frame.width.toLong() * frame.height * 4
                    val buffer = frame.buffer.duplicate().apply { rewind() }
                    if (buffer.remaining().toLong() < expectedBytes) return@collect
                    val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    val now = System.nanoTime()
                    _frames.emit(
                        CameraFrame(
                            frameId = frameIdCounter.incrementAndGet(),
                            source = CameraSourceType.META_GLASSES,
                            acquisitionTimestampNanos = now,
                            receivedTimestampNanos = now,
                            width = frame.width,
                            height = frame.height,
                            rotationDegrees = 90,
                            pixelFormat = PixelFormat.RGBA_8888,
                            bitmap = bitmap
                        )
                    )
                }
            }
            _state.value = CameraSourceState.STREAMING
            Result.success(Unit)
        } catch (e: Exception) {
            stopDatResources()
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
        stopDatResources()
        _state.value = CameraSourceState.CONNECTED
        return Result.success(Unit)
    }

    private fun stopDatResources() {
        streamJob?.cancel()
        streamJob = null
        runCatching { datStream?.stop() }
        runCatching { datStream?.close() }
        datStream = null
        runCatching { session?.stop() }
        session = null
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
