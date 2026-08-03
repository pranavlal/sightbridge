package com.sightbridge.camera.mock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Synthetic test frame generator (Mock Camera Source).
 * Generates continuous synthetic test pattern frames with timestamps for testing without hardware.
 * Optimized with persistent Paint resources to minimize GC allocations.
 */
class SimulatedCameraSource(
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 480
) : CameraSource {

    private val _state = MutableStateFlow(CameraSourceState.UNINITIALISED)
    override val state: StateFlow<CameraSourceState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<CameraFrame>(extraBufferCapacity = 1)
    override val frames: SharedFlow<CameraFrame> = _frames.asSharedFlow()

    override val capabilities: CameraCapabilities = CameraCapabilities(
        supportedResolutions = listOf(Pair(640, 480), Pair(1280, 720)),
        supportedFrameRates = listOf(15, 24, 30),
        supportsHardwareAcceleration = true,
        isSimulated = true
    )

    private var streamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val frameIdCounter = AtomicLong(0)

    // Reusable Paint resources safely initialized for both Android ART & JVM unit test environments
    private val textPaint by lazy {
        runCatching {
            Paint().apply {
                color = Color.WHITE
                textSize = 32f
                isAntiAlias = true
            }
        }.getOrNull()
    }
    private val redPaint by lazy { runCatching { Paint().apply { color = Color.RED } }.getOrNull() }
    private val greenPaint by lazy { runCatching { Paint().apply { color = Color.GREEN } }.getOrNull() }
    private val bluePaint by lazy { runCatching { Paint().apply { color = Color.BLUE } }.getOrNull() }
    private val yellowPaint by lazy { runCatching { Paint().apply { color = Color.YELLOW } }.getOrNull() }

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
        if (_state.value == CameraSourceState.STREAMING) return Result.success(Unit)

        _state.value = CameraSourceState.STARTING_STREAM
        val delayMs = (1000 / config.targetFps.coerceIn(1, 60)).toLong()

        streamJob = scope.launch {
            _state.value = CameraSourceState.STREAMING
            val barWidth = targetWidth / 4

            while (isActive) {
                val acqNanos = System.nanoTime()
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                
                // Draw test pattern if Canvas / Paint are available
                runCatching {
                    val canvas = Canvas(bitmap)
                    redPaint?.let { canvas.drawRect(0f, 0f, barWidth.toFloat(), targetHeight.toFloat(), it) }
                    greenPaint?.let { canvas.drawRect(barWidth.toFloat(), 0f, (barWidth * 2).toFloat(), targetHeight.toFloat(), it) }
                    bluePaint?.let { canvas.drawRect((barWidth * 2).toFloat(), 0f, (barWidth * 3).toFloat(), targetHeight.toFloat(), it) }
                    yellowPaint?.let { canvas.drawRect((barWidth * 3).toFloat(), 0f, targetWidth.toFloat(), targetHeight.toFloat(), it) }

                    val frameId = frameIdCounter.get() + 1
                    textPaint?.let { canvas.drawText("SIMULATED FRAME #$frameId", 40f, 60f, it) }
                }

                val frameId = frameIdCounter.incrementAndGet()
                val recNanos = System.nanoTime()
                val cameraFrame = CameraFrame(
                    frameId = frameId,
                    source = CameraSourceType.MOCK_SIMULATED,
                    acquisitionTimestampNanos = acqNanos,
                    receivedTimestampNanos = recNanos,
                    width = targetWidth,
                    height = targetHeight,
                    rotationDegrees = 0,
                    pixelFormat = PixelFormat.RGBA_8888,
                    bitmap = bitmap,
                    metadata = FrameMetadata(isSimulated = true)
                )

                _frames.emit(cameraFrame)
                delay(delayMs)
            }
        }

        return Result.success(Unit)
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
