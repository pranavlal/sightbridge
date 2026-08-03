package com.sightbridge.frame.buffer

import com.sightbridge.core.model.CameraFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe Non-blocking Latest-Frame Buffer per Section 6.2 & 10.3 of specification.
 * Replaces obsolete pending frames with the latest acquisition frame, preventing unbounded queues.
 * Enforces timestamp monotonicity and age freshness validation.
 */
class LatestFrameBuffer(
    private val maxStaleAgeMs: Long = 500
) {
    private val lock = ReentrantLock()
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
     * Thread-safe with atomic frame swapping.
     */
    fun offer(frame: CameraFrame) {
        lock.withLock {
            _totalProducedCount.incrementAndGet()
            val previous = _latestFrame.value
            if (previous != null) {
                _droppedFramesCount.incrementAndGet()
            }
            _latestFrame.value = frame
        }
    }

    /**
     * Consume and clear the current latest frame if valid and fresh.
     * Returns null if no frame exists, if the timestamp is in the future, or exceeds maxStaleAgeMs.
     */
    fun poll(currentMonotonicNanos: Long = System.nanoTime()): CameraFrame? {
        return lock.withLock {
            val frame = _latestFrame.value ?: return null
            _latestFrame.value = null

            // Reject future timestamps
            if (frame.acquisitionTimestampNanos > currentMonotonicNanos) {
                _droppedFramesCount.incrementAndGet()
                return null
            }

            val ageMs = frame.calculateAgeMs(currentMonotonicNanos)
            if (ageMs in 0..maxStaleAgeMs) {
                _totalConsumedCount.incrementAndGet()
                frame
            } else {
                // Discard stale or negative age frame
                _droppedFramesCount.incrementAndGet()
                null
            }
        }
    }

    fun clear() {
        lock.withLock {
            _latestFrame.value = null
        }
    }

    fun resetMetrics() {
        lock.withLock {
            _droppedFramesCount.set(0)
            _totalProducedCount.set(0)
            _totalConsumedCount.set(0)
        }
    }
}
