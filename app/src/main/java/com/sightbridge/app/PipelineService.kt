package com.sightbridge.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.sightbridge.ai.OnDeviceAiProcessor
import com.sightbridge.camera.api.CameraSource
import com.sightbridge.camera.meta.MetaSdkAdapter
import com.sightbridge.camera.mock.SimulatedCameraSource
import com.sightbridge.camera.phone.CameraXPhoneSource
import com.sightbridge.camera.usb.UsbCameraSource
import com.sightbridge.core.health.HealthWatchdog
import com.sightbridge.core.model.CameraSourceType
import com.sightbridge.output.voicestream.HttpMjpegVoiceStreamAdapter
import com.sightbridge.pipeline.PipelineController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns camera and consumers independently of the dashboard's lifecycle. */
class PipelineService : Service(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private var foreground = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wakeLockRefresh: Job? = null
    val health = HealthWatchdog()
    lateinit var usb: UsbCameraSource
        private set
    lateinit var meta: MetaSdkAdapter
        private set
    lateinit var simulated: SimulatedCameraSource
        private set
    lateinit var phone: CameraXPhoneSource
        private set
    lateinit var media: HttpMjpegVoiceStreamAdapter
        private set
    lateinit var controller: PipelineController
        private set
    private val mutableSourceType = MutableStateFlow(CameraSourceType.USB_CAMERA)
    val sourceType = mutableSourceType.asStateFlow()

    inner class LocalBinder : Binder() {
        val service: PipelineService get() = this@PipelineService
    }

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.STARTED
        usb = UsbCameraSource(this)
        meta = MetaSdkAdapter(this)
        simulated = SimulatedCameraSource()
        phone = CameraXPhoneSource(this).apply { setLifecycleOwner(this@PipelineService) }
        media = HttpMjpegVoiceStreamAdapter(health)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SightBridge:Pipeline").apply { setReferenceCounted(false) }
        controller = PipelineController(usb, media, OnDeviceAiProcessor(this), health,
            beforeStart = { withContext(Dispatchers.Main.immediate) { ensureForeground() } })
        health.startWatchdog(scope)
        scope.launch {
            controller.status.collect { status ->
                if (!foreground) return@collect
                if (!status.busy && !status.mediaRunning && !status.aiRunning) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foreground = false
                    wakeLockRefresh?.cancel()
                    wakeLockRefresh = null
                    if (wakeLock.isHeld) wakeLock.release()
                    stopSelf()
                } else {
                    val text = when {
                        status.mediaRunning && status.aiRunning -> "Media server and AI running"
                        status.mediaRunning -> "Media server running"
                        status.aiRunning -> "AI processing running"
                        else -> "Starting camera processing"
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
    }

    fun source(type: CameraSourceType): CameraSource = when (type) {
        CameraSourceType.USB_CAMERA -> usb
        CameraSourceType.META_GLASSES -> meta
        CameraSourceType.MOCK_SIMULATED -> simulated
        CameraSourceType.PHONE_CAMERA -> phone
        CameraSourceType.RECORDED_REPLAY -> error("Recorded replay is not available")
    }

    suspend fun selectSource(type: CameraSourceType) {
        if (controller.selectSource(source(type))) mutableSourceType.value = type
    }

    // Start/stop jobs belong to the service even if the dashboard closes mid-command.
    fun startMedia() { scope.launch { controller.startMedia(com.sightbridge.output.voicestream.VoiceStreamConfig(bindLocalhostOnly = true)) } }
    fun stopMedia() { scope.launch { controller.stopMedia() } }
    fun startAi() { scope.launch { controller.startAi() } }
    fun stopAi() { scope.launch { controller.stopAi() } }

    private fun ensureForeground() {
        if (foreground) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Camera permission required")
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Camera processing", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Starting camera processing"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        foreground = true
        // A started service remains alive after the dashboard unbinds.
        startService(Intent(this, PipelineService::class.java))
        // Renew only while the service is alive and processing. A stalled service
        // cannot hold the CPU awake indefinitely after its last renewal.
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLockRefresh = scope.launch {
            while (foreground) {
                delay(WAKE_LOCK_TIMEOUT_MS / 2)
                val status = controller.status.value
                if (foreground && (status.busy || status.mediaRunning || status.aiRunning)) {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PipelineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.sightbridge.R.drawable.ic_launcher_foreground)
            .setContentTitle("SightBridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop all", stop).build())
            .build()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) scope.launch {
            controller.stopMedia()
            controller.stopAi()
        }
        // Never restart camera capture without a new user action.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        health.stopWatchdog()
        wakeLockRefresh?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        scope.launch {
            runCatching { controller.release() }
            listOf(usb, meta, simulated, phone).forEach { runCatching { it.release() } }
            runCatching { media.release() }
            scope.cancel()
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "camera_processing"
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.sightbridge.STOP_PIPELINE"
    }
}
