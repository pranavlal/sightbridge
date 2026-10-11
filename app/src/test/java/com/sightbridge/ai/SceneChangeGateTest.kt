package com.sightbridge.ai

import org.junit.Assert.*
import org.junit.Test
import com.sightbridge.ai.SceneChangeGate.Decision.*

class SceneChangeGateTest {
    private fun scene(value: Int = 80) = IntArray(SceneChangeGate.WIDTH * SceneChangeGate.HEIGHT) { value }
    private fun ready() = SceneChangeGate().apply { markAnalyzed(scene(), "camera", 100) }

    @Test fun firstFrameAlwaysRunsAndResetForgetsThePreviousScene() {
        val gate = ready()
        gate.reset()
        assertEquals(INITIAL, gate.evaluate(scene(), "camera", 101))
    }
    @Test fun staticSceneSkipsUntilTheRefreshDeadline() {
        val gate = ready()
        assertEquals(UNCHANGED, gate.evaluate(scene(), "camera", 1100))
        assertEquals(UNCHANGED, gate.evaluate(scene(), "camera", 5099))
        assertEquals(PERIODIC_REFRESH, gate.evaluate(scene(), "camera", 5100))
    }
    @Test fun toleratesNoiseAndSmallExposureChanges() {
        val noisy = scene().mapIndexed { index, value -> value + 4 + if (index % 2 == 0) 2 else -2 }.toIntArray()
        assertEquals(UNCHANGED, ready().evaluate(noisy, "camera", 1100))
    }
    @Test fun catchesSmallLocalizedChanges() {
        val changed = scene()
        for (y in 8 until 12) for (x in 8 until 12) changed[y * SceneChangeGate.WIDTH + x] = 208
        assertEquals(SCENE_CHANGE, ready().evaluate(changed, "camera", 1100))
    }
    @Test fun gradualChangeAccumulatesAgainstTheAnalyzedReference() {
        val gate = ready()
        assertEquals(UNCHANGED, gate.evaluate(scene(86), "camera", 1100))
        assertEquals(UNCHANGED, gate.evaluate(scene(92), "camera", 2100))
        assertEquals(SCENE_CHANGE, gate.evaluate(scene(100), "camera", 3100))
    }
    @Test fun rateLimitBoundsInferenceEvenWithContinuousMotion() {
        assertEquals(RATE_LIMITED, ready().evaluate(scene(200), "camera", 1099))
        assertEquals(SCENE_CHANGE, ready().evaluate(scene(200), "camera", 1100))
    }
    @Test fun changedGeometryTriggersFreshInference() {
        assertEquals(SCENE_CHANGE, ready().evaluate(scene(), "rotated-camera", 1100))
    }
    @Test fun successfulInferenceUpdatesReferenceAndRefreshTime() {
        val gate = ready()
        gate.markAnalyzed(scene(200), "camera", 1100)
        assertEquals(UNCHANGED, gate.evaluate(scene(200), "camera", 2100))
        assertEquals(PERIODIC_REFRESH, gate.evaluate(scene(200), "camera", 6100))
    }
    @Test fun confirmationCanAnalyzeAStaticSceneButStillRespectsTheRateLimit() {
        val gate = ready()
        assertEquals(RATE_LIMITED, gate.evaluate(scene(), "camera", 1099, confirmationNeeded = true))
        assertEquals(CONFIRMATION_CHECK, gate.evaluate(scene(), "camera", 1100, confirmationNeeded = true))
        gate.markAnalyzed(scene(), "camera", 1100)
        assertEquals(UNCHANGED, gate.evaluate(scene(), "camera", 2100))
    }
}
