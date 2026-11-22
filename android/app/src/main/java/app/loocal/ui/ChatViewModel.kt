package app.loocal.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.loocal.domain.Chat
import app.loocal.domain.ChatStore
import app.loocal.domain.Limits
import app.loocal.domain.Message
import app.loocal.domain.Persona
import app.loocal.domain.PreparedPrompt
import app.loocal.domain.PromptBuilder
import app.loocal.domain.Role
import app.loocal.inference.LlamaEngine
import app.loocal.inference.ModelDownloader
import app.loocal.inference.ModelManifest
import app.loocal.ui.theme.Appearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class UiState(
    val chat: Chat = Chat(),
    val chats: List<Chat> = emptyList(),
    val personas: List<Persona> = emptyList(),
    val personaId: String? = null,
    val draft: String = "",
    /** Id of the reply being written, while generating. */
    val generatingId: String? = null,
    /** Text written so far for [generatingId]. */
    val streaming: String = "",
    /** Replies the user stopped: shown with "Stopped · partial reply saved". */
    val interrupted: Set<String> = emptySet(),
    val modelName: String = "",
    val modelBytes: Long = 0,
    val modelReady: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadError: String? = null,
    val appearance: Appearance = Appearance.SYSTEM,
    val error: String? = null,
) {
    val isBusy get() = generatingId != null
    val canSend get() = modelReady && !isBusy && draft.isNotBlank()
    val persona get() = personas.firstOrNull { it.id == personaId }
    val isTemporary get() = chat.isTemporary
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app.filesDir.resolve("archive"))
    private val prefs = app.getSharedPreferences("loocal", Context.MODE_PRIVATE)
    private val downloader = ModelDownloader(app.filesDir.resolve("models"))
    private val manifest: ModelManifest = app.assets.open("model-4b.json").bufferedReader().use {
        Json { ignoreUnknownKeys = true }.decodeFromString(ModelManifest.serializer(), it.readText())
    }
    private val engine = LlamaEngine()
    private val prompts = PromptBuilder(engine::countTokens)
    private var job: Job? = null

    private val _state = MutableStateFlow(
        UiState(
            chats = store.loadChats(),
            personas = store.loadPersonas(),
            personaId = prefs.getString("persona", null),
            modelName = manifest.name,
            modelBytes = manifest.files.sumOf { it.size },
            modelReady = downloader.isReady(manifest),
            appearance = runCatching { Appearance.valueOf(prefs.getString("appearance", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM),
        ),
    )
    val state: StateFlow<UiState> = _state

    fun newChat() = _state.update { if (it.isBusy) it else it.copy(chat = Chat(), draft = "") }
    fun open(chat: Chat) = _state.update { if (it.isBusy) it else it.copy(chat = chat) }
    fun setDraft(text: String) = _state.update { it.copy(draft = text) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun choosePersona(id: String?) {
        prefs.edit().putString("persona", id).apply()
        _state.update { it.copy(personaId = id) }
    }

    fun setAppearance(appearance: Appearance) {
        prefs.edit().putString("appearance", appearance.name).apply()
        _state.update { it.copy(appearance = appearance) }
    }

    /** The logo on an empty chat switches between a normal and a temporary chat. */
    fun toggleTemporary() = _state.update {
        if (it.chat.messages.isEmpty()) it.copy(chat = it.chat.copy(isTemporary = !it.chat.isTemporary)) else it
    }

    fun delete(chat: Chat) {
        store.delete(chat.id)
        _state.update { it.copy(chats = store.loadChats(), chat = if (it.chat.id == chat.id) Chat() else it.chat) }
    }

    fun togglePin(chat: Chat) {
        store.save(chat.copy(isPinned = !chat.isPinned))
        _state.update { s -> s.copy(chats = store.loadChats(), chat = if (s.chat.id == chat.id) s.chat.copy(isPinned = !chat.isPinned) else s.chat) }
    }

    fun savePersona(persona: Persona) {
        val list = _state.value.personas
        val updated = if (list.any { it.id == persona.id }) list.map { if (it.id == persona.id) persona else it } else list + persona
        store.savePersonas(updated)
        _state.update { it.copy(personas = store.loadPersonas()) }
    }

    fun deletePersona(persona: Persona) {
        store.savePersonas(_state.value.personas.filterNot { it.id == persona.id })
        if (_state.value.personaId == persona.id) choosePersona(null)
        _state.update { it.copy(personas = store.loadPersonas()) }
    }

    fun downloadModel() {
        if (_state.value.downloadProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(downloadProgress = 0f, downloadError = null) }
            runCatching { downloader.download(manifest) { p -> _state.update { it.copy(downloadProgress = p) } } }
                .onFailure { e -> _state.update { it.copy(downloadError = e.message ?: "The download stopped. Try again.") } }
            _state.update { it.copy(downloadProgress = null, modelReady = downloader.isReady(manifest)) }
        }
    }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val text = s.draft.trim()
        if (PromptBuilder.isPromptTooLong(text)) {
            _state.update { it.copy(error = "Shorten your prompt to 6,000 characters or fewer for the phone context.") }
            return
        }
        _state.update { it.copy(draft = "", chat = it.chat.appending(Message(role = Role.USER, text = text))) }
        reply()
    }

    fun canRegenerate(message: Message): Boolean {
        val s = _state.value
        return !s.isBusy && message.role == Role.ASSISTANT && s.chat.messages.lastOrNull()?.id == message.id
    }

    fun regenerate() {
        if (_state.value.isBusy) return
        _state.update { it.copy(chat = it.chat.withoutLatestReply()) }
        reply()
    }

    /** Edit rewinds the chat to the prompt and puts its text back in the composer. */
    fun edit(message: Message) {
        if (_state.value.isBusy) return
        _state.update { it.copy(chat = it.chat.rewoundTo(message.id), draft = message.text) }
        persist()
    }

    fun stop() { job?.cancel() }

    private fun reply() {
        val replyId = java.util.UUID.randomUUID().toString()
        job = viewModelScope.launch {
            _state.update { it.copy(generatingId = replyId, streaming = "") }
            val buffer = StringBuilder()
            var stopped = false
            try {
                val modelFile = downloader.file(manifest, manifest.files.first { it.path.endsWith(".gguf") })
                engine.load(modelFile.path)
                val s = _state.value
                val prepared = withContext(Dispatchers.Default) { prompts.prepare(s.chat.messages, s.persona) }
                when (prepared) {
                    PreparedPrompt.TooLong -> error("This message is too long for the phone's context. Shorten it.")
                    is PreparedPrompt.Ready -> engine.generate(prepared.system, prepared.messages).collect { piece ->
                        buffer.append(piece)
                        _state.update { it.copy(streaming = buffer.toString()) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                stopped = true // Stop keeps what was written so far.
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                finishReply(replyId, buffer.toString().trim(), stopped)
            }
        }
    }

    private fun finishReply(id: String, text: String, stopped: Boolean) {
        _state.update { s ->
            val chat = if (text.isEmpty()) s.chat else s.chat.appending(Message(id = id, role = Role.ASSISTANT, text = text))
            s.copy(chat = chat, generatingId = null, streaming = "", interrupted = if (stopped && text.isNotEmpty()) s.interrupted + id else s.interrupted)
        }
        persist()
    }

    private fun persist() {
        store.save(_state.value.chat)
        _state.update { it.copy(chats = store.loadChats()) }
    }

    override fun onCleared() { engine.unload() }
}
