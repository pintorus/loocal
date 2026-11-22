package app.loocal.ui

import android.app.Application
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class Tab { HISTORY, CHAT, SETTINGS }

data class UiState(
    val tab: Tab = Tab.CHAT,
    val chat: Chat = Chat(),
    val chats: List<Chat> = emptyList(),
    val personas: List<Persona> = emptyList(),
    val personaId: String? = null,
    val draft: String = "",
    val streaming: String? = null,
    val modelReady: Boolean = false,
    val downloadProgress: Float? = null,
    val error: String? = null,
) {
    val isBusy get() = streaming != null
    val canSend get() = modelReady && !isBusy && draft.isNotBlank()
    val persona get() = personas.firstOrNull { it.id == personaId }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app.filesDir.resolve("archive"))
    private val downloader = ModelDownloader(app.filesDir.resolve("models"))
    private val manifest: ModelManifest = app.assets.open("model-4b.json").bufferedReader().use {
        Json { ignoreUnknownKeys = true }.decodeFromString(ModelManifest.serializer(), it.readText())
    }
    private val engine = LlamaEngine()
    private val prompts = PromptBuilder(engine::countTokens)
    private var job: Job? = null

    private val _state = MutableStateFlow(UiState(chats = store.loadChats(), personas = store.loadPersonas(), modelReady = downloader.isReady(manifest)))
    val state: StateFlow<UiState> = _state

    fun select(tab: Tab) = _state.update { if (tab == Tab.CHAT && it.tab == Tab.CHAT) it.copy(chat = Chat()) else it.copy(tab = tab) }
    fun open(chat: Chat) = _state.update { it.copy(chat = chat, tab = Tab.CHAT) }
    fun setDraft(text: String) = _state.update { it.copy(draft = text) }
    fun choosePersona(id: String?) = _state.update { it.copy(personaId = id) }
    fun toggleTemporary() = _state.update { if (it.chat.messages.isEmpty()) it.copy(chat = it.chat.copy(isTemporary = !it.chat.isTemporary)) else it }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun delete(chat: Chat) {
        store.delete(chat.id)
        _state.update { it.copy(chats = store.loadChats(), chat = if (it.chat.id == chat.id) Chat() else it.chat) }
    }

    fun savePersonas(personas: List<Persona>) {
        store.savePersonas(personas)
        _state.update { s -> s.copy(personas = store.loadPersonas(), personaId = s.personaId?.takeIf { id -> personas.any { it.id == id } }) }
    }

    fun downloadModel() {
        if (_state.value.downloadProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(downloadProgress = 0f) }
            runCatching { downloader.download(manifest) { p -> _state.update { it.copy(downloadProgress = p) } } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            _state.update { it.copy(downloadProgress = null, modelReady = downloader.isReady(manifest)) }
        }
    }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val text = s.draft.trim()
        if (PromptBuilder.isPromptTooLong(text)) {
            _state.update { it.copy(error = "Shorten your prompt to ${Limits.MAX_PROMPT_CHARS} characters or fewer.") }
            return
        }
        _state.update { it.copy(draft = "", chat = it.chat.appending(Message(role = Role.USER, text = text))) }
        reply()
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
    }

    fun stop() { job?.cancel() }

    private fun reply() {
        job = viewModelScope.launch {
            _state.update { it.copy(streaming = "") }
            val buffer = StringBuilder()
            try {
                val modelFile = downloader.file(manifest, manifest.files.first { it.path.endsWith(".gguf") })
                engine.load(modelFile.path)
                val s = _state.value
                val prepared = withContext(Dispatchers.Default) { prompts.prepare(s.chat.messages, s.persona) }
                when (prepared) {
                    PreparedPrompt.TooLong -> error("This prompt is too long for the phone's context. Shorten it.")
                    is PreparedPrompt.Ready -> engine.generate(prepared.system, prepared.messages).collect { piece ->
                        buffer.append(piece)
                        _state.update { it.copy(streaming = buffer.toString()) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stop keeps what was written so far.
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                finishReply(buffer.toString().trim())
            }
        }
    }

    private fun finishReply(text: String) {
        _state.update { s ->
            val chat = if (text.isEmpty()) s.chat else s.chat.appending(Message(role = Role.ASSISTANT, text = text))
            store.save(chat)
            s.copy(chat = chat, streaming = null, chats = store.loadChats())
        }
    }

    override fun onCleared() { engine.unload() }
}
