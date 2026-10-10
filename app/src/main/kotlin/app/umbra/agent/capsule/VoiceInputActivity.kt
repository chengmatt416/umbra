package app.umbra.agent.capsule

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import app.umbra.agent.container

/**
 * Speech for the Quick Composer, which lives in an overlay window and cannot ask for an activity result itself. This
 * see-through activity asks the phone's recognizer, puts the words into the shared draft and goes away: the composer
 * that asked stays where it is (§3.1, the microphone belongs to the composer that triggered it).
 */
class VoiceInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                REQUEST,
            )
        } catch (_: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (heard.isNotBlank()) {
                val ui = container.ui
                ui.saveDraft(listOf(ui.draft.value, heard).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST = 7
    }
}
