package com.sightbridge.camera.meta

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import com.sightbridge.camera.api.CameraSource
import com.sightbridge.camera.api.PermissionResult
import com.sightbridge.camera.api.StreamConfig
import com.sightbridge.core.model.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** DAT camera source; the Activity owns registration and camera permission prompts. */
class MetaSdkAdapter(private val context: Context) : CameraSource {
    private val mutableState = MutableStateFlow(CameraSourceState.UNINITIALISED)
    override val state: StateFlow<CameraSourceState> = mutableState.asStateFlow()
    private val mutableFrames = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)
    override val frames: SharedFlow<CameraFrame> = mutableFrames.asSharedFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    override val capabilities = CameraCapabilities(
        supportedResolutions = listOf(504 to 896, 720 to 1280, 360 to 640),
        supportedFrameRates = listOf(15, 24, 30),
        supportsHardwareAcceleration = true,
        isSimulated = false,
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var frameJob: Job? = null
    private var sessionJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private val frameIds = AtomicLong()

    override suspend fun initialise(): Result<Unit> = Wearables.initialize(context).fold(
        onSuccess = { mutableState.value = CameraSourceState.READY; Result.success(Unit) },
        onFailure = { error, cause ->
            if (error == WearablesError.ALREADY_INITIALIZED) {
                mutableState.value = CameraSourceState.READY
                Result.success(Unit)
            } else {
                mutableState.value = CameraSourceState.FAILED
                Result.failure(IllegalStateException(error.description, cause))
            }
        },
    )

    override suspend fun requestPermissions(): PermissionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) {
            return PermissionResult(false, listOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            return PermissionResult(false, listOf("Meta AI registration"))
        }
        val granted = Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            onSuccess = { it == PermissionStatus.Granted },
            onFailure = { error, cause -> throw IllegalStateException(error.description, cause) },
        )
        return PermissionResult(granted, if (granted) emptyList() else listOf("Meta glasses camera"))
    }

    fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)
    fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)

    override suspend fun connect(): Result<Unit> = try {
        val permissions = requestPermissions()
        if (!permissions.granted) {
            mutableState.value = CameraSourceState.PERMISSION_REQUIRED
            Result.failure(SecurityException("Allow ${permissions.deniedPermissions.joinToString()} to use Meta glasses"))
        } else {
            mutableState.value = CameraSourceState.CONNECTED
            Result.success(Unit)
        }
    } catch (e: Exception) {
        mutableState.value = CameraSourceState.FAILED
        Result.failure(e)
    }

    override suspend fun startStreaming(config: StreamConfig): Result<Unit> {
        if (mutableState.value == CameraSourceState.STREAMING) return Result.success(Unit)
        mutableState.value = CameraSourceState.STARTING_STREAM
        mutableError.value = null
        return try {
            stopDatResources()
            val createdSession = Wearables.createSession(AutoDeviceSelector()).fold(
                onSuccess = { it },
                onFailure = { error, cause -> throw IllegalStateException(error.description, cause) },
            )
            session = createdSession
            sessionJob = scope.launch {
                createdSession.state.collect { state ->
                    if (state == DeviceSessionState.STOPPED && session === createdSession &&
                        mutableState.value == CameraSourceState.STREAMING) {
                        mutableError.value = "Meta glasses disconnected. Reconnect and start again."
                        mutableState.value = CameraSourceState.DISCONNECTED
                    }
                }
            }
            sessionErrorJob = scope.launch {
                createdSession.errors.collect { error ->
                    mutableError.value = error.description
                    if (session === createdSession && mutableState.value == CameraSourceState.STREAMING &&
                        error != com.meta.wearable.dat.core.types.DeviceSessionError.DWA_OUT_OF_STU_RANGE) {
                        mutableState.value = CameraSourceState.FAILED
                    }
                }
            }
            createdSession.start()
            val started = withTimeout(30_000) {
                createdSession.state.first {
                    it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED
                }
            }
            check(started == DeviceSessionState.STARTED) {
                mutableError.value ?: "Meta glasses session stopped before it connected"
            }
            val quality = when {
                config.width >= 1280 || config.height >= 720 -> VideoQuality.HIGH
                config.width <= 640 && config.height <= 360 -> VideoQuality.LOW
                else -> VideoQuality.MEDIUM
            }
            val fps = listOf(2, 7, 15, 24, 30).minBy { kotlin.math.abs(it - config.targetFps) }
            val createdCamera = createdSession.addCamera(
                StreamConfiguration(videoQuality = quality, frameRate = fps, compressVideo = true),
            ).fold(
                onSuccess = { it },
                onFailure = { error, cause -> throw IllegalStateException(error.description, cause) },
            )
            camera = createdCamera
            val stream = createdCamera.stream
            frameJob = scope.launch {
                val decoder = GlassesVideoDecoder()
                try {
                    stream.videoStream.collect { frame ->
                        if (!frame.isCompressed) return@collect
                        decoder.decode(frame) { bitmap ->
                            val now = System.nanoTime()
                            val result = CameraFrame(
                                frameId = frameIds.incrementAndGet(),
                                source = CameraSourceType.META_GLASSES,
                                acquisitionTimestampNanos = now,
                                receivedTimestampNanos = now,
                                width = bitmap.width,
                                height = bitmap.height,
                                rotationDegrees = 0,
                                pixelFormat = PixelFormat.RGBA_8888,
                                bitmap = bitmap,
                            )
                            if (mutableFrames.subscriptionCount.value == 0 || !mutableFrames.tryEmit(result)) bitmap.recycle()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableError.value = "Meta video decoding failed: ${e.message}"
                    mutableState.value = CameraSourceState.FAILED
                } finally {
                    decoder.close()
                }
            }
            streamStateJob = scope.launch {
                var active = false
                stream.state.collect { state ->
                    if (state == StreamState.STARTING || state == StreamState.STARTED ||
                        state == StreamState.STREAMING || state == StreamState.PAUSED) active = true
                    if (active && state in listOf(StreamState.STOPPED, StreamState.CLOSED) &&
                        mutableState.value == CameraSourceState.STREAMING) {
                        mutableError.value = "Meta camera stream ended. Start again to reconnect."
                        mutableState.value = CameraSourceState.DISCONNECTED
                    }
                }
            }
            streamErrorJob = scope.launch {
                stream.errorStream.collect { error ->
                    if (error != StreamError.STREAM_ERROR) {
                        mutableError.value = error.description
                        if (error == StreamError.CRITICAL_STREAM_ERROR ||
                            error == StreamError.PERMISSIONS_DENIED) mutableState.value = CameraSourceState.FAILED
                    }
                }
            }
            stream.start().fold(
                onSuccess = { Unit },
                onFailure = { error, cause -> throw IllegalStateException(error.description, cause) },
            )
            mutableState.value = CameraSourceState.STREAMING
            Result.success(Unit)
        } catch (e: CancellationException) {
            stopDatResources()
            throw e
        } catch (e: Exception) {
            stopDatResources()
            mutableError.value = e.message
            mutableState.value = CameraSourceState.FAILED
            Result.failure(e)
        }
    }

    private suspend fun stopDatResources() {
        frameJob?.cancelAndJoin(); frameJob = null
        streamStateJob?.cancelAndJoin(); streamStateJob = null
        streamErrorJob?.cancelAndJoin(); streamErrorJob = null
        sessionJob?.cancelAndJoin(); sessionJob = null
        sessionErrorJob?.cancelAndJoin(); sessionErrorJob = null
        runCatching { camera?.close() }; camera = null
        runCatching { session?.stop() }; session = null
    }

    override suspend fun stopStreaming(): Result<Unit> {
        stopDatResources()
        mutableState.value = CameraSourceState.CONNECTED
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        stopDatResources()
        mutableState.value = CameraSourceState.DISCONNECTED
        return Result.success(Unit)
    }

    override suspend fun release() {
        stopDatResources()
        scope.cancel()
        mutableState.value = CameraSourceState.RELEASED
    }

    fun emitFrameForTesting(bitmap: Bitmap) {
        val now = System.nanoTime()
        if (mutableFrames.subscriptionCount.value == 0 || !mutableFrames.tryEmit(CameraFrame(frameIds.incrementAndGet(), CameraSourceType.META_GLASSES,
                now, now, bitmap.width, bitmap.height, 0, PixelFormat.RGBA_8888, bitmap))) {
            bitmap.recycle()
        }
    }
}
