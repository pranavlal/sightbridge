package com.sightbridge.frame.buffer

import com.sightbridge.core.model.CameraFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Non-blocking Latest-Frame Buffer per Section 6.2 of specification.
 * Replaces obsolete pending frames with the latest acquisition frame, preventing unbounded queues and latency backlogs.
 */
class LatestFrameBuffer(
    private val maxStaleAgeMs: Long = 500
) {
    private val _latestFrame = MutableStateFlow<CameraFrame?>(null)
    val latestFrame: StateFlow<CameraFrame?> = _latestFrame.asStateFlow()

    private val _droppedFramesCount = AtomicLong(0)
    val droppedFramesCount: Long get() = _droppedFramesCount.get()

    private val _totalProducedCount = AtomicLong(0)
    val totalProducedCount: Long get() = _totalProducedCount.get()

    private val _totalConsumedCount = AtomicLong(0)
    val totalConsumedCount: Long get() = _totalConsumedCount.get()

    /**
     * Submit a new camera frame to the buffer.
     * If an unconsumed frame is already present, it is replaced and dropped count is incremented.
     */
    fun offer(frame: CameraFrame) {
        _totalProducedCount.incrementAndGet()
        val previous = _latestFrame.value
        if (previous != null) {
            _droppedFramesCount.incrementAndGet()
        }
        _latestFrame.value = frame
    }

    /**
     * Consume and clear the current latest frame if valid and fresh.
     * Returns null if no frame exists or if the frame exceeds maxStaleAgeMs.
     */
    fun poll(currentMonotonicNanos: Long = System.nanoTime()): CameraFrame? {
        val frame = _latestFrame.value ?: return null
        _latestFrame.value = null

        val ageMs = frame.calculateAgeMs(currentMonotonicNanos)
        return if (ageMs <= maxStaleAgeMs) {
            _totalConsumedCount.incrementAndGet()
            frame
        } else {
            // Discard stale frame
            _droppedFramesCount.incrementAndGet()
            null
        }
    }

    fun clear() {
        _latestFrame.value = null
    }

    fun resetMetrics() {
        _droppedFramesCount.set(0)
        _totalProducedCount.set(0)
        _totalConsumedCount.set(0)
    }
}
