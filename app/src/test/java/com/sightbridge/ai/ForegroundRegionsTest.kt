package com.sightbridge.ai

import org.junit.Assert.*
import org.junit.Test

class ForegroundRegionsTest {
    @Test fun rejectsBlankWeakAndFullFrameMasks() {
        listOf(0f, .69f, .78f, .95f).forEach {
            assertTrue(ForegroundRegions.extract(FloatArray(10000) { _ -> it }, 100, 100).isEmpty())
        }
    }

    @Test fun reportsSeparateCoherentForegroundComponents() {
        val mask = FloatArray(10000)
        for (y in 20 until 60) for (x in 5 until 25) mask[y * 100 + x] = .95f
        for (y in 70 until 90) for (x in 75 until 95) mask[y * 100 + x] = .95f
        assertEquals(listOf(ObjectBox(.05f, .2f, .25f, .6f), ObjectBox(.75f, .7f, .95f, .9f)),
            ForegroundRegions.extract(mask, 100, 100))
    }

    @Test fun rejectsSpecklesAndSparseOutlines() {
        val mask = FloatArray(10000)
        mask[10] = Float.NaN
        for (y in 5 until 95) {
            mask[y * 100 + 5] = .95f
            mask[y * 100 + 94] = .95f
        }
        for (x in 5 until 95) {
            mask[5 * 100 + x] = .95f
            mask[94 * 100 + x] = .95f
        }
        assertTrue(ForegroundRegions.extract(mask, 100, 100).isEmpty())
    }
}
