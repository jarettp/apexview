package app.apexview

import android.content.ComponentName
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
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The recogniser wants the microphone permission, even for a file. */
class SpeechPermissionNeeded : Exception("speech recognition needs the microphone permission")

/** The recogniser gave up on a clip, with Android's error code. */
class RecogniserError(val code: Int) : IOException("speech recogniser error $code (${RecogniserError.name(code)})") {
    companion object {
        fun name(code: Int) = when (code) {
            1, 2 -> "network"
            3 -> "audio"
            4 -> "server"
            5 -> "client"
            10 -> "too many requests"
            11 -> "server disconnected"
            12 -> "language not supported"
            13 -> "language not installed"
            14 -> "can't check support"
            else -> "code $code"
        }
    }
}

/**
 * Turns team radio clips into text with the phone's own speech recogniser (Android 13 and up,
 * where it can read audio from a file). A clip is downloaded, decoded from MP3 to 16 kHz mono
 * PCM, and fed to the recogniser as one segmented session, so it reads the whole clip rather
 * than stopping at the first pause.
 *
 * Radio is in English whatever the phone's language, so English is asked for. The on-device
 * recogniser is used when it has English; if it hasn't, its English is downloaded for next time
 * and the phone's regular speech service (Google's, where there is one) does the work meanwhile.
 * Phones whose on-device recogniser fails outright fall back to that service the same way.
 */
class RadioTranscriber(private val context: Context) {
    private val http = OkHttpClient()
    private val work = File(context.cacheDir, "radio").apply { mkdirs() }

    private val onDevice = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    /**
     * The phone's regular speech service: Google's speech services where installed (most phones
     * with Google apps have them, whatever the maker), else whatever the phone has set as default.
     */
    private val service: ComponentName? = if (Build.VERSION.SDK_INT < 33) null else speechServices().let { all ->
        GOOGLE_SPEECH.firstNotNullOfOrNull { pkg -> all.firstOrNull { it.packageName == pkg } }
    }
    private val hasService = Build.VERSION.SDK_INT >= 33 && (service != null || SpeechRecognizer.isRecognitionAvailable(context))
    val available: Boolean = onDevice || hasService
    /** On-device until it turns out to have no English, or to fail. */
    private var useOnDevice = onDevice
    private var checkedOnDevice = false
    /** The English the recogniser is asked for: the on-device one's own English if it has one. */
    private var language = "en-US"

    private fun speechServices(): List<ComponentName> =
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }

    /**
     * Once, before the first clip: does the on-device recogniser have English? If not, ask it to
     * download English, and use the regular service until then.
     */
    private suspend fun checkOnDevice() = withContext(Dispatchers.Main) {
        if (checkedOnDevice || !useOnDevice) return@withContext
        checkedOnDevice = true
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        val support = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<RecognitionSupport?> { cont ->
                recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) { if (cont.isActive) cont.resume(support) }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        }
        if (support != null) {
            val english = support.installedOnDeviceLanguages.let { installed ->
                installed.firstOrNull { it.equals("en-US", ignoreCase = true) } ?: installed.firstOrNull { it.startsWith("en", ignoreCase = true) }
            }
            val reported = support.installedOnDeviceLanguages.isNotEmpty() || support.supportedOnDeviceLanguages.isNotEmpty()
            if (english != null) language = english
            // A recogniser that reports nothing at all is left to try; one that lists languages without English isn't.
            else if (reported) {
                if ((support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages).any { it.startsWith("en", ignoreCase = true) }) {
                    runCatching { recognizer.triggerModelDownload(intent) }
                }
                if (hasService) useOnDevice = false
            }
        }
        recognizer.destroy()
    }

    /**
     * [hints] are words likely in the clip (names, teams, radio talk); the recogniser leans
     * toward them.
     */
    suspend fun transcribe(url: String, hints: List<String>): String {
        val pcm = withContext(Dispatchers.IO) {
            val mp3 = File(work, "clip.mp3")
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                mp3.writeBytes(r.body?.bytes() ?: throw IOException("empty clip"))
            }
            File(work, "clip.pcm").also { it.writeBytes(decodeToPcm16k(mp3)) }
        }
        checkOnDevice()
        return withTimeout(RECOGNISE_TIMEOUT_MS) {
            try {
                recognise(pcm, hints)
            } catch (e: RecogniserError) {
                // The on-device recogniser failed: the regular service takes over from here.
                if (!useOnDevice || !hasService) throw e
                useOnDevice = false
                language = "en-US"
                recognise(pcm, hints)
            }
        }
    }

    /** MP3 to 16-bit mono PCM at 16 kHz, little-endian. */
    private fun decodeToPcm16k(file: File): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("no audio in clip")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var samples = ShortArray(rate * 20)
        var count = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
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
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        // Down to mono: average the channels.
                        val frames = info.size / 2 / channels
                        for (f in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += out.get(f * channels + c)
                            if (count == samples.size) samples = samples.copyOf(count * 2)
                            samples[count++] = (sum / channels).toShort()
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        // Even out the level: the loudest point to about 90% of full scale.
        var peak = 1
        for (i in 0 until count) peak = maxOf(peak, kotlin.math.abs(samples[i].toInt()))
        val gain = (0.9 * Short.MAX_VALUE / peak).coerceIn(0.5, 8.0)
        for (i in 0 until count) samples[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        // Linear resample to 16 kHz.
        if (count == 0) throw IOException("silent clip")
        val outCount = (count.toLong() * TARGET_RATE / rate).toInt()
        val bytes = ByteBuffer.allocate(outCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outCount) {
            val pos = i.toDouble() * rate / TARGET_RATE
            val k = pos.toInt().coerceAtMost(count - 1)
            val next = (k + 1).coerceAtMost(count - 1)
            val frac = pos - k
            bytes.putShort((samples[k] * (1 - frac) + samples[next] * frac).toInt().toShort())
        }
        return bytes.array()
    }

    /** One recogniser session over the whole file, collecting every segment it hears. */
    private suspend fun recognise(pcm: File, hints: List<String>): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context, service)
            val source = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
            val parts = mutableListOf<String>()
            var done = false
            fun finish(error: Exception?) {
                if (done) return
                done = true
                recognizer.destroy()
                source.close()
                if (error != null) cont.resumeWithException(error) else cont.resume(parts.joinToString(" ").trim())
            }
            fun take(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(parts::add)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) = take(segmentResults)
                override fun onEndOfSegmentedSession() = finish(null)
                override fun onResults(results: Bundle?) {
                    take(results)
                    finish(null)
                }
                override fun onError(error: Int) = when (error) {
                    // Nothing (more) to understand: whatever was heard is the transcript.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(null)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish(SpeechPermissionNeeded())
                    else -> finish(RecogniserError(error))
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Radio is in English, whatever language the phone is set to.
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
                // The session runs until the file ends, not until the first pause.
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.take(MAX_HINTS)))
            recognizer.startListening(intent)
            cont.invokeOnCancellation {
                if (!done) {
                    done = true
                    recognizer.destroy()
                    source.close()
                }
            }
        }
    }

    companion object {
        private const val TARGET_RATE = 16_000
        private const val RECOGNISE_TIMEOUT_MS = 45_000L
        private const val MAX_HINTS = 100
        /** Google's speech recognisers, best first: Speech Services, the Google app, Android System Intelligence. */
        private val GOOGLE_SPEECH = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox", "com.google.android.as")

        /** What engineers and drivers say a lot. */
        val RADIO_WORDS = listOf(
            "box", "box box", "copy", "copy that", "understood", "tyres", "softs", "mediums", "hards",
            "inters", "DRS", "safety car", "virtual safety car", "VSC", "yellow flag", "red flag",
            "blue flags", "track limits", "penalty", "five second penalty", "gap", "pace", "push",
            "stay out", "pit", "pit stop", "undercut", "overcut", "deg", "degradation", "graining",
            "brake bias", "diff", "strat", "plan A", "plan B", "mode push", "lift and coast",
            "good job", "well done", "P1", "P2", "P3", "lap", "sector", "turn", "engine", "battery",
        )
    }
}
