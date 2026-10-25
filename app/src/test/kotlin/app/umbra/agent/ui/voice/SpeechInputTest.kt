package app.umbra.agent.ui.voice

import android.speech.RecognizerIntent
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechInputTest {
    @Test fun microphoneAndRecordedNotesDetectAndSwitchWithoutRestrictingLanguages() {
        for (onDevice in listOf(false, true)) {
            val extras = SpeechInput.extras(36, "app.umbra.agent", !onDevice, onDevice)
            assertEquals(true, extras[RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION])
            assertEquals(RecognizerIntent.LANGUAGE_SWITCH_BALANCED, extras[RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH])
            assertFalse(RecognizerIntent.EXTRA_LANGUAGE in extras)
            assertFalse(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES in extras)
            assertFalse(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES in extras)
            assertEquals(!onDevice, extras[RecognizerIntent.EXTRA_PARTIAL_RESULTS])
            assertEquals(onDevice, extras[RecognizerIntent.EXTRA_PREFER_OFFLINE] == true)
        }
    }

    @Test fun displayLocaleNeverBecomesARecognitionLanguage() {
        val previous = Locale.getDefault()
        try {
            val expected = SpeechInput.extras(36, "app.umbra.agent", true, false)
            for (tag in listOf("en-US", "zh-TW", "ar", "hi-IN", "ja-JP", "sw-KE")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(expected, SpeechInput.extras(36, "app.umbra.agent", true, false), tag)
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun oldServicesKeepFreeFormInputWithoutUnsupportedFlags() {
        val extras = SpeechInput.extras(33, "app.umbra.agent", false, true)
        assertFalse(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION in extras)
        assertFalse(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH in extras)
        assertFalse(RecognizerIntent.EXTRA_LANGUAGE in extras)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, extras[RecognizerIntent.EXTRA_LANGUAGE_MODEL])
        assertTrue(extras[RecognizerIntent.EXTRA_PREFER_OFFLINE] == true)
    }
}
