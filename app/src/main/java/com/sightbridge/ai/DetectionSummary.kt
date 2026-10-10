package com.sightbridge.ai

data class DetectedObject(val label: String, val confidence: Float, val centerX: Float)

object DetectionSummary {
    fun describe(objects: List<DetectedObject>): String {
        val descriptions = objects.filter { it.confidence >= 0.5f && it.centerX.isFinite() }
            .sortedByDescending { it.confidence }.take(3).map {
                val position = when {
                    it.centerX < 1f / 3 -> "on the left"
                    it.centerX > 2f / 3 -> "on the right"
                    else -> "in the center"
                }
                "${it.label} $position"
            }.distinct()
        return if (descriptions.isEmpty()) "No recognized objects in this frame" else descriptions.joinToString("; ")
    }
}
