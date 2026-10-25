package app.umbra.agent.telegram

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import app.umbra.agent.ui.voice.SpeechInput
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
        if (audio.isEmpty() || audio.size > MAX_AUDIO_BYTES) return null
        val pcm = withContext(Dispatchers.IO) { decode(audio) } ?: return null
        return withTimeoutOrNull(45_000) { recognize(pcm) }
    }

    /** Decodes to 16 kHz mono 16-bit PCM. */
    private suspend fun decode(audio: ByteArray): ShortArray? {
        val input = File.createTempFile("voice", ".ogg", context.cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            input.writeBytes(audio)
            extractor.setDataSource(input.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val out = java.io.ByteArrayOutputStream()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!outputDone) {
                coroutineContext.ensureActive()
                check(SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out" }
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) || output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    }
                    o >= 0 -> {
                        require(info.size >= 0 && out.size().toLong() + info.size <= MAX_PCM_BYTES)
                        val buffer = decoder.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(chunk)
                        out.write(chunk)
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            require(rate in 8000..192000 && channels in 1..8)
            val shorts = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = shorts.remaining() / channels
            require(frames > 0 && frames.toLong() * 1000 / rate <= MAX_DURATION_MS)
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            input.delete()
        }
    }

    private suspend fun recognize(pcm: ShortArray): String? = withContext(Dispatchers.Main) {
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return@withContext null
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (_: Exception) {
            recognizer.destroy()
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            val main = Handler(Looper.getMainLooper())
            val cleanup = {
                runCatching { pipe[0].close() }
                runCatching { pipe[1].close() }
                if (Looper.myLooper() == Looper.getMainLooper()) runCatching { recognizer.destroy() }
                else main.post { runCatching { recognizer.destroy() } }
                Unit
            }
            val done = { text: String? ->
                if (finished.compareAndSet(false, true)) {
                    cleanup()
                    if (cont.isActive) cont.resume(text?.trim()?.takeIf { it.isNotEmpty() })
                }
            }
            cont.invokeOnCancellation { if (finished.compareAndSet(false, true)) cleanup() }
            if (finished.get()) return@suspendCancellableCoroutine
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
            val intent = SpeechInput.intent(context.packageName, partialResults = false, onDevice = true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
            try { recognizer.startListening(intent) } catch (_: RuntimeException) { done(null) }
            if (finished.get()) return@suspendCancellableCoroutine
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
            }.apply { name = "umbra-voice-audio"; isDaemon = true }.start()
        }
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val MAX_AUDIO_BYTES = 20_000_000
        const val MAX_PCM_BYTES = 24_000_000
        const val MAX_DURATION_MS = 120_000
    }
}
