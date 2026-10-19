package app.umbra.agent.telegram

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A voice note in words, on the phone: the note (Telegram's Ogg/Opus) is decoded with the phone's own codecs and handed
 * to its speech recognizer as audio (Android 13's EXTRA_AUDIO_SOURCE; on-device when the phone has that). Nothing
 * leaves the phone. Null when the phone cannot do it.
 */
class VoiceTranscriber(private val context: Context) {
    suspend fun transcribe(audio: ByteArray): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        val pcm = withContext(Dispatchers.IO) { decode(audio) } ?: return null
        return withTimeoutOrNull(45_000) { recognize(pcm) }
    }

    /** Decodes to 16 kHz mono 16-bit PCM. */
    private fun decode(audio: ByteArray): ShortArray? {
        val input = File.createTempFile("voice", ".ogg", context.cacheDir)
        try {
            input.writeBytes(audio)
            val extractor = MediaExtractor()
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = java.io.ByteArrayOutputStream()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        val buffer = codec.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(chunk)
                        out.write(chunk)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            codec.stop(); codec.release(); extractor.release()
            val shorts = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = shorts.remaining() / channels.coerceAtLeast(1)
            val mono = ShortArray(frames) { f ->
                var sum = 0
                for (c in 0 until channels) sum += shorts.get(f * channels + c)
                (sum / channels).toShort()
            }
            // Linear resampling to 16 kHz.
            if (rate == TARGET_RATE) return mono
            val n = (mono.size.toLong() * TARGET_RATE / rate).toInt()
            return ShortArray(n) { i ->
                val x = i.toDouble() * rate / TARGET_RATE
                val a = x.toInt().coerceAtMost(mono.lastIndex)
                val b = (a + 1).coerceAtMost(mono.lastIndex)
                val t = x - a
                (mono[a] * (1 - t) + mono[b] * t).toInt().toShort()
            }
        } catch (_: Exception) {
            return null
        } finally {
            input.delete()
        }
    }

    private suspend fun recognize(pcm: ShortArray): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            val pipe = ParcelFileDescriptor.createPipe()
            val done = { text: String? ->
                runCatching { recognizer.destroy() }
                runCatching { pipe[0].close() }
                if (cont.isActive) cont.resume(text)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) = done(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onError(error: Int) = done(null)
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
            recognizer.startListening(intent)
            // The audio goes into the pipe from another thread; closing it ends the speech.
            Thread {
                runCatching {
                    FileOutputStream(pipe[1].fileDescriptor).use { stream ->
                        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        pcm.forEach { bytes.putShort(it) }
                        stream.write(bytes.array())
                    }
                }
                runCatching { pipe[1].close() }
            }.start()
            cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
        }
    }

    private companion object {
        const val TARGET_RATE = 16_000
    }
}
