package app.umbra.agent.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.IconSpec
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.porcelain.pcHue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sin

/**
 * Listening through the phone's speech recognizer, without its dialog: Umbra shows the words as they come and the
 * voice as it is heard. One listener per panel; [start] and [cancel] are called on the main thread.
 */
class VoiceListener(private val context: Context) {
    sealed interface State {
        data object Idle : State
        /** Hearing; [heard] is what was recognised so far (it can still change). */
        data class Listening(val heard: String) : State
        /** Speech ended; the recognizer settles the final words. */
        data class Settling(val heard: String) : State
        data class Heard(val text: String) : State
        data class Failed(val message: Int) : State
    }

    private val mutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutable.asStateFlow()

    /** How loud the voice is now, 0..1. */
    private val mutableLevel = MutableStateFlow(0f)
    val level: StateFlow<Float> = mutableLevel.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        release()
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null || !SpeechRecognizer.isRecognitionAvailable(context)) {
            mutable.value = State.Failed(R.string.r_voice_unavailable)
            return
        }
        recognizer = r
        mutable.value = State.Listening("")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // The recognizer reports roughly -2 dB (silence) to 10 dB (a raised voice).
                mutableLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                mutableLevel.value = 0f
                mutable.value = State.Settling(heardSoFar())
            }
            override fun onError(error: Int) {
                mutableLevel.value = 0f
                val heard = heardSoFar()
                // A pause after some words is the end of what was said, not a failure.
                mutable.value = if (heard.isNotBlank() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) State.Heard(heard)
                else State.Failed(messageFor(error))
                release()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { heardSoFar() }
                mutableLevel.value = 0f
                mutable.value = if (text.isBlank()) State.Failed(R.string.r_voice_nothing) else State.Heard(text)
                release()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (text.isNotBlank()) mutable.value = when (mutable.value) {
                    is State.Settling -> State.Settling(text)
                    else -> State.Listening(text)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Stops hearing now; what was said so far becomes the result. */
    fun finish() {
        val now = mutable.value
        if (now is State.Listening) {
            mutable.value = State.Settling(now.heard)
            recognizer?.stopListening()
        }
    }

    fun cancel() {
        recognizer?.cancel()
        release()
        mutableLevel.value = 0f
        mutable.value = State.Idle
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun heardSoFar(): String = when (val s = mutable.value) {
        is State.Listening -> s.heard
        is State.Settling -> s.heard
        else -> ""
    }

    private fun messageFor(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.r_voice_nothing
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.r_voice_needs_mic
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> R.string.r_voice_offline
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.r_voice_language
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.r_voice_busy
        else -> R.string.r_voice_stopped
    }
}

/**
 * Umbra's listening face: the words appear as they are said, in the display type; under them the voice moves a
 * field of soft bars (breathing gently while it is quiet). Cancel on the left, Done on the right. The microphone
 * permission is asked here the first time. [onHeard] gets the final words; [onClose] ends the panel either way.
 */
@Composable
fun VoicePanel(onHeard: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val listener = remember { VoiceListener(context.applicationContext) }
    val state by listener.state.collectAsStateWithLifecycle()
    val level by listener.level.collectAsStateWithLifecycle()
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listener.start() else listener.cancel().also { onClose() }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listener.start()
        else if (!asked) { asked = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    LaunchedEffect(state) {
        (state as? VoiceListener.State.Heard)?.let { onHeard(it.text); onClose() }
    }
    DisposableEffect(listener) { onDispose { listener.cancel() } }

    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .shadow(14.dp, shape, ambientColor = pcHue(0x26455F49), spotColor = pcHue(0x40455F49))
            .clip(shape).background(pc(0xFFF6F8F2)).border(1.dp, P.line, shape)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val s = state
        val words = when (s) {
            is VoiceListener.State.Listening -> s.heard
            is VoiceListener.State.Settling -> s.heard
            else -> ""
        }
        val listeningLabel = stringResource(R.string.r_voice_listening)
        AnimatedContent(
            targetState = (s as? VoiceListener.State.Failed)?.message,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "voice-words",
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) { failure ->
            if (failure != null) Text(stringResource(failure), fontSize = 17.sp, lineHeight = 24.sp, color = P.danger, fontFamily = P.sans)
            else Text(
                words.ifEmpty { listeningLabel },
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 22.sp, lineHeight = 30.sp, fontFamily = P.serif,
                color = if (words.isEmpty()) P.muted else P.ink, maxLines = 5,
            )
        }
        Voiceprint(level, active = s is VoiceListener.State.Listening, settling = s is VoiceListener.State.Settling, Modifier.fillMaxWidth().height(56.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            VoiceButton(PIcons.close, stringResource(R.string.action_cancel), filled = false) { listener.cancel(); onClose() }
            if (s is VoiceListener.State.Failed) VoiceButton(PIcons.mic, stringResource(R.string.r_voice_again), filled = true) { listener.start() }
            else VoiceButton(PIcons.check, stringResource(R.string.r_voice_done), filled = true, enabled = s is VoiceListener.State.Listening) { listener.finish() }
        }
    }
}

/**
 * The voice, drawn: a row of rounded bars around a still centre line. Each bar follows the loudness a moment after
 * its neighbour, so a word rolls outward from the middle; quiet, they breathe; settling, they shimmer as the words land.
 */
@Composable
private fun Voiceprint(level: Float, active: Boolean, settling: Boolean, modifier: Modifier) {
    val bars = 29
    val heights = remember { FloatArray(bars) }
    var time by remember { mutableFloatStateOf(0f) }
    var smooth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withInfiniteAnimationFrameMillis { ms ->
            val dt = if (last == 0L) 16f else (ms - last).coerceIn(1, 64).toFloat()
            last = ms
            time += dt / 1000f
            // the loudness rises quickly and falls back slowly, as a meter does
            val target = if (active) level else 0f
            smooth += (target - smooth) * (if (target > smooth) 0.35f else 0.08f)
            for (i in 0 until bars) {
                val fromCentre = abs(i - bars / 2) / (bars / 2f)
                val wave = 0.5f + 0.5f * sin(time * 6.2f - fromCentre * 5.5f + i * 0.9f)
                val voiced = smooth * (1f - fromCentre * 0.55f) * (0.55f + 0.45f * wave)
                val breath = 0.10f + 0.05f * sin(time * 2.1f + i * 0.45f)
                val shimmer = if (settling) 0.18f + 0.14f * sin(time * 9f + i * 0.7f) else 0f
                val goal = maxOf(voiced, breath, shimmer)
                heights[i] += (goal - heights[i]) * 0.3f
            }
        }
    }
    val from = P.accent
    val to = P.accent2
    Canvas(modifier) {
        // reading the clock here redraws the bars every frame
        if (time < 0f) return@Canvas
        val gap = size.width / bars
        val w = (gap * 0.46f).coerceAtMost(7.dp.toPx())
        val brush = Brush.verticalGradient(listOf(from, to, from))
        for (i in 0 until bars) {
            val h = (heights[i].coerceIn(0f, 1f) * size.height).coerceAtLeast(w)
            val x = gap * i + (gap - w) / 2f
            drawRoundRect(brush, Offset(x, (size.height - h) / 2f), Size(w, h), CornerRadius(w / 2f, w / 2f), alpha = 0.55f + 0.45f * heights[i].coerceIn(0f, 1f))
        }
    }
}

@Composable
private fun VoiceButton(icon: IconSpec, label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (filled) P.accent2.copy(alpha = if (enabled) 1f else 0.4f) else P.base2)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { PIcon(icon, size = 22.dp, tint = if (filled) P.base else P.ink) }
}

/** Whether this phone has a speech recognizer at all (the microphone shows only then). */
fun canListen(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
