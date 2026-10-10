package com.sightbridge.camera.usb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ImageReader
import android.graphics.PixelFormat as AndroidPixelFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import com.sightbridge.camera.api.*
import com.sightbridge.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Direct UVC capture: does not depend on a vendor external Camera2 provider. */
class UsbCameraSource(private val context: Context) : CameraSource {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mutableState = MutableStateFlow(CameraSourceState.UNINITIALISED)
    override val state = mutableState.asStateFlow()
    private val mutableFrames = MutableSharedFlow<CameraFrame>()
    override val frames = mutableFrames.asSharedFlow()
    private val mutableDevice = MutableStateFlow("Checking USB cameras…")
    val deviceDescription = mutableDevice.asStateFlow()
    override var capabilities = CameraCapabilities(emptyList(), emptyList(), false, false)
        private set
    private val lock = Mutex()
    private var camera: UVCCamera? = null
    private var previewReader: ImageReader? = null
    private var pending: CompletableDeferred<USBMonitor.UsbControlBlock>? = null
    private var selected: UsbDevice? = null
    private var released = false
    private val ids = AtomicLong()
    private var lastFrameNanos = 0L
    private val monitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) { refreshDevices() }
        override fun onDetach(device: UsbDevice) {
            if (device.deviceName == selected?.deviceName) {
                pending?.completeExceptionally(IllegalStateException("USB camera disconnected"))
                mutableState.value = CameraSourceState.DISCONNECTED
            }
            refreshDevices()
        }
        override fun onDeviceOpen(device: UsbDevice, block: USBMonitor.UsbControlBlock, createNew: Boolean) {
            if (device.deviceName == selected?.deviceName) pending?.complete(block)
        }
        override fun onDeviceClose(device: UsbDevice, block: USBMonitor.UsbControlBlock) {
            if (!released && device.deviceName == selected?.deviceName) mutableState.value = CameraSourceState.DISCONNECTED
        }
        override fun onCancel(device: UsbDevice) {
            pending?.completeExceptionally(SecurityException("USB camera access denied. Start again to allow access."))
        }
    }, Handler(Looper.getMainLooper()))

    init { monitor.register(); refreshDevices() }

    fun refreshDevices() {
        val devices = manager.deviceList.values.filter { isUvcDevice(it) }.sortedBy { it.deviceName }
        mutableDevice.value = devices.firstOrNull()?.let {
            "USB camera detected: ${it.productName ?: "UVC webcam"} (${it.vendorId.toString(16)}:${it.productId.toString(16)})"
        } ?: "No USB camera detected. Connect a UVC webcam using a USB OTG adapter."
    }

    override suspend fun initialise(): Result<Unit> = runCatching {
        check(!released) { "Camera source released" }
        refreshDevices()
        mutableState.value = CameraSourceState.READY
    }

    override suspend fun requestPermissions(): PermissionResult {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return PermissionResult(granted, if (granted) emptyList() else listOf(Manifest.permission.CAMERA))
    }

    override suspend fun connect(): Result<Unit> = lock.withLock {
        try {
            check(!released)
            if (!requestPermissions().granted) throw SecurityException("Camera permission required")
            closeCamera()
            val device = manager.deviceList.values.filter { isUvcDevice(it) }.sortedBy { it.deviceName }.firstOrNull()
                ?: error("No UVC webcam connected. Check the USB OTG connection.")
            selected = device
            mutableState.value = CameraSourceState.PERMISSION_REQUIRED
            val permission = CompletableDeferred<USBMonitor.UsbControlBlock>()
            pending = permission
            withContext(Dispatchers.Main) { monitor.requestPermission(device) }
            val block = withTimeout(60_000) { permission.await() }
            withContext(Dispatchers.Main) {
                val opened = UVCCamera(null)
                camera = opened
                check(opened.open(block) == 0) { "Cannot open USB camera. Close other USB camera apps and retry." }
                val sizes = opened.supportedSizeList.orEmpty()
                capabilities = CameraCapabilities(sizes.map { it.width to it.height }.distinct(), sizes.map { it.fps }.distinct(), false, false)
            }
            mutableState.value = CameraSourceState.CONNECTED
            Result.success(Unit)
        } catch (e: CancellationException) {
            closeCamera()
            mutableState.value = CameraSourceState.FAILED
            throw e
        } catch (e: Exception) {
            closeCamera()
            mutableState.value = if (e is SecurityException) CameraSourceState.PERMISSION_REQUIRED else CameraSourceState.FAILED
            Result.failure(e)
        } finally { pending = null }
    }

    override suspend fun startStreaming(config: StreamConfig): Result<Unit> = lock.withLock {
        runCatching {
            val opened = camera ?: error("USB camera is not connected")
            val size = opened.supportedSizeList.orEmpty().minByOrNull {
                abs(it.width.toLong() * it.height - config.width.toLong() * config.height)
            } ?: error("USB camera reports no supported video modes")
            opened.setPreviewSize(size)
            lastFrameNanos = 0
            opened.setFrameCallback({ buffer ->
                val now = System.nanoTime()
                if (mutableState.value == CameraSourceState.STREAMING && now - lastFrameNanos >= 1_000_000_000L / config.targetFps && mutableFrames.subscriptionCount.value > 0) {
                    lastFrameNanos = now
                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    try {
                        buffer.rewind()
                        bitmap.copyPixelsFromBuffer(buffer)
                        val frame = CameraFrame(ids.incrementAndGet(), CameraSourceType.USB_CAMERA, now, System.nanoTime(), size.width, size.height, 0, PixelFormat.RGBA_8888, bitmap)
                        // Rendezvous flow keeps native frame ownership bounded; downstream owns accepted bitmaps.
                        runBlocking { mutableFrames.emit(frame) }
                    } catch (e: Exception) { bitmap.recycle() }
                }
            }, UVCCamera.PIXEL_FORMAT_RGBX)
            withContext(Dispatchers.Main) {
                // Drain the native preview surface so its buffer queue never blocks capture.
                previewReader = ImageReader.newInstance(size.width, size.height, AndroidPixelFormat.RGBA_8888, 2).apply {
                    setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                }
                opened.setPreviewDisplay(previewReader!!.surface)
                mutableState.value = CameraSourceState.STREAMING
                opened.startPreview()
            }
        }.onFailure { mutableState.value = CameraSourceState.FAILED; closeCamera() }
    }

    private fun closeCamera() {
        camera?.let { runCatching { it.setFrameCallback(null, 0); it.destroy() } }
        camera = null
        previewReader?.close()
        previewReader = null
    }
    override suspend fun stopStreaming(): Result<Unit> = lock.withLock {
        runCatching { camera?.stopPreview(); mutableState.value = CameraSourceState.CONNECTED }
    }
    override suspend fun disconnect(): Result<Unit> = lock.withLock {
        runCatching { closeCamera(); selected = null; mutableState.value = CameraSourceState.DISCONNECTED }
    }
    override suspend fun release() {
        released = true
        pending?.cancel()
        disconnect()
        monitor.destroy()
        mutableState.value = CameraSourceState.RELEASED
    }
    companion object {
        fun isUvcDevice(device: UsbDevice): Boolean = (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == 14 && device.getInterface(it).interfaceSubclass == 1
        }
    }
}
