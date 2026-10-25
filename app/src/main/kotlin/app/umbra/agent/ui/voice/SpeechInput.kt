package app.umbra.agent.ui.voice

import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent

/** Both microphone input and recorded notes detect any language the speech service supports. */
internal object SpeechInput {
    fun intent(packageName: String, partialResults: Boolean, onDevice: Boolean = false): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            for ((key, value) in extras(Build.VERSION.SDK_INT, packageName, partialResults, onDevice)) {
                when (value) {
                    is String -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                }
            }
        }

    internal fun extras(sdk: Int, packageName: String, partialResults: Boolean, onDevice: Boolean): Map<String, Any> = buildMap {
        put(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        put(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
        put(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        if (onDevice) put(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (sdk >= 34) {
            put(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            put(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
        }
        // No locale or allowed-language list: the app's display language must not constrain speech.
    }
}
