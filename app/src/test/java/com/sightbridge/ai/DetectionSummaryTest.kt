package com.sightbridge.ai

import org.junit.Assert.*
import org.junit.Test

class DetectionSummaryTest {
    @Test fun reportsHorizontalPositionWithoutDistanceClaims() {
        assertEquals("person on the left; chair in the center; bottle on the right", DetectionSummary.describe(listOf(
            DetectedObject("person", .9f, .1f), DetectedObject("chair", .8f, .5f), DetectedObject("bottle", .7f, .9f))))
    }
    @Test fun filtersUncertainResultsAndDeduplicatesSpeech() {
        assertEquals("person in the center", DetectionSummary.describe(listOf(
            DetectedObject("person", .9f, .5f), DetectedObject("person", .8f, .5f), DetectedObject("car", .3f, .1f))))
    }
    @Test fun emptyResultsDoNotImplySafety() {
        assertEquals("No recognized objects in this frame", DetectionSummary.describe(emptyList()))
    }
}
