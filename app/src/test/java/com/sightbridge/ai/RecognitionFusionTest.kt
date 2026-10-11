package com.sightbridge.ai

import org.junit.Assert.*
import org.junit.Test

class RecognitionFusionTest {
    private val left = ObjectBox(.05f, .35f, .25f, .65f)
    private val right = ObjectBox(.75f, .35f, .95f, .65f)
    private fun chair(box: ObjectBox = left, score: Float = .85f) = RecognitionObservation("chair", score, box)

    @Test fun unknownRegionsWorkWithoutAnyRecognizedClass() {
        val fusion = RecognitionFusion()
        assertTrue(fusion.update(emptyList(), listOf(right), 0, "usb").objects.isEmpty())
        val result = fusion.update(emptyList(), listOf(right), 1000, "usb")
        assertEquals(listOf(LocatedObject(null, right)), result.objects)
        assertFalse(result.needsConfirmation)
    }

    @Test fun lowClassScoresNeverMasqueradeAsObjectness() {
        val fusion = RecognitionFusion()
        repeat(3) { assertTrue(fusion.update(listOf(chair(score = .55f)), emptyList(), it * 1000L, "usb").objects.isEmpty()) }
        assertEquals("No reliable detections in this frame", DetectionSummary.describe(emptyList()))
    }

    @Test fun namedAndUnrecognizedObjectsCanBeReportedTogether() {
        val fusion = RecognitionFusion()
        fusion.update(listOf(chair()), listOf(left, right), 0, "usb")
        val result = fusion.update(listOf(chair()), listOf(left, right), 1000, "usb")
        assertEquals(listOf(LocatedObject("chair", left), LocatedObject(null, right)), result.objects)
        assertEquals("chair on the left; Unrecognized object on the right", DetectionSummary.describe(result.objects))
    }

    @Test fun chairToiletFlipsRemainUnnamedRatherThanGuessing() {
        val fusion = RecognitionFusion()
        listOf("chair", "toilet", "chair", "toilet").forEachIndexed { index, label ->
            val result = fusion.update(listOf(RecognitionObservation(label, .95f, left)), listOf(left), index * 1000L, "usb")
            assertTrue(result.objects.all { it.label == null })
        }
    }

    @Test fun toiletRequiresBothAHigherScoreAndThreeMatchingObservations() {
        val fusion = RecognitionFusion()
        repeat(3) {
            assertTrue(fusion.update(listOf(RecognitionObservation("toilet", .85f, left)), emptyList(), it * 1000L, "usb").objects.isEmpty())
        }
        repeat(2) {
            assertTrue(fusion.update(listOf(RecognitionObservation("toilet", .95f, left)), emptyList(), 3000 + it * 1000L, "usb").objects.isEmpty())
        }
        assertEquals("toilet", fusion.update(listOf(RecognitionObservation("toilet", .95f, left)), emptyList(), 5000, "usb").objects.single().label)
    }

    @Test fun twoDifferentChairsCannotConfirmEachOther() {
        val fusion = RecognitionFusion()
        fusion.update(listOf(chair(left)), emptyList(), 0, "usb")
        assertTrue(fusion.update(listOf(chair(right)), emptyList(), 1000, "usb").objects.isEmpty())
    }

    @Test fun observationsMustBeConsecutiveAndExpireAfterLongGaps() {
        val fusion = RecognitionFusion()
        fusion.update(listOf(chair()), listOf(left), 0, "usb")
        fusion.update(emptyList(), emptyList(), 1000, "usb")
        assertTrue(fusion.update(listOf(chair()), listOf(left), 2000, "usb").objects.isEmpty())
        assertEquals("chair", fusion.update(listOf(chair()), listOf(left), 3000, "usb").objects.single().label)
        assertTrue(fusion.update(listOf(chair()), listOf(left), 12000, "usb").objects.isEmpty())
    }

    @Test fun geometryChangesAndResetDropPreviousConfirmations() {
        val fusion = RecognitionFusion()
        fusion.update(listOf(chair()), listOf(left), 0, "usb")
        assertTrue(fusion.update(listOf(chair()), listOf(left), 1000, "phone").objects.isEmpty())
        fusion.reset()
        assertTrue(fusion.update(listOf(chair()), listOf(left), 2000, "phone").objects.isEmpty())
    }

    @Test fun overlappingGenericBoxesAreDeduplicatedButDifferentObjectsAreKept() {
        val fusion = RecognitionFusion()
        val nested = ObjectBox(.07f, .37f, .23f, .63f)
        repeat(2) { fusion.update(emptyList(), listOf(left, nested, right), it * 1000L, "usb") }
        val result = fusion.update(emptyList(), listOf(left, nested, right), 2000, "usb")
        assertEquals(2, result.objects.size)
    }

    @Test fun smallKnownObjectDoesNotHideALargerUnknownRegion() {
        val fusion = RecognitionFusion()
        val large = ObjectBox(.01f, .1f, .8f, .9f)
        fusion.update(listOf(chair()), listOf(large), 0, "usb")
        val result = fusion.update(listOf(chair()), listOf(large), 1000, "usb")
        assertEquals(2, result.objects.size)
    }

    @Test fun leavesASpeechSlotForUnknownObjectsAndRejectsInvalidInput() {
        val fusion = RecognitionFusion()
        val center = ObjectBox(.4f, .35f, .6f, .65f)
        val lower = ObjectBox(.4f, .75f, .6f, .95f)
        val observations = listOf(chair(), RecognitionObservation("person", .9f, center),
            RecognitionObservation("bottle", .8f, right), RecognitionObservation("bad", Float.NaN, left))
        val boxes = listOf(lower, ObjectBox(Float.NaN, 0f, .5f, .5f), ObjectBox(0f, 0f, 1f, 1f))
        fusion.update(observations, boxes, 0, "usb")
        val result = fusion.update(observations, boxes, 1000, "usb")
        assertEquals(3, result.objects.size)
        assertNull(result.objects.last().label)
    }
    @Test fun segmentationProducesARegionCueAndDoesNotClaimARecognizedObject() {
        val fusion = RecognitionFusion()
        fusion.update(emptyList(), emptyList(), 0, "usb", listOf(left))
        val result = fusion.update(emptyList(), emptyList(), 1000, "usb", listOf(left))
        assertEquals("Unidentified region on the left", DetectionSummary.describe(result.objects))
        assertTrue(result.objects.single().visualRegion)
    }
    @Test fun segmentationDoesNotDuplicateAnObjectDetectorBox() {
        val fusion = RecognitionFusion()
        fusion.update(emptyList(), listOf(left), 0, "usb", listOf(left))
        val result = fusion.update(emptyList(), listOf(left), 1000, "usb", listOf(left))
        assertEquals(1, result.objects.size)
        assertFalse(result.objects.single().visualRegion)
    }
}
