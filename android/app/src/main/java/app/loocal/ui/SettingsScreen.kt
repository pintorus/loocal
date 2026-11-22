package app.loocal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

enum class SettingsPage { HOME, MODELS, PERSONAS, APPEARANCE }

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
            SettingsPage.HOME -> Home(state, setPage)
            SettingsPage.MODELS -> Subpage("Models", { setPage(SettingsPage.HOME) }) { ModelsPage(state, vm) }
            SettingsPage.PERSONAS -> Subpage("Personas", { setPage(SettingsPage.HOME) }) { PersonasPage(state) { editing = it } }
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
private fun Home(state: UiState, open: (SettingsPage) -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    Column(
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize().background(ink.paper).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
    ) {
        Text("Settings", style = serif(36, color = ink.primary), modifier = Modifier.padding(top = 8.dp, start = 4.dp).rise(appeared))
        Group(Modifier.rise(appeared, 60)) {
            SettingsRow(Icons.Outlined.Memory, "Models", state.modelName + if (state.modelReady) " · ready" else " · not downloaded") { open(SettingsPage.MODELS) }
            Hairline(Modifier.padding(start = 64.dp))
            SettingsRow(Icons.Outlined.TheaterComedy, "Personas", personaDetail(state)) { open(SettingsPage.PERSONAS) }
        }
        Group(Modifier.rise(appeared, 120)) {
            SettingsRow(Icons.Outlined.Contrast, "Appearance", state.appearance.label) { open(SettingsPage.APPEARANCE) }
        }
        Text("Loocal runs entirely on this phone.", style = geist(13, color = ink.tertiary), modifier = Modifier.padding(start = 4.dp).rise(appeared, 180))
    }
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
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, state.modelBytes)
    val downloading = state.downloadProgress != null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, ink.hairline, RoundedCornerShape(22.dp))
                .pressable(0.98f, enabled = !state.modelReady && !downloading, onClick = vm::downloadModel)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.modelName, style = geist(15, FontWeight.Medium, ink.primary))
                Text(
                    when {
                        state.modelReady -> "$size · on this phone"
                        downloading -> "Downloading · ${((state.downloadProgress ?: 0f) * 100).toInt()}%"
                        state.downloadError != null -> state.downloadError
                        else -> "$size · tap to download"
                    },
                    style = geist(13, color = ink.secondary), maxLines = 2,
                )
            }
            when {
                state.modelReady -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp).background(ink.primary, CircleShape)) {
                    Icon(Icons.Filled.Check, null, tint = ink.paper, modifier = Modifier.size(15.dp))
                }
                downloading -> SpiralLoader(18.dp)
                else -> Icon(Icons.Outlined.Memory, null, tint = ink.tertiary, modifier = Modifier.size(18.dp))
            }
        }
        Text("Models download once, then run offline on this phone.", style = geist(13, color = ink.tertiary), modifier = Modifier.padding(horizontal = 4.dp))
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
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp).background(ink.primary, CircleShape)) {
                    Icon(personaIcon(draft.icon), null, tint = ink.paper, modifier = Modifier.size(34.dp))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 8, modifier = Modifier.fillMaxWidth()) {
                Persona.ICONS.forEach { name ->
                    val picked = name == draft.icon
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(38.dp).background(if (picked) ink.primary else ink.fill, CircleShape).pressable(0.9f) { draft = draft.copy(icon = name) },
                    ) { Icon(personaIcon(name), null, tint = if (picked) ink.paper else ink.primary, modifier = Modifier.size(18.dp)) }
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
