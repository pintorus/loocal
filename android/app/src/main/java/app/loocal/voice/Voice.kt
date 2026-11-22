package app.loocal.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/**
 * The system voices (iOS `VoiceLibrary` + `Speaker`): picks the best installed voice by default,
 * remembers the one chosen in Settings › Voice, and reads text aloud sentence by sentence.
 */
class Speaker(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val pending = AtomicInteger(0)
    private var ready = false
    private val queued = mutableListOf<String>()
    /** Called when the last queued sentence has been spoken. */
    var onIdle: (() -> Unit)? = null
    val isIdle get() = pending.get() == 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) { applyVoice(); queued.forEach(::speak); queued.clear() }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (pending.decrementAndGet() <= 0) { pending.set(0); onIdle?.invoke() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onDone(id) }
        })
    }

    /** Installed voices for the phone's language first, best quality first (as iOS sorts them). */
    fun voices(): List<Voice> {
        if (!ready) return emptyList()
        val language = Locale.getDefault().language
        return (tts.voices ?: emptySet()).filter { !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.locale.language == language }.thenByDescending { it.quality }.thenBy { it.name })
    }

    var selectedName: String?
        get() = prefs.getString("selected", null)
        set(value) { prefs.edit().putString("selected", value).apply(); applyVoice() }

    private fun applyVoice() {
        val wanted = selectedName?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        val fallback = voices().firstOrNull()
        (wanted ?: fallback)?.let { tts.voice = it }
    }

    fun speak(sentence: String, voice: Voice? = null) {
        if (!ready) { queued.add(sentence); return }
        voice?.let { tts.voice = it }
        pending.incrementAndGet()
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, Bundle(), "s" + System.nanoTime())
    }

    /** Read a whole reply aloud, sentence by sentence (long-press › Read aloud). */
    fun say(text: String, voice: Voice? = null) {
        stop()
        sentences(text).forEach { speak(it, voice) }
        if (voice != null) applyVoice()
    }

    fun stop() { pending.set(0); tts.stop() }

    /** A gentle level while talking, for the Tide; the system engine exposes no audio level. */
    fun level(seconds: Double): Double = if (isIdle) 0.05 else 0.35 + 0.25 * sin(seconds * 9) * sin(seconds * 2.3)

    fun shutdown() = tts.shutdown()

    companion object {
        /** Speech-friendly sentences: split on . ! ? … and line breaks, markdown symbols removed. */
        fun sentences(text: String): List<String> =
            plain(text).split(Regex("(?<=[.!?…])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }

        fun plain(text: String) = text.replace("**", "").replace("`", "").replace("#", "")

        fun label(voice: Voice): String = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "Premium"
            voice.quality >= Voice.QUALITY_HIGH -> "Enhanced"
            else -> "Default"
        }
    }
}

enum class Phase { LISTENING, THINKING, SPEAKING }

data class Line(val id: Long = System.nanoTime(), val fromUser: Boolean, val text: String)

data class ConversationState(val phase: Phase = Phase.LISTENING, val lines: List<Line> = emptyList(), val muted: Boolean = false)

/**
 * Conversation mode (port of App/VoiceConversation.swift): on-device speech recognition → the
 * selected local model → the reply spoken sentence by sentence as it streams. The mic is closed
 * while Loocal speaks; a 1.1 s pause ends your turn. Turns are saved into the current chat.
 * Speech never leaves the phone: without on-device recognition, Loocal doesn't listen.
 */
class VoiceConversation(
    private val context: Context,
    private val speaker: Speaker,
    /** Streams a reply to what was said, given the conversation so far. Returns the final text. */
    private val reply: suspend (history: List<Pair<Boolean, String>>, update: (String) -> Unit) -> String,
    /** Saves a finished turn into the chat. */
    private val record: (user: String, reply: String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var lastHeard = 0L
    private var silence: Job? = null
    private var replying: Job? = null
    private var spoken = 0
    private var replyDone = false
    private val history = mutableListOf<Pair<Boolean, String>>()
    @Volatile private var micLevel = 0.0

    fun start() {
        if (running) return
        running = true
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            append(Line(fromUser = false, text = "On-device speech recognition isn’t available on this phone, and Loocal never sends your voice to a server."))
            return
        }
        speaker.onIdle = { scope.launch { if (replyDone && running) resumeListening() } }
        startListening()
    }

    fun stop() {
        running = false
        replying?.cancel(); silence?.cancel()
        speaker.stop(); speaker.onIdle = null
        stopListening()
        scope.launch { recognizer?.destroy(); recognizer = null }
    }

    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        if (_state.value.phase == Phase.LISTENING) { if (muted) stopListening() else startListening() }
    }

    /** 0…1 for the Tide: the mic while listening, a breath while thinking, the voice while speaking. */
    fun level(seconds: Double): Double = when (_state.value.phase) {
        Phase.THINKING -> 0.06
        Phase.LISTENING -> if (_state.value.muted) 0.04 else maxOf(0.04, micLevel)
        Phase.SPEAKING -> speaker.level(seconds)
    }

    // MARK: Listening

    private fun startListening() {
        if (!running || _state.value.muted) return
        _state.update { s ->
            val lines = if (s.lines.lastOrNull()?.fromUser != true || s.lines.last().text.isNotEmpty()) s.lines + Line(fromUser = true, text = "") else s.lines
            s.copy(phase = Phase.LISTENING, lines = lines.takeLast(8))
        }
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { micLevel = ((rmsdB + 2) / 12.0).coerceIn(0.0, 1.0) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // The recognizer timed out with nothing heard: keep listening.
                if (_state.value.phase == Phase.LISTENING && currentSaid().isEmpty()) scope.launch { delay(150); restartListening() }
                else endTurn()
            }
            override fun onResults(results: Bundle?) { heard(results, final = true) }
            override fun onPartialResults(partial: Bundle?) { heard(partial, final = false) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        r.startListening(intent)
        lastHeard = System.currentTimeMillis()
        silence?.cancel()
        silence = scope.launch {
            while (isActive) {
                delay(200)
                if (_state.value.phase == Phase.LISTENING && currentSaid().isNotEmpty() && System.currentTimeMillis() - lastHeard > END_OF_TURN_MS) endTurn()
            }
        }
    }

    private fun restartListening() { stopListening(); startListening() }

    private fun currentSaid() = _state.value.lines.lastOrNull()?.takeIf { it.fromUser }?.text?.trim().orEmpty()

    private fun heard(bundle: Bundle?, final: Boolean) {
        if (_state.value.phase != Phase.LISTENING) return
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrEmpty() && text != currentSaid()) {
            _state.update { s -> s.copy(lines = s.lines.dropLast(1) + s.lines.last().copy(text = text)) }
            lastHeard = System.currentTimeMillis()
        }
        if (final) { if (currentSaid().isNotEmpty()) endTurn() else scope.launch { restartListening() } }
    }

    private fun stopListening() {
        silence?.cancel()
        runCatching { recognizer?.cancel() }
        micLevel = 0.0
    }

    private fun endTurn() {
        if (_state.value.phase != Phase.LISTENING) return
        val said = currentSaid()
        if (said.isEmpty()) return
        stopListening()
        _state.update { it.copy(phase = Phase.THINKING) }
        respond(said)
    }

    // MARK: Replying

    private fun respond(said: String) {
        append(Line(fromUser = false, text = ""))
        spoken = 0
        replyDone = false
        history.add(true to said)
        replying = scope.launch {
            val final = runCatching {
                reply(history.toList()) { partial -> scope.launch { received(partial) } }
            }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) return@launch else "Sorry, I couldn’t answer: ${e.message}" }
            received(final)
            history.add(false to Speaker.plain(final))
            replyDone = true
            speakPending(final = true)
            record(said, Speaker.plain(final))
            if (speaker.isIdle) resumeListening()
        }
    }

    /** Speaks up unprompted, between your turns (errand mode reads results back with it); never over you. */
    fun announce(text: String) {
        if (!running || _state.value.phase != Phase.LISTENING || currentSaid().isNotEmpty()) return
        stopListening()
        _state.update { s -> s.copy(lines = (s.lines.let { if (it.lastOrNull()?.fromUser == true) it.dropLast(1) else it } + Line(fromUser = false, text = Speaker.plain(text))).takeLast(8)) }
        spoken = 0
        replyDone = true
        speakPending(final = true)
        if (speaker.isIdle) resumeListening()
    }

    private fun received(text: String) {
        _state.update { s -> if (s.lines.lastOrNull()?.fromUser == false) s.copy(lines = s.lines.dropLast(1) + s.lines.last().copy(text = Speaker.plain(text))) else s }
        speakPending(final = false)
    }

    /** Speak each sentence as soon as it's complete, so the voice starts early. */
    private fun speakPending(final: Boolean) {
        val line = _state.value.lines.lastOrNull()?.takeIf { !it.fromUser } ?: return
        val text = line.text
        var end = spoken
        if (final) end = text.length
        else for (i in text.length - 2 downTo spoken) if (text[i] in ".!?…\n" && text[i + 1].isWhitespace()) { end = i + 1; break }
        if (end <= spoken) return
        val sentence = text.substring(spoken, end).trim()
        spoken = end
        if (sentence.isEmpty()) return
        _state.update { it.copy(phase = Phase.SPEAKING) }
        speaker.speak(sentence)
    }

    private fun resumeListening() { scope.launch { delay(250); startListening() } }

    private fun append(line: Line) = _state.update { it.copy(lines = (it.lines + line).takeLast(8)) }

    companion object {
        /** A pause this long after speech ends your turn. */
        const val END_OF_TURN_MS = 1100L
        const val INSTRUCTIONS = "This is a spoken conversation. Reply in one to three short, natural sentences. No lists, headings or markdown."
    }
}
