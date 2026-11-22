package app.loocal.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * An open-source Kokoro-82M voice (Apache 2.0), the same catalog as iOS (App/NeuralVoices.swift).
 * English only. [sid] is the speaker id in the sherpa-onnx Kokoro v1.0 voice table.
 */
data class NeuralVoice(val id: String, val name: String, val female: Boolean, val british: Boolean, val sid: Int) {
    val accent get() = if (british) "British" else "American"

    companion object {
        val CATALOG = listOf(
            NeuralVoice("af_heart", "Heart", true, false, 3), NeuralVoice("af_bella", "Bella", true, false, 2),
            NeuralVoice("af_nicole", "Nicole", true, false, 6), NeuralVoice("af_sarah", "Sarah", true, false, 9),
            NeuralVoice("af_kore", "Kore", true, false, 5), NeuralVoice("am_michael", "Michael", false, false, 16),
            NeuralVoice("am_fenrir", "Fenrir", false, false, 14), NeuralVoice("am_puck", "Puck", false, false, 18),
            NeuralVoice("bf_emma", "Emma", true, true, 21), NeuralVoice("bm_george", "George", false, true, 26),
            NeuralVoice("bm_fable", "Fable", false, true, 25),
        )
        const val PREFIX = "kokoro:"
    }
}

/**
 * The Kokoro voice engine: one pinned, hash-checked archive (sherpa-onnx `kokoro-multi-lang-v1_0`,
 * 350 MB) holding the model and every voice. Downloaded once, unpacked to app storage, then offline.
 */
class KokoroEngine(root: File) {
    private val folder = File(root, "kokoro")
    private val ready = File(folder, ".ok")
    val isReady get() = ready.exists()

    suspend fun download(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        folder.mkdirs()
        val archive = File(folder, "engine.tar.bz2")
        val have = archive.length()
        if (have < SIZE) {
            val c = (URL(URL_STRING).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val resumed = c.responseCode == HttpURLConnection.HTTP_PARTIAL
            check(resumed || c.responseCode == HttpURLConnection.HTTP_OK) { "Download failed (HTTP ${c.responseCode})." }
            var written = if (resumed) have else 0L
            c.inputStream.use { input -> FileOutputStream(archive, resumed).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n); written += n
                    progress(written.toFloat() / SIZE * 0.9f)
                }
            } }
        }
        if (sha256(archive) != SHA256) { archive.delete(); error("The voice engine failed verification and was deleted. Try again.") }
        // Unpack only what English voices need.
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfter('/')
                val keep = name in setOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt") || name.startsWith("espeak-ng-data/")
                if (!keep || name.isEmpty()) continue
                val target = File(folder, name)
                require(target.canonicalPath.startsWith(folder.canonicalPath)) { "Bad archive entry." }
                if (entry.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { tar.copyTo(it) }
            }
        }
        archive.delete()
        ready.createNewFile()
        progress(1f)
    }

    fun delete() { folder.deleteRecursively() }

    private var tts: OfflineTts? = null
    private var lexicon: String? = null

    /** The synthesizer, loaded on first use with the lexicon for the voice's accent. */
    fun synthesizer(voice: NeuralVoice): OfflineTts {
        val wanted = File(folder, if (voice.british) "lexicon-gb-en.txt" else "lexicon-us-en.txt").path
        tts?.takeIf { lexicon == wanted }?.let { return it }
        tts?.release()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(folder, "model.onnx").path, voices = File(folder, "voices.bin").path, tokens = File(folder, "tokens.txt").path,
                    dataDir = File(folder, "espeak-ng-data").path, lexicon = wanted, lang = "en-us",
                ),
                numThreads = 6, debug = false, provider = "cpu", // 6 measured faster than 4 on a Xiaomi 14
            ),
        )
        return OfflineTts(null, config).also { tts = it; lexicon = wanted }
    }

    fun unload() { tts?.release(); tts = null; lexicon = null }

    companion object {
        const val URL_STRING = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
        const val SIZE = 349_886_476L
        const val SHA256 = "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4"

        private fun sha256(file: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Speaks sentences with a Kokoro voice (iOS `NeuralSpeech`): each sentence is synthesized on a
 * background thread while the previous one plays, so speech flows without gaps.
 */
class NeuralSpeech(private val engine: KokoroEngine, private val onIdle: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var queue = Channel<Pair<String, NeuralVoice>>(Channel.UNLIMITED)
    private var worker: Job? = null
    @Volatile private var pending = 0
    @Volatile private var level = 0.0
    private var track: AudioTrack? = null

    val isIdle get() = pending == 0

    fun speak(sentence: String, voice: NeuralVoice) {
        pending++
        queue.trySend(sentence to voice)
        if (worker?.isActive != true) worker = scope.launch { run() }
    }

    private suspend fun run() {
        for ((sentence, voice) in queue) {
            runCatching {
                val audio = engine.synthesizer(voice).generate(sentence, voice.sid, 1.0f)
                play(audio.samples, audio.sampleRate)
            }
            pending = (pending - 1).coerceAtLeast(0)
            if (pending == 0) { level = 0.0; onIdle() }
        }
    }

    private suspend fun play(samples: FloatArray, rate: Int) {
        val t = track?.takeIf { it.sampleRate == rate } ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).build().also { track?.release(); track = it }
        t.play()
        // Write in small blocks so the level follows the voice and Stop is quick.
        val block = rate / 20
        var i = 0
        while (i < samples.size) {
            coroutineContext.ensureActive()
            val n = minOf(block, samples.size - i)
            var sum = 0.0
            for (k in i until i + n) sum += samples[k] * samples[k]
            level = (sqrt(sum / n) * 4).coerceIn(0.0, 1.0)
            t.write(samples, i, n, AudioTrack.WRITE_BLOCKING)
            i += n
        }
    }

    fun level() = level

    fun stop() {
        worker?.cancel(); worker = null
        queue.close(); queue = Channel(Channel.UNLIMITED)
        pending = 0; level = 0.0
        track?.pause(); track?.flush()
    }

    fun release() { stop(); track?.release(); track = null }
}
