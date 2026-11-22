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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TextFields
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

/** Port of iOS `ChatView` (RootView.swift). */
@Composable
fun ChatScreen(state: UiState, vm: ChatViewModel, openPersonas: () -> Unit) {
    val focus = LocalFocusManager.current
    var personaOpen by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf<Message?>(null) }
    val composerFocus = remember { FocusRequester() }
    var composerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().background(ink.paper)) {
        // Model pill, with the temporary-chat note hanging under it.
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)
                .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        ) {
            ModelPill(state.modelName, dimmed = !state.modelReady)
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
                Composer(state, vm, personaOpen, { personaOpen = it }, composerFocus, openPersonas)
            }
        }
    }

    selecting?.let { message -> SelectTextSheet(message.text) { selecting = null } }
}

@Composable
private fun ModelPill(name: String, dimmed: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(38.dp).background(ink.fill, CircleShape).padding(horizontal = 17.dp)
            .semantics { contentDescription = "Model $name" },
    ) {
        Text(name, style = geist(15, FontWeight.Medium, if (dimmed) ink.secondary else ink.primary), maxLines = 1)
    }
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
                    AssistantMessage(Message(id = state.generatingId, role = Role.ASSISTANT, text = state.streaming), generating = true, interrupted = false)
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
            if (isUser) UserMessage(message) else AssistantMessage(message, generating = false, interrupted = message.id in state.interrupted)
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
private fun UserMessage(message: Message) {
    Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            message.text,
            style = geist(16, color = ink.primary, lineHeight = 22.sp),
            modifier = Modifier.background(ink.fill, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun AssistantMessage(message: Message, generating: Boolean, interrupted: Boolean) {
    val shown = StreamingText.stable(message.text, live = generating)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        if (shown.isEmpty() && generating) {
            // "Thinking" stays until the first whole word is ready, so the row never hops.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                SpiralLoader(18.dp)
                Text("Thinking", style = geist(15, color = ink.secondary), modifier = Modifier.shimmer())
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
    focusRequester: FocusRequester, openPersonas: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Box(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth()
            .background(ink.fill, RoundedCornerShape(25.dp)).padding(6.dp),
    ) {
        AnimatedContent(state.modelReady, label = "composer", transitionSpec = { fadeIn() togetherWith fadeOut() }) { ready ->
            if (!ready) {
                DownloadComposer(state, vm::downloadModel)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).padding(start = 12.dp).alpha(if (personaOpen) 0f else 1f)) {
                        BasicTextField(
                            value = state.draft,
                            onValueChange = vm::setDraft,
                            enabled = !state.isBusy && !personaOpen,
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
                                send = { focus.clearFocus(); vm.send() }, choose = vm::choosePersona, addPersona = openPersonas,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadComposer(state: UiState, start: () -> Unit) {
    val downloading = state.downloadProgress != null
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, state.modelBytes)
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
                else Icon(if (state.downloadError == null) Icons.Outlined.ArrowDownward else Icons.Outlined.Refresh, null, tint = ink.paper, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (downloading) "Downloading ${state.modelName}" else if (state.downloadError == null) "Download ${state.modelName}" else "Retry ${state.modelName}",
                    style = geist(15, FontWeight.Medium, ink.primary),
                )
                Text(
                    state.downloadError ?: if (downloading) "You can leave the app meanwhile" else "$size · Wi-Fi · then fully offline",
                    style = geist(13, color = ink.secondary), maxLines = 2,
                )
            }
            if (downloading) Text("${((state.downloadProgress ?: 0f) * 100).toInt()}%", style = geist(14, FontWeight.Medium, ink.primary), modifier = Modifier.padding(end = 8.dp))
        }
        if (downloading) {
            val p by animateFloatAsState(state.downloadProgress ?: 0f, tween(600), label = "progress")
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

