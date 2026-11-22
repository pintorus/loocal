package app.loocal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.loocal.domain.Chat
import app.loocal.domain.Message
import app.loocal.domain.Persona
import app.loocal.domain.Role

@Composable
fun LoocalApp(vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    Scaffold(bottomBar = { BottomBar(state.tab, vm::select) }) { padding ->
        // consumeWindowInsets: the bottom bar's space is already reserved, so imePadding
        // in the chat must not add it again under the keyboard.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            when (state.tab) {
                Tab.HISTORY -> HistoryScreen(state.chats, vm::open, vm::delete)
                Tab.CHAT -> ChatScreen(state, vm)
                Tab.SETTINGS -> SettingsScreen(state, vm)
            }
        }
    }
    state.error?.let { message ->
        AlertDialog(onDismissRequest = vm::dismissError, confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } }, text = { Text(message) })
    }
}

/** Three label-free icons, as on iOS: History, New chat, Settings. */
@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        listOf(Tab.HISTORY to Icons.Outlined.History, Tab.CHAT to Icons.Outlined.Edit, Tab.SETTINGS to Icons.Outlined.Settings).forEach { (tab, icon) ->
            NavigationBarItem(selected = selected == tab, onClick = { onSelect(tab) }, icon = { Icon(icon, contentDescription = tab.name.lowercase()) })
        }
    }
}

@Composable
private fun ChatScreen(state: UiState, vm: ChatViewModel) {
    val list = rememberLazyListState()
    val count = state.chat.messages.size + if (state.streaming != null) 1 else 0
    LaunchedEffect(count, state.streaming?.length) { if (count > 0) list.animateScrollToItem(count - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TextButton(onClick = vm::toggleTemporary, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (state.chat.isTemporary) "Temporary chat" else "Loocal")
        }
        if (!state.modelReady) {
            Text("Download the model in Settings to start.", Modifier.padding(16.dp).align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.chat.messages, key = { it.id }) { message ->
                val isLatestReply = message.role == Role.ASSISTANT && message.id == state.chat.messages.last().id
                Bubble(message, onEdit = { vm.edit(message) }, onRegenerate = if (isLatestReply) vm::regenerate else null)
            }
            state.streaming?.let { text -> item { Bubble(Message(role = Role.ASSISTANT, text = text.ifEmpty { "…" }), null, null) } }
        }
        Composer(state, vm)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(message: Message, onEdit: (() -> Unit)?, onRegenerate: (() -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val isUser = message.role == Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(12.dp),
        ) {
            Text(message.text)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { clipboard.setText(AnnotatedString(message.text)); menu = false })
                if (isUser && onEdit != null) DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); menu = false })
                if (onRegenerate != null) DropdownMenuItem(text = { Text("Regenerate") }, onClick = { onRegenerate(); menu = false })
            }
        }
    }
}

@Composable
private fun Composer(state: UiState, vm: ChatViewModel) {
    var personaMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = vm::setDraft,
            modifier = Modifier.weight(1f),
            placeholder = { Text(state.persona?.let { "Ask ${it.name}" } ?: "Ask anything") },
            maxLines = 6,
        )
        Spacer(Modifier.width(8.dp))
        Box {
            if (state.isBusy) {
                IconButton(onClick = vm::stop) { Icon(Icons.Outlined.StopCircle, "Stop") }
            } else {
                // Tap sends; the persona menu opens from the adjacent button (Android stand-in for the iOS hold gesture).
                Row {
                    if (state.personas.isNotEmpty()) IconButton(onClick = { personaMenu = true }) { Icon(Icons.Outlined.Face, "Persona") }
                    IconButton(onClick = vm::send, enabled = state.canSend) { Icon(Icons.Outlined.ArrowUpward, "Send") }
                }
            }
            DropdownMenu(expanded = personaMenu, onDismissRequest = { personaMenu = false }) {
                DropdownMenuItem(text = { Text("No persona") }, onClick = { vm.choosePersona(null); personaMenu = false })
                state.personas.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { vm.choosePersona(p.id); personaMenu = false }) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryScreen(chats: List<Chat>, onOpen: (Chat) -> Unit, onDelete: (Chat) -> Unit) {
    if (chats.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No chats yet") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(chats, key = { it.id }) { chat ->
            var menu by remember { mutableStateOf(false) }
            Box {
                ListItem(headlineContent = { Text(chat.title, maxLines = 1) }, modifier = Modifier.combinedClickable(onClick = { onOpen(chat) }, onLongClick = { menu = true }))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(chat); menu = false })
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, vm: ChatViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Model", style = MaterialTheme.typography.titleMedium)
        when {
            state.modelReady -> Text("Downloaded")
            state.downloadProgress != null -> LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
            else -> Button(onClick = vm::downloadModel) { Text("Download model") }
        }
        HorizontalDivider()
        PersonaEditor(state.personas, vm::savePersonas)
    }
}

@Composable
private fun PersonaEditor(personas: List<Persona>, onSave: (List<Persona>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    Text("Personas", style = MaterialTheme.typography.titleMedium)
    personas.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, Modifier.weight(1f))
            IconButton(onClick = { onSave(personas - p) }) { Icon(Icons.Outlined.Delete, "Delete ${p.name}") }
        }
    }
    if (personas.size < Persona.LIMIT) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(prompt, { prompt = it }, label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = {
            onSave(personas + Persona(name = name.trim(), icon = Persona.ICONS[personas.size % Persona.ICONS.size], prompt = prompt.trim()))
            name = ""; prompt = ""
        }) { Text("Add persona") }
    }
}
