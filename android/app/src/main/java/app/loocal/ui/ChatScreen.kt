package app.loocal.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loocal.domain.Message
import app.loocal.domain.Role
import app.loocal.ui.theme.InstrumentSerif
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import java.io.File
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import app.loocal.tools.Connector
import app.loocal.tools.Places
import app.loocal.tools.ToolAction
import app.loocal.tools.ToolUse

/** Port of iOS `ChatView` (RootView.swift). */
@Composable
fun ChatScreen(state: UiState, vm: ChatViewModel, openPersonas: () -> Unit, openConversation: () -> Unit = {}) {
    val focus = LocalFocusManager.current
    var personaOpen by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf<Message?>(null) }
    val composerFocus = remember { FocusRequester() }
    var composerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val context = LocalContext.current
    var attachOpen by remember { mutableStateOf(false) }
    var trayPage by remember { mutableStateOf("main") }
    var serverPrompt by remember { mutableStateOf(false) }
    // Runs once the permissions it needs have been answered (granted or not: the action reports a refusal).
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterPermission?.invoke(); afterPermission = null }
    fun withPermissions(needed: List<String>, then: () -> Unit) {
        val missing = needed.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else { afterPermission = then; askPermissions.launch(missing.toTypedArray()) }
    }
    /** Asks for what the message will need (calendar, location, notifications), then sends. */
    fun send() {
        val text = state.draft
        val needed = buildList {
            when (ToolAction.detect(text)?.kind) {
                ToolAction.Kind.EVENT -> { add(Manifest.permission.READ_CALENDAR); add(Manifest.permission.WRITE_CALENDAR) }
                ToolAction.Kind.REMINDER -> if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                else -> {}
            }
            if (Places.detect(text) != null) add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        withPermissions(needed) { vm.send() }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attachPhoto) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(vm::attachPhoto) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.attachFiles(uris) }
    fun attach(item: TrayItem) {
        when (item.id) {
            "connectors" -> { trayPage = "connectors"; return }
            "back" -> { trayPage = "main"; return }
            "server" -> { attachOpen = false; trayPage = "main"; serverPrompt = true; return }
            Connector.CALENDAR.id -> { withPermissions(if (item.on) emptyList() else listOf(Manifest.permission.READ_CALENDAR)) { vm.toggleConnector(item.id) }; return } // toggles keep the tray open
        }
        if (state.connectors.any { it.id == item.id }) { vm.toggleConnector(item.id); return }
        attachOpen = false
        trayPage = "main"
        when (item.id) {
            "photos" -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "camera" -> {
                val file = File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "capture.jpg") }
                cameraUri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                runCatching { takePhoto.launch(cameraUri!!) }.onFailure { vm.showError("This device has no camera available.") }
            }
            "files" -> pickFiles.launch(arrayOf("text/*", "application/pdf", "application/json", "text/csv", "text/markdown", "application/xml", "text/html"))
        }
    }

    Column(Modifier.fillMaxSize().background(ink.paper)) {
        // Model pill, with the temporary-chat note hanging under it.
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)
                .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        ) {
            ModelSelector(state.models, state.modelId, vm::chooseModel, disabled = state.isBusy, dimmed = !state.modelReady)
            androidx.compose.animation.AnimatedVisibility(
                state.isTemporary,
                enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier.offset(y = 46.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    LoopGhostMark(1f, Modifier.width(22.dp), color = ink.secondary)
                    Text(
                        if (state.chat.messages.isEmpty()) "Temporary chat · nothing is saved" else "Temporary",
                        style = geist(12, FontWeight.Medium, ink.secondary),
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = state.chat.messages.isEmpty() && state.generatingId == null,
                transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.98f)) togetherWith fadeOut(tween(200)) },
                label = "empty",
            ) { empty ->
                if (empty) {
                    EmptyChat(temporary = state.isTemporary, toggle = vm::toggleTemporary, bottomInset = with(density) { composerHeight.toDp() })
                } else {
                    Transcript(
                        state = state, vm = vm, held = held, setHeld = { held = it },
                        bottomInset = with(density) { composerHeight.toDp() },
                        edit = { vm.edit(it); composerFocus.requestFocus() },
                        select = { selecting = it },
                    )
                }
            }

            // The chat runs on under the prompt bar, fading into the paper toward the nav.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { composerHeight = it.height }
                    .bottomFade(),
            ) {
                AnimatedVisibility(
                    state.chat.messages.isEmpty() && state.modelReady && state.draft.isEmpty() && !state.isBusy,
                    enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
                ) { Suggestions { vm.setDraft(it); composerFocus.requestFocus() } }
                Composer(
                    state, vm, personaOpen,
                    // Only one tray at a time: opening + closes the persona capsule and vice versa.
                    { personaOpen = it; if (it) attachOpen = false }, attachOpen,
                    { attachOpen = it; if (it) { personaOpen = false; focus.clearFocus() } else trayPage = "main" },
                    ::attach, composerFocus, openPersonas, trayPage, ::send,
                    { withPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) openConversation() else vm.showError("Loocal needs the microphone for conversation mode. Allow it in Settings.") } },
                )
            }
        }
    }

    selecting?.let { message -> SelectTextSheet(message.text) { selecting = null } }
    if (serverPrompt) McpServerDialog(dismiss = { serverPrompt = false }) { address, token -> serverPrompt = false; vm.addMcpServer(address, token) }
}

/** Add an MCP server: its https address and an optional bearer token (kept encrypted on the phone). */
@Composable
private fun McpServerDialog(dismiss: () -> Unit, connect: (String, String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = ink.paper,
        title = { Text("Add MCP server", style = geist(17, FontWeight.Medium, ink.primary)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Loocal connects over HTTPS and lets the model call this server’s tools. Its token stays encrypted on this phone.", style = geist(13, color = ink.secondary))
                listOf(Triple(address, "https://server.example/mcp", { v: String -> address = v }), Triple(token, "Token (optional)", { v: String -> token = v })).forEachIndexed { i, (value, hint, set) ->
                    Box(Modifier.fillMaxWidth().background(ink.fill, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (value.isEmpty()) Text(hint, style = geist(15, color = ink.tertiary))
                        BasicTextField(
                            value, set, singleLine = true, textStyle = geist(15, color = ink.primary), cursorBrush = SolidColor(ink.primary),
                            visualTransformation = if (i == 1) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { Text("Connect", style = geist(15, FontWeight.Medium, ink.primary), modifier = Modifier.padding(8.dp).pressable { connect(address, token) }) },
        dismissButton = { Text("Cancel", style = geist(15, color = ink.secondary), modifier = Modifier.padding(8.dp).pressable(onClick = dismiss)) },
    )
}

/** Empty chat: the logo is the switch (loop for a normal chat, ghost for temporary) and a serif title. */
@Composable
private fun EmptyChat(temporary: Boolean, toggle: () -> Unit, bottomInset: androidx.compose.ui.unit.Dp) {
    val haptics = LocalHapticFeedback.current
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(if (temporary) 1f else 0f, tween(1300, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "ghost")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = bottomInset),
    ) {
        Box(
            Modifier.rise(appeared)
                .semantics { contentDescription = if (temporary) "Temporary chat. Tap for a normal chat" else "Start a temporary chat" }
                .pressable(0.85f) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); toggle() }
                .padding(14.dp),
        ) { LoopGhostMark(progress, Modifier.width(46.dp)) }
        Spacer(Modifier.height(18.dp))
        AnimatedContent(
            temporary,
            transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut(tween(250)) + scaleOut(targetScale = 0.96f)) },
            label = "title", modifier = Modifier.rise(appeared, 60),
        ) { temp ->
            val (plain, italic) = if (temp) "Off the " to "record." else "What’s on " to "your mind?"
            Text(
                buildAnnotatedString {
                    append(plain)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontFamily = InstrumentSerif)) { append(italic) }
                },
                style = serif(40, color = ink.primary).copy(textAlign = TextAlign.Center, lineHeight = 44.sp),
            )
        }
    }
}

private data class Prompt(val title: String, val detail: String, val text: String)

private val prompts = listOf(
    Prompt("Think out loud", "with an idea I’m stuck on", "Help me think through an idea: "),
    Prompt("Draft a note", "short, warm, to the point", "Draft a short, warm note to "),
)

@Composable
private fun Suggestions(choose: (String) -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 10.dp),
    ) {
        prompts.forEachIndexed { i, prompt ->
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.rise(appeared, 150 + i * 70, 10f)
                    .border(1.dp, ink.hairline, RoundedCornerShape(16.dp))
                    .pressable { choose(prompt.text) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(prompt.title, style = geist(14, FontWeight.Medium, ink.primary))
                Text(prompt.detail, style = geist(13, color = ink.secondary))
            }
        }
    }
}

/**
 * The latest turn (your last message and its reply) gets at least a screen of height.
 * Sending scrolls your message to the top once, then the reply writes downward into the
 * space below it, so nothing already on screen moves while it streams.
 */
@Composable
private fun Transcript(
    state: UiState, vm: ChatViewModel, held: String?, setHeld: (String?) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp, edit: (Message) -> Unit, select: (Message) -> Unit,
) {
    val messages = state.chat.messages
    val split = messages.indexOfLast { it.role == Role.USER }.let { if (it < 0) messages.size else it }
    val scroll = rememberScrollState()
    var turnTop by remember { mutableIntStateOf(0) }

    // A new prompt scrolls its turn to the top; opening a chat starts at the bottom.
    LaunchedEffect(state.chat.id) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(messages.size) {
        if (messages.lastOrNull()?.role == Role.USER) scroll.animateScrollTo(turnTop, spring(dampingRatio = 0.9f, stiffness = 200f))
    }

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(held) { detectTapGestures { setHeld(null) } }) {
        val viewport = maxHeight - bottomInset
        Column(
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 16.dp + bottomInset),
        ) {
            messages.take(split).forEach { MessageRow(it, state, vm, held, setHeld, edit, select) }
            Column(
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.heightIn(min = (viewport - 32.dp).coerceAtLeast(0.dp)).onPlaced { turnTop = it.positionInParent().y.toInt() },
            ) {
                messages.drop(split).forEach { MessageRow(it, state, vm, held, setHeld, edit, select) }
                if (state.generatingId != null) {
                    AssistantMessage(Message(id = state.generatingId, role = Role.ASSISTANT, text = state.streaming, tools = state.streamingTools), generating = true, interrupted = false, phase = state.phase, undo = vm::undoAction)
                }
                if (!state.isBusy && state.performance.isNotEmpty()) {
                    Text(state.performance, style = geist(11, color = ink.tertiary))
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: Message, state: UiState, vm: ChatViewModel, held: String?, setHeld: (String?) -> Unit,
    edit: (Message) -> Unit, select: (Message) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isHeld = held == message.id
    val isUser = message.role == Role.USER
    val scale by animateFloatAsState(if (isHeld) 1.02f else 1f, spring(dampingRatio = 0.72f, stiffness = 400f), label = "held")
    val alpha by animateFloatAsState(if (held == null || isHeld) 1f else 0.4f, label = "dim")
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha; transformOrigin = TransformOrigin(if (isUser) 1f else 0f, 0.5f) }
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); setHeld(message.id) },
                        onTap = { setHeld(null) },
                    )
                },
        ) {
            if (isUser) UserMessage(message, vm::photoFile) else AssistantMessage(message, generating = false, interrupted = message.id in state.interrupted, undo = vm::undoAction)
        }
        AnimatedVisibility(
            isHeld,
            enter = fadeIn() + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(if (isUser) 1f else 0f, 0f)),
            exit = fadeOut(),
            modifier = Modifier.align(if (isUser) Alignment.BottomEnd else Alignment.BottomStart).offset(y = 54.dp),
        ) {
            Actions(message, state, vm, done = { setHeld(null) }, edit = edit, select = select)
        }
    }
}

@Composable
private fun UserMessage(message: Message, photoFile: (String) -> File) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(start = 48.dp)) {
        if (message.files.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                message.files.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.height(30.dp).border(1.dp, ink.hairline, CircleShape).padding(horizontal = 10.dp),
                    ) {
                        Icon(Icons.Outlined.Description, null, tint = ink.primary, modifier = Modifier.size(13.dp))
                        Text(name, style = geist(13, FontWeight.Medium, ink.primary), maxLines = 1)
                    }
                }
            }
        }
        message.imageName?.let { AttachedPhoto(photoFile(it)) }
        Text(
            message.text,
            style = geist(16, color = ink.primary, lineHeight = 22.sp),
            modifier = Modifier.background(ink.fill, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

/** A photo sent with a message: sized to its own aspect (at most 230 x 280), rounded on the picture itself. */
@Composable
private fun AttachedPhoto(file: File) {
    val bitmap = remember(file.path) { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() } ?: return
    val scale = minOf(230f / bitmap.width, 280f / bitmap.height)
    Image(
        bitmap, "Attached photo", contentScale = ContentScale.Crop,
        modifier = Modifier.size((bitmap.width * scale).dp, (bitmap.height * scale).dp)
            .clip(RoundedCornerShape(20.dp)).border(1.dp, ink.hairline, RoundedCornerShape(20.dp)),
    )
}

@Composable
private fun AssistantMessage(message: Message, generating: Boolean, interrupted: Boolean, phase: String = "", undo: (String) -> Unit = {}) {
    val shown = StreamingText.stable(message.text, live = generating)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        if (message.tools.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { message.tools.forEach { ToolBox(it, undo) } }
        }
        // "Thinking" stays until the first whole word is ready, so the row never hops.
        if (shown.isEmpty() && generating && message.tools.none { it.status == ToolUse.Status.RUNNING }) {
            // "Thinking" stays until the first whole word is ready, so the row never hops.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                SpiralLoader(18.dp)
                val label = if (phase.isEmpty() || phase == "Generating" || phase.startsWith("Using") || phase == "Searching the web" || phase == "Checking your sources") "Thinking" else phase
                AnimatedContent(label, label = "phase", transitionSpec = { fadeIn() togetherWith fadeOut() }) {
                    Text(it, style = geist(15, color = ink.secondary), modifier = Modifier.shimmer())
                }
            }
        } else if (shown.isNotEmpty()) {
            HandwrittenText(renderMarkdown(shown), live = generating, style = geist(16, color = ink.primary, lineHeight = 24.sp))
            if (generating) SpiralLoader(14.dp, ink.secondary)
        }
        if (interrupted) Text("Stopped · partial reply saved", style = geist(12, color = ink.tertiary))
    }
}

/** Long-press actions on a prompt or a reply: a small pill of icons under the message. */
@Composable
private fun Actions(message: Message, state: UiState, vm: ChatViewModel, done: () -> Unit, edit: (Message) -> Unit, select: (Message) -> Unit) {
    val context = LocalContext.current
    val plain = plainText(message.text)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .shadow(14.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.1f))
            .background(ink.paper, CircleShape).border(1.dp, ink.hairline, CircleShape).padding(4.dp),
    ) {
        Action("Copy", Icons.Outlined.ContentCopy) {
            // Marked sensitive: Android keeps it out of clipboard previews and cross-device sync.
            val clip = ClipData.newPlainText("Loocal", plain).apply {
                description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
            done()
        }
        if (message.role == Role.USER) {
            if (!state.isBusy) Action("Edit", Icons.Outlined.Edit) { done(); edit(message) }
        } else if (message.text.isNotEmpty()) {
            Action("Share", Icons.Outlined.IosShare) {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plain), null))
                done()
            }
            Action("Read aloud", Icons.Outlined.VolumeUp) { vm.readAloud(plain); done() }
            if (vm.canRegenerate(message)) Action("Regenerate", Icons.Outlined.Refresh) { done(); vm.regenerate() }
        }
        Action("Select text", Icons.Outlined.TextFields) { done(); select(message) }
    }
}

@Composable
private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, run: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp, 36.dp).semantics { contentDescription = label }.pressable(0.85f, onClick = run),
    ) { Icon(icon, null, tint = ink.primary, modifier = Modifier.size(18.dp)) }
}

/** Without a downloaded model, the prompt box itself becomes the download box. */
@Composable
private fun Composer(
    state: UiState, vm: ChatViewModel, personaOpen: Boolean, setPersonaOpen: (Boolean) -> Unit,
    attachOpen: Boolean, setAttachOpen: (Boolean) -> Unit, attach: (TrayItem) -> Unit,
    focusRequester: FocusRequester, openPersonas: () -> Unit, trayPage: String, sendNow: () -> Unit, talk: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Box(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth()
            .background(ink.fill, RoundedCornerShape(25.dp)).padding(6.dp),
    ) {
        AnimatedContent(state.modelReady, label = "composer", transitionSpec = { fadeIn() togetherWith fadeOut() }) { ready ->
            if (!ready) {
                DownloadComposer(state) { vm.downloadModel() }
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.photo?.let { photo ->
                    Box(Modifier.padding(start = 6.dp, top = 6.dp)) {
                        val bitmap = remember(photo.name) { android.graphics.BitmapFactory.decodeFile(vm.photoFile(photo.name).path)?.asImageBitmap() }
                        if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(20.dp).background(ink.primary, CircleShape)
                                .semantics { contentDescription = "Remove photo" }.pressable(0.85f, onClick = vm::discardPhoto),
                        ) { Icon(androidx.compose.material.icons.Icons.Filled.Close, null, tint = ink.paper, modifier = Modifier.size(11.dp)) }
                    }
                }
                val chips = state.files.map { Chip("file:" + it.name, Icons.Outlined.Description, it.name) } +
                    state.connectors.filter { it.id in state.activeConnectors }.map { Chip(it.id, symbolIcon(it.icon), it.name) }
                if (chips.isNotEmpty()) {
                    ChipRow(chips) { chip -> if (chip.id.startsWith("file:")) vm.removeFile(chip.id.removePrefix("file:")) else vm.toggleConnector(chip.id) }
                }
                if (state.preparingPhoto) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                        SpiralLoader(14.dp, ink.secondary)
                        Text("Preparing photo", style = geist(13, color = ink.secondary))
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.zIndex(1f)) {
                        AttachControl(
                            open = attachOpen, setOpen = setAttachOpen,
                            items = if (trayPage == "connectors") {
                                listOf(TrayItem("back", Icons.AutoMirrored.Outlined.ArrowBack, "Back")) +
                                    state.connectors.map { TrayItem(it.id, symbolIcon(it.icon), it.name, on = it.id in state.activeConnectors) } +
                                    TrayItem("server", Icons.Outlined.Dns, "Add MCP server")
                            } else listOf(
                                TrayItem("photos", Icons.Outlined.PhotoLibrary, "Photos", enabled = state.model.vision),
                                TrayItem("camera", Icons.Outlined.PhotoCamera, "Camera", enabled = state.model.vision),
                                TrayItem("files", Icons.Outlined.Description, "Files"),
                                TrayItem("connectors", Icons.Outlined.Hub, "Connectors", on = state.activeConnectors.isNotEmpty()),
                            ),
                            choose = attach, enabled = !state.isBusy && !state.preparingPhoto,
                        )
                    }
                    Box(Modifier.weight(1f).alpha(if (personaOpen || attachOpen) 0f else 1f)) {
                        BasicTextField(
                            value = state.draft,
                            onValueChange = vm::setDraft,
                            enabled = !state.isBusy && !personaOpen && !attachOpen,
                            textStyle = geist(16, color = ink.primary, lineHeight = 22.sp),
                            cursorBrush = SolidColor(ink.primary),
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp).focusRequester(focusRequester),
                            decorationBox = { inner ->
                                if (state.draft.isEmpty()) Text(state.persona?.let { "Ask ${it.name}" } ?: "Ask anything", style = geist(16, color = ink.tertiary, lineHeight = 22.sp))
                                inner()
                            },
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(30.dp, 38.dp).alpha(if (personaOpen) 0f else if (state.isBusy) 0.4f else 1f)
                            .semantics { contentDescription = "Conversation mode" }
                            .pressable(0.85f, enabled = !state.isBusy && !personaOpen) { focus.clearFocus(); setAttachOpen(false); talk() },
                    ) { AudioLinesIcon(19.dp) }
                    AnimatedContent(
                        state.isBusy, label = "send",
                        transitionSpec = { (scaleIn(initialScale = 0.5f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut()) },
                    ) { busy ->
                        if (busy) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(38.dp).background(ink.primary, CircleShape)
                                    .semantics { contentDescription = "Stop generation" }
                                    .pressable(0.88f, onClick = vm::stop),
                            ) { Box(Modifier.size(11.dp).background(ink.paper, RoundedCornerShape(2.5.dp))) }
                        } else {
                            SendControl(
                                personas = state.personas, activeId = state.personaId, canSend = state.canSend,
                                open = personaOpen, setOpen = { if (it) focus.clearFocus(); setPersonaOpen(it) },
                                send = { focus.clearFocus(); sendNow() }, choose = vm::choosePersona, addPersona = openPersonas,
                            )
                        }
                    }
                }
              }
            }
        }
    }
}

@Composable
fun DownloadComposer(state: UiState, start: () -> Unit) {
    val downloading = state.download.isDownloading
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, state.model.totalBytes)
    val name = state.model.name
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().pressable(0.98f, enabled = !downloading, onClick = start),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(38.dp).background(if (downloading) ink.paper else ink.primary, CircleShape),
            ) {
                if (downloading) SpiralLoader(18.dp)
                else Icon(if (state.download.error == null) Icons.Outlined.ArrowDownward else Icons.Outlined.Refresh, null, tint = ink.paper, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (downloading) "Downloading $name" else if (state.download.error == null) "Download $name" else "Retry $name",
                    style = geist(15, FontWeight.Medium, ink.primary),
                )
                Text(
                    state.download.error ?: if (downloading) "You can leave the app meanwhile" else "$size · Wi-Fi · then fully offline",
                    style = geist(13, color = ink.secondary), maxLines = 2,
                )
            }
            if (downloading) Text("${((state.download.progress ?: 0f) * 100).toInt()}%", style = geist(14, FontWeight.Medium, ink.primary), modifier = Modifier.padding(end = 8.dp))
        }
        if (downloading) {
            val p by animateFloatAsState(state.download.progress ?: 0f, tween(600), label = "progress")
            Box(Modifier.padding(horizontal = 6.dp).padding(bottom = 4.dp).fillMaxWidth().height(3.dp).background(ink.hairline, CircleShape)) {
                Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.01f)).height(3.dp).background(ink.primary, CircleShape))
            }
        }
    }
}

/** A message opened for free text selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectTextSheet(text: String, dismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = ink.paper,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Select text", style = geist(15, FontWeight.Medium, ink.secondary), modifier = Modifier.weight(1f))
                Text("Done", style = geist(15, FontWeight.Medium, ink.primary), modifier = Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = dismiss))
            }
            SelectionContainer(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(renderMarkdown(text), style = geist(17, color = ink.primary, lineHeight = 26.sp))
            }
        }
    }
}

