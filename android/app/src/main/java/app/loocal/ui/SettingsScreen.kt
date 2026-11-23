package app.loocal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loocal.domain.Persona
import app.loocal.ui.theme.Appearance
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif

enum class SettingsPage { HOME, MODELS, PERSONAS, VOICE, MEMORY, APPEARANCE }

/** Port of iOS `SettingsView`: a serif title, grouped rows, and subpages with a round back button. */
@Composable
fun SettingsScreen(state: UiState, vm: ChatViewModel, page: SettingsPage, setPage: (SettingsPage) -> Unit, addingPersona: Boolean, consumeAdding: () -> Unit) {
    var editing by remember { mutableStateOf<Persona?>(null) }
    LaunchedEffect(addingPersona) {
        if (addingPersona) {
            consumeAdding()
            setPage(SettingsPage.PERSONAS)
            if (state.personas.size < Persona.LIMIT) editing = Persona(name = "", icon = Persona.ICONS[state.personas.size], prompt = "")
        }
    }
    BackHandler(page != SettingsPage.HOME) { setPage(SettingsPage.HOME) }

    AnimatedContent(
        page, label = "settings",
        transitionSpec = {
            val forward = targetState != SettingsPage.HOME
            (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
        },
    ) { current ->
        when (current) {
            SettingsPage.HOME -> Home(state, vm, setPage)
            SettingsPage.MODELS -> Subpage("Models", { setPage(SettingsPage.HOME) }) { ModelsPage(state, vm) }
            SettingsPage.PERSONAS -> Subpage("Personas", { setPage(SettingsPage.HOME) }) { PersonasPage(state) { editing = it } }
            SettingsPage.MEMORY -> Subpage("Memory", { setPage(SettingsPage.HOME) }) { MemoryPage(state, vm) }
            SettingsPage.VOICE -> Subpage("Voice", { vm.speaker.stop(); setPage(SettingsPage.HOME) }) { VoicePage(vm) }
            SettingsPage.APPEARANCE -> Subpage("Appearance", { setPage(SettingsPage.HOME) }) { AppearancePage(state.appearance, vm::setAppearance) }
        }
    }

    editing?.let { persona ->
        PersonaEditor(
            persona = persona,
            isNew = state.personas.none { it.id == persona.id },
            save = vm::savePersona, delete = vm::deletePersona, dismiss = { editing = null },
        )
    }
}

@Composable
private fun Home(state: UiState, vm: ChatViewModel, open: (SettingsPage) -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    Column(
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize().background(ink.paper).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
    ) {
        Text("Settings", style = serif(36, color = ink.primary), modifier = Modifier.padding(top = 8.dp, start = 4.dp).rise(appeared))
        Group(Modifier.rise(appeared, 60)) {
            SettingsRow(Icons.Outlined.Memory, "Models", state.model.name + if (state.modelReady) " · ready" else " · not downloaded") { open(SettingsPage.MODELS) }
            Hairline(Modifier.padding(start = 64.dp))
            SettingsRow(Icons.Outlined.TheaterComedy, "Personas", personaDetail(state)) { open(SettingsPage.PERSONAS) }
            Hairline(Modifier.padding(start = 64.dp))
            SettingsRow(Icons.Outlined.GraphicEq, "Voice", voiceDetail(vm)) { open(SettingsPage.VOICE) }
            Hairline(Modifier.padding(start = 64.dp))
            SettingsRow(Icons.Outlined.Psychology, "Memory", memoryDetail(state)) { open(SettingsPage.MEMORY) }
        }
        Group(Modifier.rise(appeared, 120)) {
            SettingsRow(Icons.Outlined.Contrast, "Appearance", state.appearance.label) { open(SettingsPage.APPEARANCE) }
        }
        Text("Loocal runs entirely on this phone.", style = geist(13, color = ink.tertiary), modifier = Modifier.padding(start = 4.dp).rise(appeared, 180))
    }
}

private fun voiceDetail(vm: ChatViewModel): String {
    vm.speaker.selectedNeural?.let { return it.name + " · Natural" }
    val voices = vm.speaker.voices()
    val current = voices.firstOrNull { it.name == vm.speaker.selectedName } ?: voices.firstOrNull() ?: return "System voice"
    return voiceName(current) + " · " + app.loocal.voice.Speaker.label(current)
}

/** A readable name for a system voice: its language and region, plus its number when there are several. */
private fun voiceName(voice: android.speech.tts.Voice): String {
    val base = voice.locale.getDisplayName(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() }
    val tag = Regex("-x-([a-z]{3})").find(voice.name)?.groupValues?.get(1)?.uppercase()
    return if (tag != null) "$base · $tag" else base
}

/** Settings › Voice: pick a voice; tapping one previews it. */
@Composable
private fun VoicePage(vm: ChatViewModel) {
    var selected by remember { mutableStateOf(vm.speaker.selectedName) }
    var voices by remember { mutableStateOf(vm.speaker.voices()) }
    LaunchedEffect(Unit) { while (voices.isEmpty()) { kotlinx.coroutines.delay(300); voices = vm.speaker.voices() } }
    val kokoro by vm.kokoroStatus.collectAsState()
    val engineReady = vm.speaker.kokoro.isReady && !kokoro.isDownloading
    val current = selected?.takeIf { !it.startsWith(app.loocal.voice.NeuralVoice.PREFIX) || engineReady } ?: voices.firstOrNull()?.name
    val preview = "Hi, this is how I’ll sound when we talk."

    @Composable
    fun VoiceRow(id: String, name: String, tags: List<String>, onPick: () -> Unit) {
        val isSelected = id == current
        val ring by animateDpAsState(if (isSelected) 5.5.dp else 1.5.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "ring$id")
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp).pressable(0.98f) { vm.speaker.selectedName = id; selected = id; onPick() }.padding(horizontal = 16.dp),
        ) {
            Box(Modifier.padding(end = 4.dp).size(18.dp).border(ring, if (isSelected) ink.primary else ink.hairline, CircleShape))
            Text(name, style = geist(15, FontWeight.Medium, ink.primary), maxLines = 1, modifier = Modifier.weight(1f))
            tags.forEach { tag ->
                val strong = tag in setOf("Natural", "Premium", "Enhanced")
                Text(tag, style = geist(11, FontWeight.Medium, if (strong) ink.primary else ink.secondary),
                    modifier = Modifier.border(1.dp, ink.hairline, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Your voices")
            Column(Modifier.fillMaxWidth().border(1.dp, ink.hairline, RoundedCornerShape(22.dp))) {
                val rows = (if (engineReady) app.loocal.voice.NeuralVoice.CATALOG.map { v ->
                    Triple(app.loocal.voice.NeuralVoice.PREFIX + v.id, v.name, listOf(if (v.female) "Female" else "Male", "Natural")) to { vm.speaker.stop(); vm.speaker.speakNeural(preview, v) }
                } else emptyList()) + voices.take(8).map { v ->
                    Triple(v.name, voiceName(v), listOf(app.loocal.voice.Speaker.label(v))) to { vm.speaker.say(preview, v) }
                }
                rows.forEachIndexed { i, (row, play) ->
                    VoiceRow(row.first, row.second, row.third, play)
                    if (i < rows.lastIndex) Hairline(Modifier.padding(start = 46.dp))
                }
            }
            Text(if (engineReady) "Tap a voice to hear it. More system voices: Android Settings › System › Languages › Text-to-speech output."
                 else "Android ships compact system voices. Download the natural voices below, or more system voices in Android Settings › System › Languages › Text-to-speech output.",
                style = geist(13, color = ink.tertiary, lineHeight = 18.sp), modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (!engineReady) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Natural voices")
                Column(Modifier.fillMaxWidth().border(1.dp, ink.hairline, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Kokoro · 11 voices", style = geist(16, FontWeight.Medium, ink.primary))
                            Text("American and British English · 350 MB", style = geist(13, color = ink.secondary))
                        }
                        if (kokoro.isDownloading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpiralLoader(14.dp)
                            Text("${((kokoro.progress ?: 0f) * 100).toInt()}%", style = geist(13, color = ink.secondary))
                        } else Box(contentAlignment = Alignment.Center, modifier = Modifier.height(32.dp).background(ink.primary, CircleShape).pressable(0.92f, onClick = vm::downloadKokoro).padding(horizontal = 16.dp)) {
                            Text(if (kokoro.error == null) "Get" else "Retry", style = geist(14, FontWeight.Medium, ink.paper))
                        }
                    }
                    if (kokoro.isDownloading) {
                        val p by animateFloatAsState(kokoro.progress ?: 0f, androidx.compose.animation.core.tween(600), label = "kokoro")
                        Box(Modifier.fillMaxWidth().height(3.dp).background(ink.hairline, CircleShape)) { Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.01f)).height(3.dp).background(ink.primary, CircleShape)) }
                    }
                    kokoro.error?.let { Text(it, style = geist(13, color = ink.secondary)) }
                }
                Text("Open-source Kokoro-82M (Apache 2.0), run on this phone through sherpa-onnx. English only.", style = geist(13, color = ink.tertiary), modifier = Modifier.padding(horizontal = 4.dp))
            }
        } else {
            Text("Remove natural voices · frees 350 MB", style = geist(13, FontWeight.Medium, ink.secondary), modifier = Modifier.padding(horizontal = 4.dp).pressable { vm.deleteKokoro(); selected = vm.speaker.selectedName })
        }
    }
}

private fun memoryDetail(state: UiState): String = when {
    !state.memoryOn -> "Off"
    state.memories.isEmpty() -> "Nothing saved yet"
    else -> "${state.memories.size} thing${if (state.memories.size == 1) "" else "s"} remembered"
}

/** Settings › Memory: the switch, what's remembered (each can be forgotten), and a line to add one. */
@Composable
private fun MemoryPage(state: UiState, vm: ChatViewModel) {
    var adding by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Use memory", style = geist(15, FontWeight.Medium, ink.primary))
                Text("Say “remember that…” in a chat. Kept on this phone only.", style = geist(13, color = ink.secondary))
            }
            InkSwitch(state.memoryOn, vm::setMemoryOn)
        }
        Column(Modifier.fillMaxWidth().border(1.dp, ink.hairline, RoundedCornerShape(20.dp)).alpha(if (state.memoryOn) 1f else 0.5f)) {
            state.memories.forEach { memory ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(memory.text, style = geist(14, color = ink.primary), modifier = Modifier.weight(1f))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp).pressable(0.85f) { vm.deleteMemory(memory) }) {
                        Icon(Icons.Filled.Close, "Forget this", tint = ink.tertiary, modifier = Modifier.size(13.dp))
                    }
                }
                Hairline(Modifier.padding(start = 14.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 14.dp).height(48.dp)) {
                Box(Modifier.weight(1f)) {
                    if (adding.isEmpty()) Text("Add something to remember", style = geist(14, color = ink.tertiary))
                    BasicTextField(adding, { adding = it }, singleLine = true, textStyle = geist(14, color = ink.primary), cursorBrush = SolidColor(ink.primary), modifier = Modifier.fillMaxWidth())
                }
                if (adding.isNotBlank()) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp).background(ink.primary, CircleShape).pressable(0.85f) { vm.addMemory(adding); adding = "" }) {
                        Icon(Icons.Filled.ArrowUpward, "Save memory", tint = ink.paper, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** A monochrome switch: ink track when on, hairline when off. */
@Composable
fun InkSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = ink.paper, checkedTrackColor = ink.primary, checkedBorderColor = ink.primary,
            uncheckedThumbColor = ink.paper, uncheckedTrackColor = ink.hairline, uncheckedBorderColor = ink.hairline,
        ),
    )
}

private fun personaDetail(state: UiState): String {
    val active = state.persona
    if (active != null) return "${active.name} is on · ${state.personas.size} of ${Persona.LIMIT}"
    return if (state.personas.isEmpty()) "None yet" else "${state.personas.size} of ${Persona.LIMIT} · none on"
}

@Composable
private fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().border(1.dp, ink.hairline, RoundedCornerShape(22.dp))) { content() }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, detail: String, open: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().pressable(0.98f, onClick = open).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(ink.fill, CircleShape)) {
            Icon(icon, null, tint = ink.primary, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = geist(15, FontWeight.Medium, ink.primary))
            Text(detail, style = geist(13, color = ink.secondary), maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ink.tertiary, modifier = Modifier.size(18.dp))
    }
}

/** A Settings subpage: round back button and title, then the page. */
@Composable
private fun Subpage(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize().background(ink.paper).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp).background(ink.fill, CircleShape).pressable(0.88f, onClick = back)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = ink.primary, modifier = Modifier.size(22.dp))
            }
            Text(title, style = geist(17, FontWeight.Medium, ink.primary))
        }
        content()
    }
}

@Composable
private fun ModelsPage(state: UiState, vm: ChatViewModel) {
    // The model just shared stays marked a moment, then the mark fades.
    androidx.compose.runtime.LaunchedEffect(state.revealedModel) {
        if (state.revealedModel != null) { kotlinx.coroutines.delay(2500); vm.revealed() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).border(1.dp, ink.hairline, RoundedCornerShape(22.dp)).animateContentSize()) {
            state.models.forEachIndexed { i, model ->
                androidx.compose.runtime.key(model.id) {
                    SwipeToRemove(enabled = model.custom, remove = { vm.removeModel(model.id) }) {
                        ModelRow(state, model, vm, revealed = state.revealedModel == model.id)
                    }
                }
                if (i < state.models.lastIndex || state.addingModel != null) Hairline(Modifier.padding(start = 20.dp))
            }
            state.addingModel?.let { adding ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SpiralLoader(16.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Adding model", style = geist(16, FontWeight.Medium, ink.primary))
                        Text(adding, style = geist(13, color = ink.secondary), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Text("Models download once, then run offline on this phone. Smaller ones answer faster. Long-press a downloaded model to remove it. To add one, share its Hugging Face page to Loocal.", style = geist(13, color = ink.tertiary), modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Swipe a shared model left to remove it, like a chat in History. */
@Composable
private fun SwipeToRemove(enabled: Boolean, remove: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) return content()
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) { remove(); true } else value == androidx.compose.material3.SwipeToDismissBoxValue.Settled
    })
    androidx.compose.material3.SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFFF3B30)).padding(horizontal = 24.dp)) {
                Icon(Icons.Outlined.Delete, "Remove", tint = androidx.compose.ui.graphics.Color.White)
            }
        },
    ) { content() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModelRow(state: UiState, model: app.loocal.inference.ModelManifest, vm: ChatViewModel, revealed: Boolean = false) {
    val selected = state.modelId == model.id
    val ready = model.id in state.ready
    val download = state.downloads[model.id] ?: DownloadStatus()
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, model.totalBytes)
    var confirmDelete by remember { mutableStateOf(false) }
    val ring by animateDpAsState(if (selected) 5.5.dp else 1.5.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "ring")
    val mark by androidx.compose.animation.animateColorAsState(if (revealed) ink.fill else ink.paper, androidx.compose.animation.core.tween(600), label = "mark")
    val tooBig = remember(model.id) { app.loocal.inference.ModelLibrary.mayNotFit(model, vm.totalMemory) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.background(mark).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                    onClick = { vm.chooseModel(model.id) }, onLongClick = { if (ready) confirmDelete = true },
                ),
            ) {
                Box(Modifier.size(18.dp).border(ring, if (selected) ink.primary else ink.hairline, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.name, style = geist(16, FontWeight.Medium, ink.primary), maxLines = 1)
                        if (tooBig) Icon(Icons.Filled.Warning, "May be too big for this phone", tint = androidx.compose.ui.graphics.Color(0xFFFF9500), modifier = Modifier.size(13.dp))
                    }
                    Text("${model.detail} · $size", style = geist(13, color = ink.secondary))
                }
            }
            when {
                ready -> Icon(Icons.Filled.Check, "Installed", tint = ink.primary, modifier = Modifier.size(17.dp))
                download.isDownloading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpiralLoader(14.dp)
                    Text("${((download.progress ?: 0f) * 100).toInt()}%", style = geist(13, color = ink.secondary))
                }
                else -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(32.dp).background(ink.primary, CircleShape).pressable(0.92f) { vm.downloadModel(model.id) }.padding(horizontal = 16.dp),
                ) { Text(if (download.error == null) "Get" else "Retry", style = geist(14, FontWeight.Medium, ink.paper)) }
            }
        }
        if (download.isDownloading) {
            val p by animateFloatAsState(download.progress ?: 0f, androidx.compose.animation.core.tween(600), label = "p")
            Box(Modifier.padding(start = 30.dp).fillMaxWidth().height(3.dp).background(ink.hairline, CircleShape)) {
                Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.01f)).height(3.dp).background(ink.primary, CircleShape))
            }
        }
        download.error?.let { Text(it, style = geist(13, color = ink.secondary), modifier = Modifier.padding(start = 30.dp)) }
        if (confirmDelete) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 30.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.height(32.dp).border(1.dp, ink.hairline, CircleShape).pressable { vm.deleteModel(model.id); confirmDelete = false }.padding(horizontal = 14.dp)) {
                    Text("Remove · frees $size", style = geist(13, FontWeight.Medium, ink.primary))
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.height(32.dp).pressable { confirmDelete = false }.padding(horizontal = 10.dp)) {
                    Text("Cancel", style = geist(13, color = ink.secondary))
                }
            }
        }
    }
}

@Composable
private fun PersonasPage(state: UiState, edit: (Persona) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.personas.forEach { persona -> PersonaRow(persona, active = state.personaId == persona.id) { edit(persona) } }
        if (state.personas.size < Persona.LIMIT) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
                    .dashedBorder(ink.hairline, 20.dp, dash = 4.dp)
                    .pressable(0.98f) { edit(Persona(name = "", icon = Persona.ICONS[state.personas.size % Persona.ICONS.size], prompt = "")) }
                    .padding(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).dashedBorder(ink.hairline, 20.dp, dash = 3.dp)) {
                    Icon(Icons.Filled.Add, null, tint = ink.primary, modifier = Modifier.size(18.dp))
                }
                Text("New persona", style = geist(15, FontWeight.Medium, ink.primary), modifier = Modifier.weight(1f))
                Text("${state.personas.size} of ${Persona.LIMIT}", style = geist(13, color = ink.tertiary))
            }
        }
        Text("Hold the send button to switch personas.", style = geist(13, color = ink.tertiary), modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun PersonaRow(persona: Persona, active: Boolean, edit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, ink.hairline, RoundedCornerShape(20.dp)).pressable(0.98f, onClick = edit).padding(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(ink.primary, CircleShape)) {
            Icon(personaIcon(persona.icon), null, tint = ink.paper, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(persona.name.ifEmpty { "Untitled" }, style = geist(15, FontWeight.Medium, ink.primary))
                if (active) Text("Active", style = geist(11, FontWeight.Medium, ink.secondary), modifier = Modifier.border(1.dp, ink.hairline, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
            }
            Text(persona.prompt.ifEmpty { "No instructions yet" }, style = geist(13, color = ink.secondary), maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ink.tertiary, modifier = Modifier.padding(top = 11.dp).size(18.dp))
    }
}

/** Light, dark, or follow the system: a segmented pill whose thumb glides. */
@Composable
private fun AppearancePage(appearance: Appearance, set: (Appearance) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Theme")
        BoxWithConstraints(Modifier.fillMaxWidth().background(ink.fill, CircleShape).padding(4.dp)) {
            val w = maxWidth / Appearance.entries.size
            val x by animateDpAsState(w * appearance.ordinal, spring(dampingRatio = 0.85f, stiffness = 500f), label = "thumb")
            Box(
                Modifier.offset(x = x).width(w).height(38.dp)
                    .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.06f))
                    .background(ink.paper, CircleShape),
            )
            Row {
                Appearance.entries.forEach { option ->
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.width(w).height(38.dp).pressable { set(option) }) {
                        Text(option.label, style = geist(14, FontWeight.Medium, if (option == appearance) ink.primary else ink.tertiary))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    SectionLabel("App icon")
    AppIconPicker()
}

/** Persona editor sheet (iOS `PersonaEditor`): Cancel / Save, the icon, a grid of icons, name and instructions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PersonaEditor(persona: Persona, isNew: Boolean, save: (Persona) -> Unit, delete: (Persona) -> Unit, dismiss: () -> Unit) {
    var draft by remember(persona.id) { mutableStateOf(persona) }
    val canSave = draft.name.isNotBlank() && draft.prompt.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ink.paper,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 8.dp)) {
            Text("Cancel", style = geist(15, color = ink.secondary), modifier = Modifier.pressable(onClick = dismiss))
            Spacer(Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.height(34.dp).background(if (canSave) ink.primary else ink.hairline, CircleShape)
                    .pressable(0.92f, enabled = canSave) { save(draft.copy(name = draft.name.trim())); dismiss() }
                    .padding(horizontal = 16.dp),
            ) { Text("Save", style = geist(15, FontWeight.Medium, if (canSave) ink.paper else ink.tertiary)) }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            // The two looks a persona has: its icon in chat, its bot on errands.
            var side by remember { mutableStateOf(0) }
            var hops by remember { mutableStateOf(0) }
            val shape = draft.shape?.let { s -> BotShape.entries.firstOrNull { it.name == s } } ?: BotShape.ROUND
            val tint = draft.tint?.let { t -> BotTint.entries.firstOrNull { it.name == t } } ?: BotTint.INK
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Box(Modifier.height(96.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(side, label = "side", transitionSpec = { (androidx.compose.animation.scaleIn(initialScale = 0.8f) + fadeIn()) togetherWith (androidx.compose.animation.scaleOut(targetScale = 0.8f) + fadeOut()) }) { current ->
                        if (current == 0) Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp).background(ink.primary, CircleShape)) {
                            Icon(personaIcon(draft.icon), null, tint = ink.paper, modifier = Modifier.size(34.dp))
                        } else PebbleBot(BotMood.HAPPY, shape, 92.dp, reaction = hops, tint = tint.color)
                    }
                }
                BoxWithConstraints(Modifier.width(220.dp).background(ink.fill, CircleShape).padding(4.dp)) {
                    val w = maxWidth / 2
                    val x by animateDpAsState(w * side, spring(dampingRatio = 0.86f, stiffness = 400f), label = "sidePill")
                    Box(Modifier.offset(x = x).width(w).height(34.dp).background(ink.paper, CircleShape))
                    Row { listOf("Chat", "Errands").forEachIndexed { i, label ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.width(w).height(34.dp).pressable { side = i }) {
                            Text(label, style = geist(14, FontWeight.Medium, if (side == i) ink.primary else ink.tertiary))
                        }
                    } }
                }
            }
            if (side == 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 8, modifier = Modifier.fillMaxWidth()) {
                    Persona.ICONS.forEach { name ->
                        val picked = name == draft.icon
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(38.dp).background(if (picked) ink.primary else ink.fill, CircleShape).pressable(0.9f) { draft = draft.copy(icon = name) },
                        ) { Icon(personaIcon(name), null, tint = if (picked) ink.paper else ink.primary, modifier = Modifier.size(18.dp)) }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionLabel("Shape")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        BotShape.entries.forEach { s ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(48.dp).background(if (s == shape) ink.fill else Color.Transparent, RoundedCornerShape(14.dp))
                                    .semantics { contentDescription = s.label }.pressable(0.9f) { draft = draft.copy(shape = s.name); hops++ },
                            ) { PebbleBot(BotMood.NEUTRAL, s, 34.dp, lively = false, tint = tint.color) }
                        }
                    }
                    SectionLabel("Colour")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BotTint.entries.forEach { t ->
                            Box(
                                Modifier.size(30.dp).background(t.color ?: ink.primary, CircleShape)
                                    .border(if (t == tint) 2.dp else 0.dp, if (t == tint) ink.paper else Color.Transparent, CircleShape)
                                    .border(if (t == tint) 3.5.dp else 0.dp, if (t == tint) (t.color ?: ink.primary) else Color.Transparent, CircleShape)
                                    .semantics { contentDescription = t.label }.pressable(0.9f) { draft = draft.copy(tint = t.name); hops++ },
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Name")
                Field(draft.name, { draft = draft.copy(name = it) }, "Editor, Coach, Friend…", single = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Instructions")
                Field(draft.prompt, { draft = draft.copy(prompt = it.take(2000)) }, "How should the model behave? This becomes its system prompt.", single = false)
                Text("${draft.prompt.length} / 2,000", style = geist(12, color = ink.tertiary), modifier = Modifier.align(Alignment.End))
            }
            if (!isNew) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().height(48.dp).border(1.dp, ink.hairline, CircleShape).pressable(0.97f) { delete(persona); dismiss() },
                ) { Text("Delete persona", style = geist(15, FontWeight.Medium, ink.secondary)) }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, single: Boolean) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = if (single) 48.dp else 130.dp).background(ink.fill, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = if (single) 14.dp else 16.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = geist(16, color = ink.tertiary, lineHeight = 22.sp))
        BasicTextField(value, onChange, singleLine = single, textStyle = geist(16, color = ink.primary, lineHeight = 22.sp), cursorBrush = SolidColor(ink.primary), modifier = Modifier.fillMaxWidth())
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp, dash: Dp) = drawBehind {
    val r = radius.toPx().coerceAtMost(size.minDimension / 2)
    drawRoundRect(color, cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), dash.toPx()))))
}


/** Loop or bot on the home screen: switches which launcher entry is enabled. */
@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    val pm = context.packageManager
    fun enabled(alias: String) = pm.getComponentEnabledSetting(android.content.ComponentName(context, "app.loocal.$alias")).let {
        it == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (it == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && alias == "LoopIcon")
    }
    var current by remember { mutableStateOf(if (enabled("BotIcon")) "BotIcon" else "LoopIcon") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("LoopIcon" to "Loop", "BotIcon" to "Bot").forEach { (alias, label) ->
            val picked = current == alias
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.pressable {
                if (picked) return@pressable
                for (other in listOf("LoopIcon", "BotIcon")) pm.setComponentEnabledSetting(
                    android.content.ComponentName(context, "app.loocal.$other"),
                    if (other == alias) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP,
                )
                current = alias
            }) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp).background(Color.White, RoundedCornerShape(16.dp)).border(if (picked) 2.dp else 1.dp, if (picked) ink.primary else ink.hairline, RoundedCornerShape(16.dp))) {
                    if (alias == "LoopIcon") LoopMark(Modifier.width(36.dp), color = Color(0xFF111111)) else PebbleBot(BotMood.NEUTRAL, size = 40.dp, lively = false, tint = Color(0xFF111111))
                }
                Text(label, style = geist(13, FontWeight.Medium, if (picked) ink.primary else ink.secondary))
            }
        }
    }
}
