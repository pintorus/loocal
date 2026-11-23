package app.loocal.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.loocal.domain.AttachedFile
import app.loocal.domain.Chat
import app.loocal.domain.Cadence
import app.loocal.domain.Errand
import app.loocal.domain.ErrandIdea
import app.loocal.domain.ErrandRun
import app.loocal.domain.ErrandStore
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import app.loocal.domain.ChatStore
import app.loocal.domain.FileReader
import app.loocal.domain.Message
import app.loocal.domain.Persona
import app.loocal.domain.PhotoStore
import app.loocal.domain.PreparedPrompt
import app.loocal.domain.PromptBuilder
import app.loocal.domain.Role
import app.loocal.inference.LibraryError
import app.loocal.inference.LlamaEngine
import app.loocal.inference.ModelLibrary
import app.loocal.inference.ModelDownloader
import app.loocal.inference.ModelManifest
import app.loocal.inference.PhotoInput
import app.loocal.ui.theme.Appearance
import app.loocal.tools.ActionRunner
import app.loocal.tools.Connector
import app.loocal.tools.ConnectorHub
import app.loocal.domain.Island
import app.loocal.domain.IslandText
import app.loocal.tools.SourceIntent
import app.loocal.tools.McpClient
import app.loocal.tools.McpServer
import app.loocal.tools.Memory
import app.loocal.tools.PageReader
import app.loocal.tools.SecretStore
import app.loocal.tools.ToolAction
import app.loocal.tools.ToolBelt
import app.loocal.tools.ToolCall
import app.loocal.tools.ToolDefinition
import app.loocal.tools.ToolStore
import app.loocal.tools.ToolUse
import app.loocal.tools.WebSearch
import app.loocal.tools.RoutineAlarm
import app.loocal.ReplyService
import app.loocal.voice.Speaker
import app.loocal.voice.VoiceConversation
import app.loocal.voice.NeuralVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class DownloadStatus(val progress: Float? = null, val error: String? = null) {
    val isDownloading get() = progress != null
}

/** A photo waiting in the composer. */
data class PendingPhoto(val name: String, val width: Int, val height: Int)

/** Up to three replies write at once; they take turns on the model. */
const val MAX_REPLIES = 3

/** One reply being written in a chat. */
data class Writing(val replyId: String, val text: String = "", val tools: List<ToolUse> = emptyList(), val phase: String = "", val tokens: Int = 0)

data class UiState(
    val chat: Chat = Chat(),
    val chats: List<Chat> = emptyList(),
    val personas: List<Persona> = emptyList(),
    val personaId: String? = null,
    val draft: String = "",
    /** Islands placed in the draft (their markers are in [draft]). */
    val draftIslands: List<Island> = emptyList(),
    /** An island waiting to be put at the prompt's cursor; the field takes it and clears it. */
    val islandToInsert: Island? = null,
    /** Replies being written, by chat id: several chats can be answered at once (iOS parallel replies). */
    val writing: Map<String, Writing> = emptyMap(),
    /** "512 tokens · 12.8 tok/s · 4,100 MB free" under the latest reply. */
    val performance: String = "",
    /** Replies the user stopped: shown with "Stopped · partial reply saved". */
    val interrupted: Set<String> = emptySet(),
    val models: List<ModelManifest> = emptyList(),
    val modelId: String = "",
    val ready: Set<String> = emptySet(),
    val downloads: Map<String, DownloadStatus> = emptyMap(),
    val photo: PendingPhoto? = null,
    val preparingPhoto: Boolean = false,
    val files: List<AttachedFile> = emptyList(),
    val activeConnectors: Set<String> = emptySet(),
    val servers: List<McpServer> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val memoryOn: Boolean = true,
    val errands: List<Errand> = emptyList(),
    val runs: List<ErrandRun> = emptyList(),
    /** The persona errands go out as; separate from the chat's. */
    val errandPersonaId: String? = null,
    val errandDraft: String = "",
    val errandCadence: Cadence = Cadence.NOW,
    val errandHour: Int = 8,
    val errandMinute: Int = 0,
    val errandSources: Set<String> = emptySet(),
    val appearance: Appearance = Appearance.SYSTEM,
    val error: String? = null,
    val notice: String? = null,
    /** A Hugging Face model being looked up after it was shared. */
    val addingModel: String? = null,
    /** The model just shared: Settings › Models opens on it and it's marked a moment. */
    val revealedModel: String? = null,
) {
    val model get() = models.firstOrNull { it.id == modelId } ?: models.first()
    val modelReady get() = model.id in ready
    val download get() = downloads[model.id] ?: DownloadStatus()
    private val here get() = writing[chat.id]
    /** Id of this chat's reply being written. */
    val generatingId get() = here?.replyId
    /** Text written so far for [generatingId]. */
    val streaming get() = here?.text.orEmpty()
    /** What this chat's reply is doing before its first words ("Loading model", "Reading prompt"). */
    val phase get() = here?.phase.orEmpty()
    /** Boxes of the reply being written (sources, tools, actions). */
    val streamingTools get() = here?.tools.orEmpty()
    /** This chat is being answered. Other chats can still be opened and written in. */
    val isBusy get() = chat.id in writing
    /** The model has work: a reply in any chat, or an errand. */
    val anyWork get() = writing.isNotEmpty() || runs.any { it.status == ErrandRun.Status.RUNNING }
    /** What sits with the bot: errands on the go (oldest first), then today's unseen results. Four at most. */
    val crew get() = (runs.filter { it.isActive }.reversed() + runs.filter { !it.isActive && !it.seen && it.startedAt > System.currentTimeMillis() - 12 * 3600_000L }).take(ErrandStore.CREW_SIZE)
    val errandPersona get() = personas.firstOrNull { it.id == errandPersonaId }
    val canSend get() = modelReady && !isBusy && writing.size < MAX_REPLIES && !preparingPhoto && (draft.isNotBlank() || photo != null || files.isNotEmpty())
    val persona get() = personas.firstOrNull { it.id == personaId }
    val isTemporary get() = chat.isTemporary
    val connectors get() = Connector.BUILT_IN + servers.map(Connector::mcp)
}

class ChatViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app.filesDir.resolve("archive"))
    private val photos = PhotoStore(app.filesDir).also { IslandArt.photos = app.filesDir }
    private val prefs = app.getSharedPreferences("loocal", Context.MODE_PRIVATE)
    private val downloader = ModelDownloader(app.filesDir.resolve("models"))
    private val json = Json { ignoreUnknownKeys = true }
    private val builtIn: List<ModelManifest> = listOf("qwen3-vl-4b", "qwen3-vl-8b", "gemma-3-12b").map { id ->
        app.assets.open("models/$id.json").bufferedReader().use { json.decodeFromString(ModelManifest.serializer(), it.readText()) }
    }
    /** Models shared to Loocal from Hugging Face, on top of the three built in. */
    private val sharedFile = app.filesDir.resolve("shared-models.json")
    private var shared: List<ModelManifest> = ModelLibrary.load(sharedFile)
        set(value) { field = value; ModelLibrary.save(value, sharedFile) }
    /** Every model, small → large: Settings › Models and the carousel. */
    private val models get() = ModelLibrary.ordered(builtIn + shared)
    private val tools = ToolStore(app)
    private val desk = ErrandStore(app.filesDir)
    private val secrets = SecretStore(app)
    private val actions = ActionRunner(app, tools)
    private val engine = LlamaEngine()
    /** System voices: read aloud, conversation mode, Settings › Voice. */
    val speaker = Speaker(app)
    private val prompts = PromptBuilder(engine::countTokens)
    private val downloadJobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow(
        UiState(
            chats = store.loadChats(),
            personas = store.loadPersonas(),
            personaId = prefs.getString("persona", null),
            models = models,
            modelId = prefs.getString("model", null)?.takeIf { id -> models.any { it.id == id } } ?: "qwen3-vl-8b",
            ready = models.filter { downloader.isReady(it) }.map { it.id }.toSet(),
            appearance = runCatching { Appearance.valueOf(prefs.getString("appearance", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM),
            activeConnectors = activeConnectorsAtLaunch(),
            servers = tools.servers,
            memories = tools.memories,
            memoryOn = prefs.getBoolean("memory.on", true),
            errands = desk.load().first,
            runs = desk.load().second,
            errandPersonaId = prefs.getString("errands.persona", null),
        ),
    )

    /** Web search starts switched on, once; after that it stays however you leave it. */
    private fun activeConnectorsAtLaunch(): Set<String> {
        val on = prefs.getStringSet("connectors.active", emptySet())!!.toMutableSet()
        if (!prefs.getBoolean("connectors.webDefault", false)) {
            on.add(Connector.WEB.id)
            prefs.edit().putBoolean("connectors.webDefault", true).putStringSet("connectors.active", on).apply()
        }
        return on
    }
    val state: StateFlow<UiState> = _state

    init {
        // While the model writes, a foreground service keeps it going if you leave (iOS: continued processing).
        viewModelScope.launch {
            _state.collect { s ->
                if (s.anyWork) {
                    val errand = s.runs.firstOrNull { it.status == ErrandRun.Status.RUNNING }
                    val reply = s.writing.values.firstOrNull()
                    val many = s.writing.size + (if (errand != null) 1 else 0)
                    ReplyService.start(app, s.model.name)
                    ReplyService.update(app, if (many > 1) "Writing $many replies" else errand?.let { "Errand · " + (it.stage ?: "Writing") } ?: reply?.phase?.ifEmpty { "Thinking" } ?: "Thinking", errand?.tokens ?: reply?.tokens ?: 0, s.model.name)
                } else ReplyService.stop(app)
            }
        }
        // Model folders no model uses any more, temporary photos, and photos no saved chat refers to.
        viewModelScope.launch(Dispatchers.IO) {
            downloader.sweep(models)
            // A download that reached its last byte but was never verified is finished without fetching again.
            downloader.finishVerified(models).takeIf { it.isNotEmpty() }?.let { done -> _state.update { it.copy(ready = it.ready + done.map { m -> m.id }) } }
            photos.sweep(_state.value.chats.flatMap { c -> c.messages.mapNotNull { it.imageName } }.toSet())
        }
    }

    // MARK: Chats

    fun newChat() {
        leaveTemporary()
        _state.update { it.copy(chat = Chat(), draft = "", photo = null, files = emptyList(), performance = "") }
    }

    fun open(chat: Chat) {
        leaveTemporary()
        _state.update { it.copy(chat = chat, performance = "") }
    }

    /** A temporary chat is discarded when you leave it, photos included. */
    private fun leaveTemporary() { if (_state.value.chat.isTemporary) photos.clearTemporary() }

    /**
     * The field's text, islands as markers. An attachment whose island was deleted from the text
     * goes with it.
     */
    fun setDraft(text: String) {
        val s = _state.value
        val gone = s.draftIslands.filter { !text.contains(it.marker) }
        if (gone.any { it.kind == Island.Kind.PHOTO }) s.photo?.let { photos.delete(listOf(it.name)) }
        val goneFiles = gone.filter { it.kind == Island.Kind.FILE }.map { it.ref }.toSet()
        _state.update {
            it.copy(
                draft = text, draftIslands = it.draftIslands - gone.toSet(),
                photo = if (gone.any { g -> g.kind == Island.Kind.PHOTO }) null else it.photo,
                files = it.files.filterNot { f -> f.name in goneFiles },
            )
        }
    }

    /** Puts an island at the prompt's cursor (the field does the placing). */
    fun placeIsland(island: Island) = _state.update { it.copy(draftIslands = it.draftIslands + island, islandToInsert = island) }

    fun islandPlaced() = _state.update { it.copy(islandToInsert = null) }

    /** A typed tool name was tapped: its island joins the draft (the field puts it in the word's place). */
    fun adoptIsland(connectorId: String): Island? {
        val name = _state.value.connectors.firstOrNull { it.id == connectorId }?.name ?: return null
        return Island.connector(connectorId, name).also { island -> _state.update { it.copy(draftIslands = it.draftIslands + island) } }
    }

    private fun removeIsland(match: (Island) -> Boolean) = _state.update { s ->
        val gone = s.draftIslands.filter(match)
        var text = s.draft
        gone.forEach { text = text.replace(" " + it.marker, "").replace(it.marker, "") }
        s.copy(draft = text, draftIslands = s.draftIslands - gone.toSet())
    }
    fun dismissError() = _state.update { it.copy(error = null, notice = null) }
    fun showError(message: String) = _state.update { it.copy(error = message) }

    fun choosePersona(id: String?) {
        prefs.edit().putString("persona", id).apply()
        _state.update { it.copy(personaId = id) }
    }

    fun chooseModel(id: String) {
        if (_state.value.anyWork) return
        prefs.edit().putString("model", id).apply()
        // A text-only model can't take the photo waiting in the composer.
        _state.update { s -> s.copy(modelId = id, photo = if (models.first { it.id == id }.vision) s.photo else null.also { s.photo?.let { p -> photos.delete(listOf(p.name)) } }) }
    }

    fun setAppearance(appearance: Appearance) {
        prefs.edit().putString("appearance", appearance.name).apply()
        _state.update { it.copy(appearance = appearance) }
    }

    /** The logo on an empty chat switches between a normal and a temporary chat. */
    fun toggleTemporary() = _state.update {
        if (it.chat.messages.isEmpty() && !it.isBusy) it.copy(chat = it.chat.copy(isTemporary = !it.chat.isTemporary)) else it
    }

    fun delete(chat: Chat) {
        if (chat.id in _state.value.writing) return
        photos.delete(chat.messages.mapNotNull { it.imageName })
        store.delete(chat.id)
        _state.update { it.copy(chats = store.loadChats(), chat = if (it.chat.id == chat.id) Chat() else it.chat) }
    }

    fun togglePin(chat: Chat) {
        store.save(chat.copy(isPinned = !chat.isPinned))
        _state.update { s -> s.copy(chats = store.loadChats(), chat = if (s.chat.id == chat.id) s.chat.copy(isPinned = !chat.isPinned) else s.chat) }
    }

    // MARK: Personas

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

    // MARK: Models

    fun downloadModel(id: String = _state.value.modelId) {
        if (downloadJobs[id]?.isActive == true) return
        val manifest = models.first { it.id == id }
        if (!onWifi()) {
            _state.update { it.copy(downloads = it.downloads + (id to DownloadStatus(error = "Connect to Wi-Fi to download. Models are large."))) }
            return
        }
        downloadJobs[id] = viewModelScope.launch {
            _state.update { it.copy(downloads = it.downloads + (id to DownloadStatus(progress = 0f))) }
            val result = runCatching {
                downloader.download(manifest) { p -> _state.update { it.copy(downloads = it.downloads + (id to DownloadStatus(progress = p))) } }
            }
            _state.update { s ->
                s.copy(
                    downloads = s.downloads + (id to DownloadStatus(error = result.exceptionOrNull()?.let { e -> if (e is kotlinx.coroutines.CancellationException) null else e.message ?: "The download stopped. Try again." })),
                    ready = if (downloader.isReady(manifest)) s.ready + id else s.ready - id,
                )
            }
        }
    }

    fun cancelDownload(id: String) { downloadJobs[id]?.cancel() }

    /** Physical memory, for marking models likely too big for this phone. */
    val totalMemory: Long = runCatching {
        android.app.ActivityManager.MemoryInfo().also { app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }.getOrDefault(0L)

    /**
     * A link shared to Loocal: a Hugging Face model page joins the models, not downloaded yet.
     * Returns false when the text holds no link at all.
     */
    fun addModel(text: String): Boolean {
        val link = ModelLibrary.link(text) ?: return false
        val repository = runCatching { ModelLibrary.repository(link) }.getOrElse { e -> _state.update { it.copy(error = e.message) }; return true }
        models.firstOrNull { it.id == "hf." + repository.lowercase() || it.repository.equals(repository, ignoreCase = true) }?.let { known ->
            _state.update { it.copy(revealedModel = known.id, notice = "${known.name} is already in your models.") }
            return true
        }
        if (_state.value.addingModel != null) return true
        _state.update { it.copy(addingModel = repository) }
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { ModelLibrary.fetch(repository) } }
            result.onSuccess { model ->
                if (models.none { it.id == model.id }) shared = shared + model
                _state.update { it.copy(models = models, addingModel = null, revealedModel = model.id) }
            }.onFailure { e ->
                val message = if (e is LibraryError) e.message else "Hugging Face couldn’t be reached. Check your connection and share the model again."
                _state.update { it.copy(addingModel = null, error = message) }
            }
        }
        return true
    }

    fun revealed() = _state.update { it.copy(revealedModel = null) }

    /** Takes a shared model off the list and deletes whatever of it was downloaded. The three built-in models stay. */
    fun removeModel(id: String) {
        val model = shared.firstOrNull { it.id == id } ?: return
        if (_state.value.anyWork) { _state.update { it.copy(notice = "Wait for the replies being written to finish, then remove ${model.name}.") }; return }
        downloadJobs[id]?.cancel()
        engine.unload()
        downloader.delete(model)
        shared = shared - model
        _state.update { s ->
            val id0 = if (s.modelId == id) "qwen3-vl-8b" else s.modelId
            if (id0 != s.modelId) prefs.edit().putString("model", id0).apply()
            s.copy(models = models, modelId = id0, ready = s.ready - id, downloads = s.downloads - id)
        }
    }

    fun deleteModel(id: String) {
        if (_state.value.anyWork && id == _state.value.modelId) return
        engine.unload()
        downloader.delete(models.first { it.id == id })
        _state.update { it.copy(ready = it.ready - id, downloads = it.downloads - id) }
    }

    private fun onWifi(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // MARK: Attachments

    fun attachPhoto(uri: Uri) {
        if (!_state.value.model.vision) return
        _state.update { it.copy(preparingPhoto = true) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { photos.import(app.contentResolver, uri, _state.value.isTemporary) } }
                .onSuccess { stored ->
                    _state.value.photo?.let { photos.delete(listOf(it.name)) }
                    removeIsland { it.kind == Island.Kind.PHOTO }
                    _state.update { it.copy(photo = PendingPhoto(stored.name, stored.modelWidth, stored.modelHeight)) }
                    placeIsland(Island.photo(stored.name))
                }
                .onFailure { e -> _state.update { it.copy(error = "The photo couldn’t be imported: ${e.message}") } }
            _state.update { it.copy(preparingPhoto = false) }
        }
    }

    fun discardPhoto() {
        _state.value.photo?.let { photos.delete(listOf(it.name)) }
        _state.update { it.copy(photo = null) }
        removeIsland { it.kind == Island.Kind.PHOTO }
    }

    fun attachFiles(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                withContext(Dispatchers.IO) { FileReader.read(app, uri) }
                    .onSuccess { file -> _state.update { it.copy(files = it.files + file) }; placeIsland(Island.file(file.name)) }
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
            }
        }
    }

    fun removeFile(name: String) {
        _state.update { s -> s.copy(files = s.files.filterNot { it.name == name }) }
        removeIsland { it.kind == Island.Kind.FILE && it.ref == name }
    }

    /**
     * A connector picked in the tray goes into the prompt as an island, for this message only
     * (picking it again takes it out). Connectors switched on in Settings stay on for every message.
     */
    fun toggleConnectorIsland(id: String) {
        val s = _state.value
        if (s.draftIslands.any { it.kind == Island.Kind.CONNECTOR && it.ref == id }) { removeIsland { it.kind == Island.Kind.CONNECTOR && it.ref == id }; return }
        val name = s.connectors.firstOrNull { it.id == id }?.name ?: return
        placeIsland(Island.connector(id, name))
    }

    // MARK: Connectors, MCP and memory

    /** Permissions are asked by the screen before this is called. */
    fun toggleConnector(id: String) {
        val now = _state.value.activeConnectors.let { if (id in it) it - id else it + id }
        prefs.edit().putStringSet("connectors.active", now).apply()
        _state.update { it.copy(activeConnectors = now) }
    }

    /** Connects to an MCP server, lists its tools and keeps it (token encrypted with the Keystore). */
    fun addMcpServer(address: String, token: String) {
        val trimmed = address.trim()
        val url = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val parsed = runCatching { java.net.URL(url) }.getOrNull()
        if (parsed == null || !(parsed.protocol == "https" || parsed.host == "localhost")) {
            _state.update { it.copy(error = "Enter the server’s https URL.") }
            return
        }
        viewModelScope.launch {
            runCatching { McpClient(url, token.ifEmpty { null }).discover() }
                .onSuccess { (name, found) ->
                    val server = McpServer(url = url, name = name, tools = found)
                    secrets.set(server.id, token)
                    tools.servers = tools.servers + server
                    val active = _state.value.activeConnectors + server.connectorId
                    prefs.edit().putStringSet("connectors.active", active).apply()
                    _state.update { it.copy(servers = tools.servers, activeConnectors = active, notice = "Connected to $name · ${found.size} tools.") }
                }
                .onFailure { e -> _state.update { it.copy(error = "Couldn’t connect to the MCP server. ${e.message}") } }
        }
    }

    fun removeMcpServer(server: McpServer) {
        secrets.set(server.id, null)
        tools.servers = tools.servers.filterNot { it.id == server.id }
        val active = _state.value.activeConnectors - server.connectorId
        prefs.edit().putStringSet("connectors.active", active).apply()
        _state.update { it.copy(servers = tools.servers, activeConnectors = active) }
    }

    fun setMemoryOn(on: Boolean) {
        prefs.edit().putBoolean("memory.on", on).apply()
        _state.update { it.copy(memoryOn = on) }
    }

    fun deleteMemory(memory: Memory) {
        tools.memories = tools.memories.filterNot { it.id == memory.id }
        _state.update { it.copy(memories = tools.memories) }
    }

    fun addMemory(text: String) {
        val trimmed = text.trim().ifEmpty { return }
        tools.memories = listOf(Memory(text = trimmed)) + tools.memories
        _state.update { it.copy(memories = tools.memories) }
    }

    fun clearMemories() {
        tools.memories = emptyList()
        _state.update { it.copy(memories = emptyList()) }
    }

    /** Undo from a box in a reply: removes the reminder or event, or puts memory back. */
    fun undoAction(toolId: String) {
        val chat = _state.value.chat
        val message = chat.messages.firstOrNull { m -> m.tools.any { it.id == toolId } } ?: return
        val box = message.tools.first { it.id == toolId }
        val action = box.action ?: return
        if (actions.undo(action)) {
            val updated = chat.copy(messages = chat.messages.map { m -> if (m.id != message.id) m else m.copy(tools = m.tools.map { if (it.id == toolId) it.copy(status = ToolUse.Status.UNDONE) else it }) })
            _state.update { it.copy(chat = updated, memories = tools.memories) }
            persist()
        } else {
            _state.update { it.copy(error = "Couldn’t undo that. It may already be gone.") }
        }
    }

    /** The tools a reply can call: web search when it's on, plus each active MCP server's (8 each). */
    private fun toolDefinitions(active: Set<String>): List<ToolDefinition> =
        (if (Connector.WEB.id in active) listOf(WebSearch.definition) else emptyList()) +
            tools.servers.filter { it.connectorId in active }.flatMap { server -> server.tools.take(8).map { ToolDefinition(server.url, secrets.get(server.id), it) } }

    /** Runs a tool for the model: web search on device, anything else on its MCP server. */
    private suspend fun call(definition: ToolDefinition, arguments: String): Pair<String, List<ToolUse.Link>> {
        if (WebSearch.isLocal(definition)) {
            val query = WebSearch.query(arguments)
            val hits = WebSearch.search(query)
            return WebSearch.report(hits, query) to hits.take(5).map { ToolUse.Link(it.title, it.url) }
        }
        return runCatching { McpClient(definition.url, definition.token).run(definition.tool.name, arguments) }
            .getOrElse { "The tool could not be reached: ${it.message}" } to emptyList()
    }

    /** Calls a tool and keeps its box in the reply up to date; reads the top web result too. */
    private suspend fun useTool(definition: ToolDefinition, arguments: String, showTool: (ToolUse) -> Unit): String {
        val local = WebSearch.isLocal(definition)
        val host = runCatching { java.net.URL(definition.url).host }.getOrDefault("server")
        val box = if (local) ToolUse(kind = Connector.WEB.id, icon = "magnifyingglass", running = "Searching the web", done = "Searched the web", detail = WebSearch.query(arguments))
            else ToolUse(kind = "mcp", icon = "server.rack", running = "Using ${definition.tool.name}", done = "Used ${definition.tool.name}", detail = host)
        showTool(box)
        val (output, links) = call(definition, arguments)
        val failed = output.startsWith("The tool could not") || output.startsWith("Tool error") || (local && links.isEmpty())
        showTool(box.copy(status = if (failed) ToolUse.Status.FAILED else ToolUse.Status.DONE, links = links))
        // Snippets are thin: read the top result's page too, so the answer has substance.
        if (local && links.isNotEmpty()) {
            val top = links.first()
            val read = ToolUse(kind = "page", icon = "doc.text", running = "Reading the top result", done = "Read the top result", detail = top.host)
            showTool(read)
            val page = PageReader.read(top.url, limit = 900)
            showTool(read.copy(status = if (page != null) ToolUse.Status.DONE else ToolUse.Status.FAILED, links = if (page != null) listOf(top) else emptyList()))
            if (page != null) return output + "\n\nFrom the top result (${top.host}):\n" + page.text
        }
        return output
    }

    /** Changes the reply being written in [chatId]. */
    private fun writingIn(chatId: String, change: (Writing) -> Writing) = _state.update { s ->
        val w = s.writing[chatId] ?: return@update s
        s.copy(writing = s.writing + (chatId to change(w)))
    }

    /** Adds the box to the reply being written in [chatId], or updates it if it's already there. */
    private fun showTool(chatId: String, use: ToolUse) = writingIn(chatId) { w ->
        w.copy(tools = if (w.tools.any { it.id == use.id }) w.tools.map { if (it.id == use.id) use else it } else w.tools + use)
    }

    /** One reply or errand on the model at a time; the others wait their turn. */
    private val engineTurn = kotlinx.coroutines.sync.Mutex()

    /** Each chat's reply, so Stop stops that one. */
    private val jobs = HashMap<String, Job>()

    // MARK: Sending

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val prompt = s.draft.trim()
        val islands = IslandText.present(prompt, s.draftIslands)
        if (PromptBuilder.isPromptTooLong(prompt)) {
            _state.update { it.copy(error = "Shorten your prompt to 6,000 characters or fewer for the phone context.") }
            return
        }
        val fallback = if (s.files.isEmpty()) "Describe this image." else "Summarize the attached file."
        val context = if (s.files.isEmpty()) null else s.files.joinToString("\n\n") { "Attached file “${it.name}”:\n${it.text}" }
        val message = Message(
            // Only islands, no words: the attachment's usual question follows them.
            role = Role.USER, text = if (IslandText.stripped(prompt).isEmpty() && (s.photo != null || s.files.isNotEmpty())) (if (prompt.isEmpty()) fallback else "$prompt $fallback") else prompt,
            islands = islands,
            imageName = s.photo?.name, imageWidth = s.photo?.width ?: 0, imageHeight = s.photo?.height ?: 0,
            files = s.files.map { it.name }, context = context,
        )
        _state.update { it.copy(draft = "", draftIslands = emptyList(), photo = null, files = emptyList(), chat = it.chat.appending(message)) }
        persist() // in History right away, marked as being answered
        reply()
    }

    fun canRegenerate(message: Message): Boolean {
        val s = _state.value
        return !s.isBusy && s.modelReady && message.role == Role.ASSISTANT && s.chat.messages.lastOrNull()?.id == message.id
    }

    fun regenerate() {
        if (_state.value.isBusy) return
        _state.update { it.copy(chat = it.chat.withoutLatestReply()) }
        if (_state.value.chat.messages.lastOrNull()?.role == Role.USER) reply() else persist()
    }

    /** Edit rewinds the chat to the prompt, deletes the photos it used, and puts its text back in the composer. */
    fun edit(message: Message) {
        val s = _state.value
        if (s.isBusy) return
        val index = s.chat.messages.indexOfFirst { it.id == message.id }
        if (index >= 0) photos.delete(s.chat.messages.drop(index).mapNotNull { it.imageName })
        _state.update { it.copy(chat = it.chat.rewoundTo(message.id), draft = message.text, draftIslands = message.islands.filter { i -> i.kind == Island.Kind.CONNECTOR }, performance = "") }
        persist()
    }

    /** Stops this chat's reply; with none, the errand being written. */
    fun stop() {
        val id = _state.value.chat.id
        jobs[id]?.let { it.cancel(); return }
        errandJobs.values.toList().forEach { it.cancel() }
    }

    private fun reply() {
        val replyId = java.util.UUID.randomUUID().toString()
        val manifest = _state.value.model
        val chatId = _state.value.chat.id
        _state.update { it.copy(writing = it.writing + (chatId to Writing(replyId)), performance = "", error = null) }
        val showTool = { use: ToolUse -> showTool(chatId, use) }
        jobs[chatId] = viewModelScope.launch {
            val buffer = StringBuilder()
            var tokens = 0
            var stopped = false
            var started = 0L
            try {
                val s = _state.value
                val asking = s.chat.messages.lastOrNull { it.role == Role.USER }
                // Tools look at the words; islands in the prompt switch their connectors on for this message.
                val question = IslandText.stripped(asking?.text.orEmpty())
                val placed = asking?.islands.orEmpty().filter { it.kind == Island.Kind.CONNECTOR }.map { it.ref }.toSet()
                // Asking for a search in so many words turns it on for this reply.
                val switchedOn = (if (WebSearch.explicitlyAsked(question)) s.activeConnectors + Connector.WEB.id else s.activeConnectors) + placed
                val definitions = toolDefinitions(switchedOn)
                var system = prompts.systemPrompt(s.persona)
                // "Check my calendar" reads the calendar even when it isn't switched on; a source that
                // isn't allowed is named to the model so it says so instead of inventing.
                val (asked, refusal) = ConnectorHub.resolve(app, SourceIntent.wanted(question) - switchedOn)
                val active = switchedOn + asked
                if (refusal.isNotEmpty()) system += "\n\n" + refusal

                // Built-in connectors are read right before the reply and join the system prompt.
                val read = Connector.BUILT_IN.filter { it.id in active && it.id != Connector.WEB.id && it.id != Connector.MAPS.id }
                if (read.isNotEmpty()) {
                    writingIn(chatId) { it.copy(phase = "Checking your sources") }
                    val boxes = read.map { it.use() }
                    boxes.forEach(showTool)
                    val context = ConnectorHub.context(app, active, question, tools.reminders)
                    boxes.forEach { box -> showTool(box.copy(status = if (box.kind == Connector.WIKIPEDIA.id && !context.contains("Wikipedia —")) ToolUse.Status.FAILED else ToolUse.Status.DONE)) }
                    if (context.isNotEmpty()) system += "\n\n" + context
                }
                // Something to do, not just answer: done first, then the reply confirms it.
                ToolAction.detect(question)?.let { action ->
                    val box = action.box()
                    writingIn(chatId) { it.copy(phase = box.running) }
                    showTool(box)
                    val (done, error) = withContext(Dispatchers.IO) { actions.perform(action) }
                    showTool(box.copy(action = done, status = if (error == null) ToolUse.Status.DONE else ToolUse.Status.FAILED, detail = error ?: if (action.kind == ToolAction.Kind.FORGET) done.title else box.detail))
                    _state.update { it.copy(memories = tools.memories) }
                    system += "\n\n" + action.note(error == null, error)
                }
                // Maths, dates, places, a linked page, translation: worked out for the model.
                val found = ToolBelt.run(app, question, active, showTool)
                if (found.isNotEmpty()) system += "\n\n" + found
                system += "\n\n" + CAPABILITIES
                if (s.memoryOn && tools.memories.isNotEmpty()) system += "\n\n" + Memory.prompt(tools.memories)
                if (Connector.WEB.id in active) {
                    system += "\n\n" + WebSearch.GUIDANCE
                    if (WebSearch.wantsSearch(question)) {
                        writingIn(chatId) { it.copy(phase = "Searching the web") }
                        system += "\n\n" + useTool(WebSearch.definition, WebSearch.arguments(WebSearch.searchQuery(question)), showTool)
                    }
                }
                if (definitions.isNotEmpty()) system += "\n\n" + ToolCall.describe(definitions)

                val vision = manifest.vision
                var history = s.chat.messages
                started = System.nanoTime()
                // A reply may call a tool (at most two rounds); its result is added and generation resumes.
                for (round in 0..2) {
                    if (engineTurn.isLocked) writingIn(chatId) { it.copy(phase = "Waiting its turn") }
                    buffer.clear()
                    // The model is loaded, the prompt counted and written in one turn on the engine.
                    engineTurn.withLock {
                        if (!engine.isLoaded) writingIn(chatId) { it.copy(phase = "Loading model") }
                        loadModel(manifest)
                        writingIn(chatId) { it.copy(phase = "Reading prompt") }
                        val prepared = withContext(Dispatchers.Default) { prompts.prepare(history, s.persona, vision && engine.hasVision, system) }
                        if (prepared == PreparedPrompt.TooLong) error("This prompt exceeds the 2,048-token phone context. Shorten your message or attach a smaller file.")
                        prepared as PreparedPrompt.Ready
                        val photo = prepared.photo?.let { slot ->
                            PhotoInput(slot, withContext(Dispatchers.IO) { photos.rgb(prepared.messages[slot.index].imageName!!, slot.width, slot.height) })
                        }
                        engine.generate(prepared.system, prepared.messages, photo).collect { piece ->
                            tokens++
                            if (piece.isNotEmpty()) {
                                buffer.append(piece)
                                writingIn(chatId) { it.copy(text = ToolCall.visible(buffer.toString()), phase = "Generating", tokens = tokens) }
                            }
                        }
                    }
                    val text = buffer.toString()
                    val call = if (round < 2) ToolCall.parse(text) else null
                    val tool = call?.let { (name, _) -> definitions.firstOrNull { it.tool.name == name } }
                    if (call == null || tool == null) break
                    writingIn(chatId) { it.copy(phase = if (WebSearch.isLocal(tool)) "Searching the web" else "Using ${tool.tool.name}") }
                    val output = useTool(tool, call.second, showTool).take(1500)
                    history = history + Message(role = Role.ASSISTANT, text = text) + Message(role = Role.USER, text = "Result of ${tool.tool.name}:\n$output")
                    if (round == 2) error("The model kept calling tools without answering.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                stopped = true // Stop keeps what was written so far.
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                val seconds = if (started > 0) (System.nanoTime() - started) / 1e9 else 0.0
                val performance = if (tokens > 0 && !stopped) "$tokens tokens · ${"%.1f".format(tokens / seconds.coerceAtLeast(0.001))} tok/s · ${freeMegabytes()} MB free" else ""
                finishReply(chatId, replyId, ToolCall.visible(buffer.toString()).trim(), stopped, performance)
            }
        }
    }

    /**
     * Loads the model, unless it could never fit: its weights plus room for the cache and the app
     * must stay under the phone's memory, or Android would kill Loocal mid-reply (iOS refuses the same way).
     */
    private suspend fun loadModel(manifest: ModelManifest) {
        val weights = downloader.file(manifest, manifest.weights)
        if (!engine.isLoaded) {
            val info = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val needed = weights.length() + (manifest.projector?.let { downloader.file(manifest, it).length() } ?: 0L) + 700L * 1024 * 1024
            if (needed > info.totalMem * 0.75) error("${manifest.name} needs more memory than this phone has. Choose a smaller model.")
        }
        engine.load(weights.path, manifest.projector?.let { downloader.file(manifest, it).path })
    }

    private fun freeMegabytes(): Long {
        val info = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    /** Puts the finished reply in its chat, whether it's on screen or not. */
    private fun finishReply(chatId: String, id: String, text: String, stopped: Boolean, performance: String) {
        jobs.remove(chatId)
        var finished: Chat? = null
        _state.update { s ->
            // A reply that stops mid-tool leaves no box spinning.
            val boxes = s.writing[chatId]?.tools.orEmpty().map { if (it.status == ToolUse.Status.RUNNING) it.copy(status = ToolUse.Status.FAILED) else it }
            val onScreen = s.chat.id == chatId
            val base = if (onScreen) s.chat else s.chats.firstOrNull { it.id == chatId }
            val chat = if (base == null || (text.isEmpty() && boxes.isEmpty())) base else base.appending(Message(id = id, role = Role.ASSISTANT, text = text, tools = boxes))
            finished = chat
            s.copy(
                chat = if (onScreen && chat != null) chat else s.chat, writing = s.writing - chatId,
                performance = if (onScreen) performance else s.performance,
                interrupted = if (stopped && text.isNotEmpty()) s.interrupted + id else s.interrupted,
                notice = if (stopped) "Generation stopped. Your partial reply is saved." else s.notice,
            )
        }
        finished?.let { store.save(it) }
        _state.update { it.copy(chats = store.loadChats()) }
        pumpErrands()
    }

    private fun persist() {
        store.save(_state.value.chat)
        _state.update { it.copy(chats = store.loadChats()) }
    }

    fun photoFile(name: String) = photos.file(name)

    // MARK: Voice

    fun readAloud(text: String) = speaker.say(text)

    /** The Kokoro natural-voice engine: download progress, or an error. */
    val kokoroStatus = MutableStateFlow(DownloadStatus())
    private var kokoroJob: Job? = null

    fun downloadKokoro() {
        if (kokoroJob?.isActive == true) return
        if (!onWifi()) { kokoroStatus.value = DownloadStatus(error = "Connect to Wi-Fi to download the voice engine."); return }
        kokoroJob = viewModelScope.launch {
            kokoroStatus.value = DownloadStatus(progress = 0f)
            val result = runCatching { speaker.kokoro.download { p -> kokoroStatus.value = DownloadStatus(progress = p) } }
            kokoroStatus.value = DownloadStatus(error = result.exceptionOrNull()?.takeIf { it !is kotlinx.coroutines.CancellationException }?.let { it.message ?: "The download stopped. Try again." })
        }
    }

    fun deleteKokoro() {
        speaker.stop(); speaker.kokoro.unload(); speaker.kokoro.delete()
        if (speaker.selectedName?.startsWith(NeuralVoice.PREFIX) == true) speaker.selectedName = null
        kokoroStatus.value = DownloadStatus()
    }

    fun conversation() = VoiceConversation(app, speaker, ::voiceReply, ::recordVoiceTurn)

    /** Everything Loocal works out before a spoken answer: actions, tool results, web, memory. */
    private suspend fun voiceContext(question: String): String {
        val parts = mutableListOf<String>()
        ToolAction.detect(question)?.let { action ->
            val (_, error) = withContext(Dispatchers.IO) { actions.perform(action) }
            _state.update { it.copy(memories = tools.memories) }
            parts.add(action.note(error == null, error))
        }
        ToolBelt.run(app, question, emptySet()) {}.takeIf { it.isNotEmpty() }?.let(parts::add)
        val s = _state.value
        val (asked, refusal) = ConnectorHub.resolve(app, SourceIntent.wanted(question))
        if (asked.isNotEmpty()) parts.add(ConnectorHub.context(app, asked, question, tools.reminders))
        if (refusal.isNotEmpty()) parts.add(refusal)
        if ((Connector.WEB.id in s.activeConnectors && WebSearch.wantsSearch(question)) || WebSearch.explicitlyAsked(question)) {
            parts.add(call(WebSearch.definition, WebSearch.arguments(WebSearch.searchQuery(question))).first + "\nAnswer in a few spoken sentences.")
        }
        if (s.memoryOn && tools.memories.isNotEmpty()) parts.add(Memory.prompt(tools.memories))
        parts.add(CAPABILITIES)
        return parts.joinToString("\n\n")
    }

    /** A spoken reply from the selected local model (iOS uses Apple's model when it can; Android has no equivalent). */
    private suspend fun voiceReply(history: List<Pair<Boolean, String>>, update: (String) -> Unit): String {
        val s = _state.value
        if (!s.modelReady) error("Download a model first.")
        val question = history.lastOrNull { it.first }?.second.orEmpty()
        val system = prompts.systemPrompt(s.persona) + "\n\n" + VoiceConversation.INSTRUCTIONS + "\n\n" + voiceContext(question)
        val manifest = s.model
        val messages = history.map { (user, text) -> Message(role = if (user) Role.USER else Role.ASSISTANT, text = text) }
        val buffer = StringBuilder()
        engineTurn.withLock {
            loadModel(manifest)
            val prepared = withContext(Dispatchers.Default) { prompts.prepare(messages, s.persona, false, system) } as? PreparedPrompt.Ready
                ?: error("That was too long for the phone's context.")
            engine.generate(prepared.system, prepared.messages).collect { piece -> if (piece.isNotEmpty()) { buffer.append(piece); update(ToolCall.visible(buffer.toString())) } }
        }
        return ToolCall.visible(buffer.toString()).trim()
    }

    /** Errand mode: each thing you say becomes an errand; the bot says it's on it. Nothing goes into the chat. */
    fun errandVoice() = VoiceConversation(app, speaker, { history, _ -> dispatchSpoken(history.last().second) }, { _, _ -> })

    private fun dispatchSpoken(said: String): String {
        val s = _state.value
        if (!s.modelReady) return "I need a model first. Download one, then send me."
        if (s.runs.count { it.isActive } >= ErrandStore.CREW_SIZE) return "My hands are full. Ask me again in a minute."
        runErrand(Errand.title(said), said, spokenSources(said))
        val replies = listOf("On it.", "Got it. I’m on it.", "Leave it with me.", "Sure. Back soon.")
        return replies[kotlin.math.abs(said.hashCode()) % replies.size]
    }

    /** Spoken errands can't pick sources, so they read the ones the words point to, among those already allowed. */
    private fun spokenSources(said: String): List<String> {
        val text = said.lowercase()
        val ids = mutableListOf<String>()
        val calendarOk = app.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (calendarOk && listOf("calendar", "meeting", "agenda", "schedule", "today", "tomorrow", "week", "rendez-vous", "réunion", "semaine", "demain").any { text.contains(it) }) ids.add(Connector.CALENDAR.id)
        if (listOf("remind", "reminder", "to-do", "todo", "to do", "task", "rappel", "tâche").any { text.contains(it) }) ids.add(Connector.REMINDERS.id)
        if (listOf("clipboard", "copied", "presse-papier", "copié").any { text.contains(it) }) ids.add(Connector.CLIPBOARD.id)
        if (WebSearch.wantsSearch(said) || listOf("find", "web", "online", "trouve").any { text.contains(it) }) ids.add(Connector.WEB.id)
        return ids
    }

    /** Voice turns are kept in the current chat, like typed ones. */
    private fun recordVoiceTurn(user: String, reply: String) {
        if (_state.value.isBusy) return
        _state.update { it.copy(chat = it.chat.appending(Message(role = Role.USER, text = user)).appending(Message(role = Role.ASSISTANT, text = reply))) }
        persist()
    }

    // MARK: Errands

    /** Each errand gathers its sources as soon as it's sent, all at once; only the writing waits its turn. */
    private val errandContexts = mutableMapOf<String, Deferred<String>>()
    /** Errands being written, by run id; they start together and take turns on the model. */
    private val errandJobs = HashMap<String, Job>()

    private fun updateDesk(change: (List<Errand>, List<ErrandRun>) -> Pair<List<Errand>, List<ErrandRun>>) {
        _state.update { s -> val (e, r) = change(s.errands, s.runs); s.copy(errands = e, runs = r) }
        desk.save(_state.value.errands, _state.value.runs)
    }

    private fun updateRun(id: String, change: (ErrandRun) -> ErrandRun) = updateDesk { e, r -> e to r.map { if (it.id == id) change(it) else it } }

    fun setErrandDraft(text: String) = _state.update { it.copy(errandDraft = text) }
    fun setErrandCadence(cadence: Cadence) = _state.update { it.copy(errandCadence = cadence) }
    fun setErrandTime(hour: Int, minute: Int) = _state.update { it.copy(errandHour = hour, errandMinute = minute) }
    fun toggleErrandSource(id: String) = _state.update { s -> s.copy(errandSources = if (id in s.errandSources) s.errandSources - id else s.errandSources + id) }

    fun chooseErrandPersona(id: String?) {
        prefs.edit().putString("errands.persona", id).apply()
        _state.update { it.copy(errandPersonaId = id) }
    }

    fun useIdea(idea: ErrandIdea) = _state.update {
        it.copy(errandDraft = idea.instructions, errandCadence = idea.cadence, errandSources = idea.sources.toSet(), errandHour = idea.hour, errandMinute = idea.minute)
    }

    /** Sends the composer's errand off now, or schedules it. Returns false when the crew is full. */
    fun sendErrand(): Boolean {
        val s = _state.value
        val instructions = s.errandDraft.trim().ifEmpty { return false }
        if (s.errandCadence == Cadence.NOW && s.runs.count { it.isActive } >= ErrandStore.CREW_SIZE) {
            _state.update { it.copy(error = "The crew’s full: ${ErrandStore.CREW_SIZE} errands are on the go. Send this one when one finishes.") }
            return false
        }
        val title = ErrandIdea.ALL.firstOrNull { it.instructions == instructions }?.title ?: Errand.title(instructions)
        val cadence = s.errandCadence; val sources = s.errandSources.toList()
        _state.update { it.copy(errandDraft = "", errandCadence = Cadence.NOW, errandSources = emptySet()) }
        if (cadence == Cadence.NOW) runErrand(title, instructions, sources)
        else {
            // Weekly errands repeat on today's weekday; the "Plan my week" idea keeps Mondays.
            val planner = ErrandIdea.ALL.any { it.instructions == instructions && it.cadence == Cadence.WEEKLY }
            val weekday = if (planner) java.util.Calendar.MONDAY else java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            val errand = Errand(title = title, instructions = instructions, cadence = cadence, hour = s.errandHour, minute = s.errandMinute, weekday = weekday, sources = sources)
            // Scheduling the same errand again replaces it rather than doubling up.
            updateDesk { e, r -> (listOf(errand) + e.filterNot { it.instructions == instructions && it.cadence == cadence }) to r }
            RoutineAlarm.reschedule(app, _state.value.errands)
            _state.update { it.copy(notice = "Scheduled · ${errand.schedule}. It’s in Routines.") }
        }
        return true
    }

    /** Sends an errand off. It joins the crew right away and runs as soon as the model is free. */
    fun runErrand(title: String, instructions: String, sources: List<String>, errandId: String? = null) {
        val s = _state.value
        if (s.runs.count { it.isActive } >= ErrandStore.CREW_SIZE) { _state.update { it.copy(error = "The crew’s full: ${ErrandStore.CREW_SIZE} errands are on the go. Try again when one finishes.") }; return }
        if (!s.modelReady) { _state.update { it.copy(error = "Download ${s.model.name} first. Errands run on this phone.") }; return }
        var run = ErrandRun(errandId = errandId, title = title, instructions = instructions, sources = sources, personaId = s.errandPersonaId,
            stage = if (sources.all { it == Connector.WEB.id }) ErrandRun.NEXT_UP else "Gathering")
        if (errandId != null) updateDesk { e, r -> e.map { if (it.id == errandId) it.copy(lastRun = System.currentTimeMillis()) else it } to r }
        // A one-off "remind me…" or "add to my calendar…" is simply done, no model needed.
        if (errandId == null) ToolAction.detect(instructions)?.let { action ->
            run = run.copy(stage = action.box().running)
            updateDesk { e, r -> e to (listOf(run) + r) }
            viewModelScope.launch {
                val (done, error) = withContext(Dispatchers.IO) { actions.perform(action) }
                _state.update { it.copy(memories = tools.memories) }
                if (error != null) updateRun(run.id) { it.copy(status = ErrandRun.Status.FAILED, note = error, stage = null) }
                else updateRun(run.id) { it.copy(status = ErrandRun.Status.DONE, stage = null, text = "**${done.box().done}.**\n\n" + listOfNotNull(done.title, done.whenText).joinToString(" · ")) }
            }
            return
        }
        updateDesk { e, r -> e to (listOf(run) + r) }
        prepare(run)
        pumpErrands()
    }

    /** Fetches what an errand reads, in parallel with every other errand on the go. */
    private fun prepare(run: ErrandRun) {
        val picked = run.sources.toSet() - Connector.WEB.id // the web is searched while writing
        // "What have I got next month" reads the calendar even if no source was picked.
        val (asked, refusal) = ConnectorHub.resolve(app, SourceIntent.wanted(run.instructions) - picked)
        val active = picked + asked
        if (active.isEmpty()) { if (refusal.isNotEmpty()) errandContexts[run.id] = viewModelScope.async { refusal }; return }
        val names = _state.value.connectors.filter { it.id in active }.joinToString(", ") { it.name }
        errandContexts[run.id] = viewModelScope.async {
            updateRun(run.id) { if (it.isActive) it.copy(stage = "Reading $names") else it }
            val context = ConnectorHub.context(app, active, run.instructions, tools.reminders)
            updateRun(run.id) { if (it.status == ErrandRun.Status.QUEUED) it.copy(stage = ErrandRun.NEXT_UP) else it }
            listOf(context, refusal).filter { it.isNotEmpty() }.joinToString("\n\n")
        }
    }

    /** Hands the model to the oldest waiting errand whenever it's free. */
    fun pumpErrands() {
        val s = _state.value
        if (!s.modelReady) return
        // Waiting errands start together, up to three on the go; the model takes turns between them.
        val room = MAX_REPLIES - s.runs.count { it.status == ErrandRun.Status.RUNNING }
        s.runs.filter { it.status == ErrandRun.Status.QUEUED }.reversed().take(room.coerceAtLeast(0)).forEach { run ->
            updateRun(run.id) { it.copy(status = ErrandRun.Status.RUNNING, stage = "Thinking", tokens = 0) }
            errandJobs[run.id] = viewModelScope.launch { performErrand(run) }
        }
    }

    private suspend fun performErrand(run: ErrandRun) {
        val buffer = StringBuilder()
        var tokens = 0
        try {
            val s = _state.value
            // A persona's voice and know-how come first; the errand rules still apply.
            val persona = s.personas.firstOrNull { it.id == run.personaId }?.prompt?.trim().orEmpty()
            var system = (if (persona.isEmpty()) "" else persona + "\n\n") + ErrandStore.SYSTEM_PROMPT + "\n\nIt is now " + ErrandStore.now() + "."
            if (s.memoryOn && tools.memories.isNotEmpty()) system += "\n\n" + Memory.prompt(tools.memories)
            ToolBelt.run(app, run.instructions, emptySet()) {}.takeIf { it.isNotEmpty() }?.let { system += "\n\n" + it }
            errandContexts[run.id]?.let { gathered ->
                updateRun(run.id) { it.copy(stage = "Finishing reading") }
                gathered.await().takeIf { it.isNotEmpty() }?.let { system += "\n\n" + it }
            }
            val definitions = toolDefinitions(run.sources.toSet())
            if (Connector.WEB.id in run.sources) {
                system += "\n\n" + WebSearch.GUIDANCE
                if (WebSearch.wantsSearch(run.instructions)) {
                    updateRun(run.id) { it.copy(stage = "Searching the web") }
                    system += "\n\n" + call(WebSearch.definition, WebSearch.arguments(WebSearch.searchQuery(run.instructions))).first
                }
            }
            if (definitions.isNotEmpty()) system += "\n\n" + ToolCall.describe(definitions)
            updateRun(run.id) { it.copy(stage = "Thinking") }
            val manifest = s.model
            var history = listOf(Message(role = Role.USER, text = run.instructions))
            for (round in 0..2) {
                buffer.clear()
                if (engineTurn.isLocked) updateRun(run.id) { it.copy(stage = "Waiting its turn") }
                engineTurn.withLock {
                    updateRun(run.id) { it.copy(stage = "Thinking") }
                    loadModel(manifest)
                    val prepared = withContext(Dispatchers.Default) { prompts.prepare(history, null, false, system) } as? PreparedPrompt.Ready
                        ?: error("The errand is too long for the phone's context.")
                    engine.generate(prepared.system, prepared.messages).collect { piece ->
                        tokens++
                        if (piece.isNotEmpty()) { buffer.append(piece); updateRunLight(run.id, ToolCall.visible(buffer.toString()), tokens) }
                    }
                }
                val text = buffer.toString()
                val call = if (round < 2) ToolCall.parse(text) else null
                val tool = call?.let { (name, _) -> definitions.firstOrNull { it.tool.name == name } } ?: break
                updateRun(run.id) { it.copy(stage = if (WebSearch.isLocal(tool)) "Searching the web" else "Using ${tool.tool.name}") }
                val output = call(tool, call.second).first.take(1500)
                history = history + Message(role = Role.ASSISTANT, text = text) + Message(role = Role.USER, text = "Result of ${tool.tool.name}:\n$output")
            }
            updateRun(run.id) { it.copy(status = ErrandRun.Status.DONE, stage = null, text = ToolCall.visible(buffer.toString()).trim(), tokens = tokens) }
            _state.value.runs.firstOrNull { it.id == run.id }?.let { RoutineAlarm.announce(app, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateRun(run.id) { it.copy(status = ErrandRun.Status.STOPPED, stage = null, text = ToolCall.visible(buffer.toString()).trim()) }
            _state.update { it.copy(notice = "Errand stopped. Its partial result is saved.") }
        } catch (e: Exception) {
            updateRun(run.id) { it.copy(status = ErrandRun.Status.FAILED, stage = null, note = e.message, text = ToolCall.visible(buffer.toString()).trim()) }
        }
        errandContexts.remove(run.id)
        errandJobs.remove(run.id)
        pumpErrands()
    }

    /** Streaming text into a run without writing the archive for every token. */
    private var lastDeskSave = 0L
    private fun updateRunLight(id: String, text: String, tokens: Int) {
        _state.update { s -> s.copy(runs = s.runs.map { if (it.id == id) it.copy(text = text, tokens = tokens, stage = "Writing") else it }) }
        if (System.currentTimeMillis() - lastDeskSave > 1000) { lastDeskSave = System.currentTimeMillis(); desk.save(_state.value.errands, _state.value.runs) }
    }

    /** Stops one errand: a waiting one just leaves the crew, the writing one stops mid-way. */
    fun cancelErrand(run: ErrandRun) {
        val current = _state.value.runs.firstOrNull { it.id == run.id }?.takeIf { it.isActive } ?: return
        if (current.status == ErrandRun.Status.RUNNING) errandJobs[run.id]?.cancel()
        else { errandContexts.remove(run.id)?.cancel(); updateRun(run.id) { it.copy(status = ErrandRun.Status.STOPPED, stage = null) } }
    }

    fun rerun(run: ErrandRun) = runErrand(run.title, run.instructions, run.sources)
    fun markSeen(id: String) = updateRun(id) { if (!it.isActive && !it.seen) it.copy(seen = true) else it }
    fun deleteRun(run: ErrandRun) = updateDesk { e, r -> e to r.filterNot { it.id == run.id } }

    fun togglePause(errand: Errand) {
        // Resuming starts the clock again, so a paused morning doesn't fire on unpause.
        updateDesk { e, r -> e.map { if (it.id == errand.id) it.copy(paused = !it.paused, lastRun = if (it.paused) System.currentTimeMillis() else it.lastRun) else it } to r }
        RoutineAlarm.reschedule(app, _state.value.errands)
    }

    fun deleteRoutine(errand: Errand) {
        updateDesk { e, r -> e.filterNot { it.id == errand.id } to r }
        RoutineAlarm.reschedule(app, _state.value.errands)
    }

    /** Runs every routine whose time has come: at launch, on return to the app, and every 30 s while open. */
    fun runDueErrands() {
        val s = _state.value
        if (!s.modelReady) return
        for (errand in s.errands.filter { it.isDue }.sortedBy { it.lastRun ?: it.createdAt }) {
            val now = _state.value
            if (now.runs.count { it.isActive } >= ErrandStore.CREW_SIZE || now.runs.any { it.isActive && it.errandId == errand.id }) continue
            runErrand(errand.title, errand.instructions, errand.sources, errand.id)
        }
    }

    /** Opens an errand's result as a regular chat, to keep going from it. */
    fun continueInChat(run: ErrandRun) {
        leaveTemporary()
        _state.update { it.copy(chat = Chat(messages = listOf(Message(role = Role.USER, text = run.instructions), Message(role = Role.ASSISTANT, text = run.text))), performance = "") }
        persist()
    }

    override fun onCleared() { engine.unload(); speaker.shutdown() }

    companion object {
        /** What the app around the model can do, so it never claims it can't look things up. */
        const val CAPABILITIES = "You run inside Loocal, on the user's phone. Loocal itself can: search the web and read links, find places near the user and travel times (using their location), do exact maths and date counts, translate, read the user's calendar and reminders and add to them, and remember things. When Loocal has done one of these for this message, its results are above: use them. Never say you have no location, internet or calendar access. Anything about the user's own life (their events, reminders, plans) must come from results above: never make it up. If they ask about their calendar and no calendar is above, say Loocal couldn't read it. If a request needs one of these and no result is above, ask them to say it plainly, for example “find restaurants near me”."
    }
}
