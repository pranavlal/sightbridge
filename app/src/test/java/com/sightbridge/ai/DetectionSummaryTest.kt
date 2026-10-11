package com.sightbridge.ai

import org.junit.Assert.*
import org.junit.Test

class DetectionSummaryTest {
    private fun box(x: Float, y: Float = .5f) = ObjectBox(x - .05f, y - .05f, x + .05f, y + .05f)

    @Test fun reportsNamedAndUnknownPositionsWithoutDistanceClaims() {
        assertEquals("chair on the left; Unrecognized object in the lower right",
            DetectionSummary.describe(listOf(LocatedObject("chair", box(.1f)), LocatedObject(null, box(.9f, .9f)))))
    }

    @Test fun deduplicatesBeforeLimitingSpeechAndFiltersInvalidBoxes() {
        assertEquals("person in the center; chair on the right", DetectionSummary.describe(listOf(
            LocatedObject("person", box(.5f)), LocatedObject("person", box(.5f)),
            LocatedObject("person", box(.5f)), LocatedObject("chair", box(.9f)),
            LocatedObject(null, ObjectBox(Float.NaN, 0f, 1f, 1f)))))
    }

    @Test fun emptyResultsDoNotClaimAnEmptyOrSafeScene() {
        assertEquals("No reliable detections in this frame", DetectionSummary.describe(emptyList()))
    }
}
