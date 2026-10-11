package com.sightbridge.ai

import kotlin.math.max
import kotlin.math.min

/** Coordinates refer to the upright camera image, normalized to [0, 1]. */
data class ObjectBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = (right - left) * (bottom - top)
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val valid: Boolean get() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        right > left && bottom > top

    fun intersection(other: ObjectBox): Float =
        max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))

    fun iou(other: ObjectBox): Float {
        val intersection = intersection(other)
        return intersection / (area + other.area - intersection).coerceAtLeast(0.000001f)
    }

    fun sameRegion(other: ObjectBox): Boolean = iou(other) >= 0.45f ||
        (intersection(other) / min(area, other.area).coerceAtLeast(0.000001f) >= 0.8f &&
            max(area, other.area) / min(area, other.area).coerceAtLeast(0.000001f) <= 2.5f)
}

data class RecognitionObservation(val label: String, val score: Float, val box: ObjectBox)
data class LocatedObject(val label: String?, val box: ObjectBox, val visualRegion: Boolean = false)
data class RecognitionResult(val objects: List<LocatedObject>, val needsConfirmation: Boolean)

/**
 * Names require consecutive high-score, spatially matching observations.
 * Unnamed cues come only from the independent localizer, never low class scores.
 */
class RecognitionFusion {
    private data class NameTrack(val observation: RecognitionObservation, val count: Int)
    private data class RegionTrack(val box: ObjectBox, val count: Int, val visualRegion: Boolean)
    private var names = emptyList<NameTrack>()
    private var regions = emptyList<RegionTrack>()
    private var geometry: String? = null
    private var lastMs: Long? = null

    fun reset() { names = emptyList(); regions = emptyList(); geometry = null; lastMs = null }

    fun update(observations: List<RecognitionObservation>, localizedBoxes: List<ObjectBox>,
               nowMs: Long, geometryKey: String, visualBoxes: List<ObjectBox> = emptyList()): RecognitionResult {
        if (geometry != geometryKey || lastMs?.let { nowMs < it || nowMs - it > 8_000 } == true) reset()
        geometry = geometryKey
        lastMs = nowMs

        val candidates = observations.filter {
            it.box.valid && it.label.isNotBlank() && it.label != "???" &&
                it.score.isFinite() && it.score in minimumScore(it.label)..1f
        }.sortedByDescending { it.score }
        val previousNames = names.toMutableList()
        val nextNames = mutableListOf<NameTrack>()
        for (candidate in candidates) {
            if (nextNames.any { it.observation.box.iou(candidate.box) >= 0.6f }) continue
            val previous = previousNames.filter {
                it.observation.label == candidate.label && it.observation.box.iou(candidate.box) >= 0.35f
            }.maxByOrNull { it.observation.box.iou(candidate.box) }
            if (previous != null) previousNames.remove(previous)
            nextNames.add(NameTrack(candidate, ((previous?.count ?: 0) + 1).coerceAtMost(requiredSamples(candidate.label))))
        }
        names = nextNames

        val previousRegions = regions.toMutableList()
        val nextRegions = mutableListOf<RegionTrack>()
        val candidatesForLocation = localizedBoxes.map { it to false } + visualBoxes.map { it to true }
        for ((box, visual) in candidatesForLocation.filter { it.first.valid && it.first.area in 0.005f..0.95f }) {
            if (nextRegions.any { it.box.sameRegion(box) }) continue
            val previous = previousRegions.filter { it.visualRegion == visual && it.box.iou(box) >= 0.35f }.maxByOrNull { it.box.iou(box) }
            if (previous != null) previousRegions.remove(previous)
            nextRegions.add(RegionTrack(box, ((previous?.count ?: 0) + 1).coerceAtMost(2), visual))
        }
        regions = nextRegions

        val recognized = names.filter { it.count >= requiredSamples(it.observation.label) }
            .map { LocatedObject(it.observation.label, it.observation.box) }
        val unknown = regions.filter { track ->
            track.count >= 2 && recognized.none { it.box.sameRegion(track.box) }
        }.map { LocatedObject(null, it.box, it.visualRegion) }
        // Leave room for an unfamiliar object even when recognized objects exist.
        val selectedNames = recognized.take(if (unknown.isNotEmpty()) 2 else 3)
        val selectedUnknown = unknown.take(3 - selectedNames.size)
        val pending = names.any { it.count < requiredSamples(it.observation.label) } ||
            regions.any { it.count < 2 }
        return RecognitionResult(selectedNames + selectedUnknown, pending)
    }

    companion object {
        // Initial precision-oriented defaults; scores are not calibrated probabilities.
        fun minimumScore(label: String): Float = if (label == "toilet") 0.9f else 0.75f
        fun requiredSamples(label: String): Int = if (label == "toilet") 3 else 2
    }
}
