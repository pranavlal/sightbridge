package com.sightbridge.ai

import kotlin.math.abs

/** Compares against the last analyzed scene, so gradual changes accumulate. */
class SceneChangeGate(
    private val minInferenceIntervalMs: Long = 1_000,
    private val refreshIntervalMs: Long = 5_000
) {
    enum class Decision { INITIAL, SCENE_CHANGE, PERIODIC_REFRESH, CONFIRMATION_CHECK, UNCHANGED, RATE_LIMITED }
    private var baseline: IntArray? = null
    private var geometry: String? = null
    private var lastInferenceMs = 0L

    init { require(minInferenceIntervalMs > 0 && refreshIntervalMs >= minInferenceIntervalMs) }

    @Synchronized
    fun reset() { baseline = null; geometry = null; lastInferenceMs = 0 }

    @Synchronized
    fun evaluate(luma: IntArray, geometryKey: String, nowMs: Long, confirmationNeeded: Boolean = false): Decision {
        require(luma.size == WIDTH * HEIGHT && luma.all { it in 0..255 })
        val previous = baseline ?: return Decision.INITIAL
        if (nowMs - lastInferenceMs < minInferenceIntervalMs) return Decision.RATE_LIMITED
        if (geometryKey != geometry) return Decision.SCENE_CHANGE
        if (nowMs - lastInferenceMs >= refreshIntervalMs) return Decision.PERIODIC_REFRESH
        // Remove modest uniform exposure shifts when measuring structural change.
        val exposureShift = luma.indices.sumOf { luma[it] - previous[it] }.toDouble() / luma.size
        if (abs(exposureShift) >= 20) return Decision.SCENE_CHANGE
        val differences = DoubleArray(luma.size) { abs(luma[it] - previous[it] - exposureShift) }
        if (differences.average() >= 8) return Decision.SCENE_CHANGE
        // Local tiles preserve sensitivity to an object occupying a small image region.
        for (tileY in 0 until HEIGHT step TILE) for (tileX in 0 until WIDTH step TILE) {
            var total = 0.0
            var changed = 0
            for (y in tileY until tileY + TILE) for (x in tileX until tileX + TILE) {
                val delta = differences[y * WIDTH + x]
                total += delta
                if (delta >= 12) changed++
            }
            if (total / (TILE * TILE) >= 18 && changed >= TILE * TILE / 4) return Decision.SCENE_CHANGE
        }
        return if (confirmationNeeded) Decision.CONFIRMATION_CHECK else Decision.UNCHANGED
    }

    /** Advance the reference only after inference has succeeded. */
    @Synchronized
    fun markAnalyzed(luma: IntArray, geometryKey: String, nowMs: Long) {
        baseline = luma.copyOf()
        geometry = geometryKey
        lastInferenceMs = nowMs
    }

    companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        private const val TILE = 8
    }
}
