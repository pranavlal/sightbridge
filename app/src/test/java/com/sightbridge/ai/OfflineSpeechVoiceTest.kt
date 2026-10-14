package com.sightbridge.ai

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OfflineSpeechVoiceTest {
    @Test fun rejectsMissingAndNetworkDefaultVoices() {
        assertFalse(canSpeakOfflineWith(null))
        val networkVoice = mock(Voice::class.java)
        `when`(networkVoice.isNetworkConnectionRequired).thenReturn(true)
        assertFalse(canSpeakOfflineWith(networkVoice))
    }

    @Test fun requiresTheSelectedVoiceToBeDownloaded() {
        val unavailableVoice = mock(Voice::class.java)
        `when`(unavailableVoice.features).thenReturn(setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        assertFalse(canSpeakOfflineWith(unavailableVoice))

        val localVoice = mock(Voice::class.java)
        `when`(localVoice.features).thenReturn(emptySet())
        assertTrue(canSpeakOfflineWith(localVoice))
    }
}
