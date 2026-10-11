package com.sightbridge.ai

object DetectionSummary {
    fun describe(objects: List<LocatedObject>): String {
        val descriptions = objects.filter { it.box.valid }.map {
            val horizontal = when {
                it.box.centerX < 1f / 3 -> "left"
                it.box.centerX > 2f / 3 -> "right"
                else -> "center"
            }
            val position = when {
                it.box.centerY < 1f / 3 -> "in the upper $horizontal"
                it.box.centerY > 2f / 3 -> "in the lower $horizontal"
                horizontal == "center" -> "in the center"
                else -> "on the $horizontal"
            }
            val label = it.label ?: if (it.visualRegion) "Unidentified region" else "Unrecognized object"
            "$label $position"
        }.distinct().take(3)
        return if (descriptions.isEmpty()) "No reliable detections in this frame" else descriptions.joinToString("; ")
    }
}
