package app.loocal.ui

import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.loocal.domain.Cadence
import app.loocal.domain.Errand
import app.loocal.domain.ErrandIdea
import app.loocal.domain.ErrandRun
import app.loocal.domain.Persona
import app.loocal.ui.theme.InstrumentSerif
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** How a run's little bot looks in lists and the crew. */
val ErrandRun.mood: BotMood get() = when (status) {
    ErrandRun.Status.QUEUED -> if (stage == ErrandRun.NEXT_UP) BotMood.WAITING else BotMood.CURIOUS
    ErrandRun.Status.RUNNING -> BotMood.WORKING
    ErrandRun.Status.DONE -> BotMood.HAPPY
    ErrandRun.Status.FAILED -> BotMood.DIZZY
    ErrandRun.Status.STOPPED -> BotMood.SLEEPY
}

fun cadenceIcon(c: Cadence): ImageVector = when (c) {
    Cadence.NOW -> Icons.Outlined.Bolt; Cadence.DAILY -> Icons.Outlined.WbSunny; Cadence.WEEKDAYS -> Icons.Outlined.WorkOutline; Cadence.WEEKLY -> Icons.Outlined.CalendarMonth
}

private sealed interface PickItem {
    data object Loocal : PickItem
    data class One(val persona: Persona) : PickItem
    data object Add : PickItem
}

/**
 * The errand page (port of iOS `ErrandView`): the bot in the middle, the crew of errands on the
 * go beneath it, and the composer. Sending hands a ticket up to the bot, which catches it.
 * Hold the bot (or slide up from it) to choose who runs your errands.
 */
@Composable
fun ErrandScreen(state: UiState, vm: ChatViewModel, openPersonas: () -> Unit, openVoice: () -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    var reaction by remember { mutableIntStateOf(0) }
    var reactionKind by remember { mutableStateOf(BotReaction.HOP) }
    var dozing by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableStateOf(System.currentTimeMillis()) }
    var picking by remember { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    var burst by remember { mutableStateOf<Pair<BotMood, Pair<String, String>>?>(null) }
    var burstToken by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<String?>(null) }
    var botBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var composerBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var ticket by remember { mutableStateOf<Pair<String, Cadence>?>(null) }

    val crew = state.crew
    val onTheGo = crew.filter { it.isActive }
    val persona = state.errandPersona
    val shape = BotShape.of(persona?.id, state.personas)
    val tint = BotTint.of(persona?.id, state.personas)

    fun react(kind: BotReaction) { reactionKind = kind; reaction++ }
    fun wake() { lastTouch = System.currentTimeMillis(); dozing = false }
    fun flash(mood: BotMood, line: Pair<String, String>, seconds: Long = 1600) {
        burst = mood to line
        val token = ++burstToken
        scope.launch { delay(seconds); if (burstToken == token) burst = null }
    }

    // A result coming in gets a little hop; a trip gets a shake.
    val doneCount = crew.count { it.status == ErrandRun.Status.DONE }
    val failedCount = crew.count { it.status == ErrandRun.Status.FAILED }
    var lastDone by remember { mutableIntStateOf(doneCount) }
    var lastFailed by remember { mutableIntStateOf(failedCount) }
    LaunchedEffect(doneCount) { if (doneCount > lastDone) { react(BotReaction.JUMP); flash(BotMood.PROUD, "Back with" to "results.", 2200) }; lastDone = doneCount }
    LaunchedEffect(failedCount) { if (failedCount > lastFailed) react(BotReaction.SHAKE); lastFailed = failedCount }
    // Nodding off after 45 s with nothing going on.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            dozing = System.currentTimeMillis() - lastTouch > 45_000 && onTheGo.isEmpty() && !focused && !picking && burst == null
        }
    }

    val items: List<PickItem> = (listOf<PickItem>(PickItem.Loocal) + state.personas.map { PickItem.One(it) } + if (state.personas.size < Persona.LIMIT) listOf(PickItem.Add) else emptyList())
        .filterNot { (it == PickItem.Loocal && persona == null) || (it is PickItem.One && it.persona.id == persona?.id) }
    fun label(item: PickItem) = when (item) { PickItem.Loocal -> "Loocal"; is PickItem.One -> item.persona.name; PickItem.Add -> "New persona" }
    fun angle(i: Int): Double {
        val n = items.size
        val span = minOf(150.0, 42.0 * (n - 1))
        val degrees = if (n == 1) -90.0 else -90 - span / 2 + span * i / (n - 1)
        return Math.toRadians(degrees)
    }
    val radius = botBounds.width / 2 + with(density) { 78.dp.toPx() }
    fun spot(i: Int) = Offset(botBounds.center.x + radius * cos(angle(i)).toFloat(), botBounds.center.y + radius * sin(angle(i)).toFloat())
    fun hit(p: Offset): Int? = items.indices.minByOrNull { (spot(it) - p).getDistance() }?.takeIf { (spot(it) - p).getDistance() < with(density) { 44.dp.toPx() } }
    fun choose(item: PickItem) {
        picking = false; hovered = null
        when (item) {
            PickItem.Add -> { openPersonas(); return }
            PickItem.Loocal -> vm.chooseErrandPersona(null)
            is PickItem.One -> vm.chooseErrandPersona(item.persona.id)
        }
        react(BotReaction.SPIN)
        flash(BotMood.LOVE, if (item == PickItem.Loocal) "Back to" to "me." else label(item) to "here.")
    }

    val mood = when {
        picking -> if (hovered == null) BotMood.CURIOUS else BotMood.EXCITED
        burst != null -> burst!!.first
        !state.modelReady || dozing -> BotMood.SLEEPY
        focused -> if (state.errandDraft.isEmpty()) BotMood.ATTENTIVE else BotMood.CURIOUS
        state.errandDraft.isNotEmpty() -> BotMood.EXCITED
        onTheGo.any { it.status == ErrandRun.Status.RUNNING } -> BotMood.WORKING
        onTheGo.isNotEmpty() -> BotMood.CURIOUS
        crew.any { it.status == ErrandRun.Status.FAILED } -> BotMood.DIZZY
        crew.any { it.status == ErrandRun.Status.DONE } -> BotMood.HAPPY
        else -> BotMood.NEUTRAL
    }
    // While you type it reads along the line; a ticket in flight it watches come in.
    val gaze: Offset? = when {
        picking -> hovered?.let { Offset(cos(angle(it)).toFloat() * 0.9f, sin(angle(it)).toFloat() * 0.9f) } ?: Offset(0f, -0.9f)
        ticket != null -> Offset(0f, 0.9f)
        focused -> Offset(-0.6f + (state.errandDraft.substringAfterLast('\n').length % 32) / 32f * 1.2f, 0.85f)
        crew.isNotEmpty() -> Offset(0f, 0.6f)
        else -> null
    }
    val counts = listOf("", "one", "two", "three", "four", "five", "six")
    val line: Pair<String, String>? = when {
        picking && hovered != null -> when (val item = items[hovered!!]) { PickItem.Loocal -> "Just" to "me."; is PickItem.One -> "Send" to item.persona.name + "?"; PickItem.Add -> "Someone" to "new?" }
        picking -> "Who’s" to "going?"
        burst != null -> burst!!.second
        !state.modelReady -> "I need a model" to "first."
        focused && state.errandDraft.isEmpty() -> "Mm-hm," to "go on."
        state.errandDraft.isNotEmpty() -> if (state.errandCadence == Cadence.NOW) "Say the" to "word." else "I’ll set an" to "alarm."
        onTheGo.size == 1 -> "On" to "it."
        onTheGo.size > 1 -> "Juggling" to "${counts[minOf(onTheGo.size, 6)]}."
        crew.any { it.status == ErrandRun.Status.FAILED } -> "That one" to "tripped me."
        crew.any { it.status == ErrandRun.Status.DONE } -> "Back with" to "results."
        else -> null
    }

    fun send() {
        if (state.errandDraft.isBlank() || ticket != null) return
        val title = ErrandIdea.ALL.firstOrNull { it.instructions == state.errandDraft.trim() }?.title ?: app.loocal.domain.Errand.title(state.errandDraft)
        val cadence = state.errandCadence
        focusManager.clearFocus()
        ticket = title to cadence
        scope.launch {
            delay(440)
            wake(); react(BotReaction.JUMP)
            if (vm.sendErrand()) flash(BotMood.EXCITED, if (cadence == Cadence.NOW) "On my" to "way." else "Alarm" to "set.")
            delay(150)
            ticket = null
        }
    }

    Box(Modifier.fillMaxSize().background(ink.paper)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                ModelSelector(state.models.filter { !it.paints }, state.model.id, vm::chooseModel, disabled = state.anyWork, dimmed = !state.modelReady)
            }
            // The stage: the bot, and what it says.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
            ) {
                val botSize by animateDpAsState(if (focused) 96.dp else 128.dp, spring(dampingRatio = 0.85f, stiffness = 300f), label = "bot")
                val pressScale by animateFloatAsState(if (pressing && !picking) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "press")
                AnimatedContent(persona?.id, label = "persona", transitionSpec = { (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) }) { _ ->
                    PebbleBot(
                        mood = mood, shape = shape, size = botSize, gaze = gaze, reaction = reaction, tint = tint, reactionKind = reactionKind,
                        modifier = Modifier.scale(pressScale)
                            .onGloballyPositioned { botBounds = it.boundsInRoot() }
                            .semantics { contentDescription = persona?.let { "Errand bot, ${it.name}" } ?: "Errand bot" }
                            .pointerInput(items.size, picking) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    pressing = true
                                    var moved = false
                                    var openedThisPress = false
                                    val hold = scope.launch { delay(280); if (pressing && !picking) { openedThisPress = true; focusManager.clearFocus(); picking = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) } }
                                    var last = down.position
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        last = change.position
                                        val d = change.position - down.position
                                        if (hypot(d.x, d.y) > 10.dp.toPx()) moved = true
                                        // Sliding up opens the arc straight away.
                                        if (!picking && d.y < -24.dp.toPx()) { hold.cancel(); openedThisPress = true; picking = true }
                                        if (picking) {
                                            val root = Offset(botBounds.left + change.position.x, botBounds.top + change.position.y)
                                            val now = hit(root)
                                            if (now != hovered && now != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            hovered = now
                                        }
                                        if (!change.pressed) break
                                    }
                                    hold.cancel()
                                    pressing = false
                                    if (picking) {
                                        val root = Offset(botBounds.left + last.x, botBounds.top + last.y)
                                        hit(root)?.let { choose(items[it]) } ?: run { if (!(openedThisPress && !moved)) { picking = false } }
                                        hovered = null
                                    } else if (!moved) {
                                        // A poke.
                                        if (dozing) { wake(); react(BotReaction.SHAKE); flash(BotMood.SURPRISED, "Huh?" to "I’m up.") }
                                        else {
                                            wake()
                                            val pokes = listOf(
                                                Triple(BotMood.HAPPY, BotReaction.HOP, "Hi" to "there."), Triple(BotMood.WINK, BotReaction.NOD, "At your" to "service."),
                                                Triple(BotMood.LOVE, BotReaction.WIGGLE, "Hey" to "you."), Triple(BotMood.SURPRISED, BotReaction.HOP, "Oh!" to "Hello."),
                                                Triple(BotMood.EXCITED, BotReaction.SPIN, "Point me" to "somewhere."), Triple(BotMood.ANNOYED, BotReaction.SHAKE, "That" to "tickles."),
                                            )
                                            val pick = pokes[(reaction + 1) % pokes.size]
                                            react(pick.second); flash(pick.first, pick.third)
                                        }
                                    }
                                }
                            },
                    )
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 32.dp).alpha(if (picking) 0.15f else 1f), contentAlignment = Alignment.Center) {
                    AnimatedContent(line, label = "line", transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.96f)) }) { l ->
                        if (l != null) {
                            val size = if (focused) 30 else 36
                            Text(
                                buildAnnotatedString { append(l.first + " "); withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontFamily = InstrumentSerif)) { append(l.second) } },
                                style = serif(size, color = ink.primary).copy(textAlign = TextAlign.Center, lineHeight = (size + 4).sp),
                            )
                        }
                    }
                }
            }
            // The crew, or ideas when nothing is going on.
            AnimatedContent(crew.isNotEmpty(), label = "crew", transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut()) }) { hasCrew ->
                if (hasCrew) CrewStrip(crew, state, open = { opened = it.id }, dismiss = { vm.markSeen(it.id) }, stop = vm::cancelErrand)
                else if (state.modelReady && state.errandDraft.isEmpty() && !focused) ErrandIdeas(vm::useIdea)
                else Spacer(Modifier.height(0.dp))
            }
            Box(Modifier.onGloballyPositioned { composerBounds = it.boundsInRoot() }) {
                ErrandComposer(state, vm, { focused = it; if (it) wake() }, ::send, tint, openVoice)
            }
        }

        // The fan of bots over the main one, on a soft curved band.
        PersonaArc(picking, items, hovered, botBounds, radius, ::angle, ::spot, state, ::label, ::choose) { picking = false; hovered = null }

        // The ticket you wrote, flying from the composer into the bot.
        ticket?.let { (title, cadence) ->
            val progress = remember { Animatable(0f) }
            LaunchedEffect(title) { delay(40); progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 180f)) }
            val from = Offset(composerBounds.center.x, composerBounds.top + with(density) { 30.dp.toPx() })
            val to = Offset(botBounds.center.x, botBounds.center.y + botBounds.height * 0.08f)
            val p = progress.value
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .graphicsLayer {
                        translationX = from.x + (to.x - from.x) * p - size.width / 2
                        translationY = from.y + (to.y - from.y) * p - size.height / 2
                        val s = 1 - 0.8f * p; scaleX = s; scaleY = s; alpha = 1 - p
                    }
                    .height(36.dp).background(ink.paper, CircleShape).border(1.dp, ink.hairline, CircleShape).padding(horizontal = 14.dp),
            ) {
                Icon(cadenceIcon(cadence), null, tint = ink.primary, modifier = Modifier.size(13.dp))
                Text(title, style = geist(14, FontWeight.Medium, ink.primary), maxLines = 1)
            }
        }
    }

    opened?.let { id -> RunSheet(state, vm, id, dismiss = { opened = null }) }
}

@Composable
private fun PersonaArc(
    picking: Boolean, items: List<PickItem>, hovered: Int?, botBounds: androidx.compose.ui.geometry.Rect, radius: Float,
    angle: (Int) -> Double, spot: (Int) -> Offset, state: UiState, label: (PickItem) -> String, choose: (PickItem) -> Unit, close: () -> Unit,
) {
    val reveal by animateFloatAsState(if (picking) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = 250f), label = "arc")
    if (reveal <= 0.01f) return
    val fill = ink.fill
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } }) {
        Canvas(Modifier.fillMaxSize()) {
            if (items.isEmpty()) return@Canvas
            val start = Math.toDegrees(angle(0)).toFloat()
            val sweep = (Math.toDegrees(angle(items.size - 1)) - start).toFloat() + 0.01f
            drawArc(
                fill, start, sweep * reveal, false,
                topLeft = Offset(botBounds.center.x - radius, botBounds.center.y - radius), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(78.dp.toPx(), cap = StrokeCap.Round), alpha = reveal,
            )
        }
        items.forEachIndexed { i, item ->
            val lit = hovered == i
            val at = spot(i)
            val center = botBounds.center
            val pos = Offset(center.x + (at.x - center.x) * reveal, center.y + (at.y - center.y) * reveal)
            val s by animateFloatAsState(if (lit) 1.28f else if (hovered == null) 1f else 0.88f, spring(dampingRatio = 0.62f, stiffness = 500f), label = "pick$i")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.offset { IntOffset((pos.x - 26.dp.toPx()).roundToInt(), (pos.y - 26.dp.toPx()).roundToInt()) }.size(52.dp)
                    .graphicsLayer { scaleX = s * (0.3f + 0.7f * reveal); scaleY = scaleX; alpha = reveal * (if (hovered == null || lit) 1f else 0.5f) }
                    .pointerInput(item) { detectTapGestures { choose(item) } },
            ) {
                when (item) {
                    PickItem.Loocal -> PebbleBot(if (lit) BotMood.EXCITED else BotMood.NEUTRAL, BotShape.PEBBLE, 52.dp)
                    is PickItem.One -> Box {
                        PebbleBot(if (lit) BotMood.EXCITED else BotMood.NEUTRAL, BotShape.of(item.persona.id, state.personas), 52.dp, tint = BotTint.of(item.persona.id, state.personas))
                        Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, (-4).dp).size(20.dp).background(ink.paper, CircleShape).border(1.dp, ink.hairline, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(personaIcon(item.persona.icon), null, tint = ink.primary, modifier = Modifier.size(11.dp))
                        }
                    }
                    PickItem.Add -> {
                        val tertiary = ink.tertiary
                        Canvas(Modifier.size(44.dp)) { drawCircle(tertiary, style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))) }
                        Icon(Icons.Filled.Add, null, tint = ink.secondary, modifier = Modifier.size(18.dp))
                    }
                }
                if (lit) {
                    Text(
                        label(item), style = geist(12, FontWeight.Medium, ink.primary), maxLines = 1, softWrap = false,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp).height(24.dp).background(ink.paper, CircleShape).border(1.dp, ink.hairline, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Errands on the go, side by side: each gets its own little bot. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CrewStrip(runs: List<ErrandRun>, state: UiState, open: (ErrandRun) -> Unit, dismiss: (ErrandRun) -> Unit, stop: (ErrandRun) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        runs.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { run ->
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        CrewCard(run, state, Modifier.pressable(0.97f) { open(run) }.pointerInput(run.id) { detectTapGestures(onLongPress = { menu = true }, onTap = { open(run) }) })
                        DropdownMenu(menu, { menu = false }, containerColor = ink.paper) {
                            if (run.isActive) DropdownMenuItem({ Text("Stop", style = geist(15, color = ink.primary)) }, { menu = false; stop(run) }, leadingIcon = { Icon(Icons.Outlined.Stop, null, tint = ink.primary) })
                            else DropdownMenuItem({ Text("Dismiss", style = geist(15, color = ink.primary)) }, { menu = false; dismiss(run) }, leadingIcon = { Icon(Icons.Filled.Check, null, tint = ink.primary) })
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CrewCard(run: ErrandRun, state: UiState, modifier: Modifier) {
    val status = when (run.status) {
        ErrandRun.Status.QUEUED -> run.stage ?: ErrandRun.NEXT_UP
        ErrandRun.Status.RUNNING -> if (run.text.isEmpty()) run.stage ?: "Thinking" else "Writing"
        ErrandRun.Status.DONE -> "Done · tap to read"
        ErrandRun.Status.FAILED -> "Tripped · tap to see"
        ErrandRun.Status.STOPPED -> "Stopped"
    }
    Box(
        modifier.fillMaxWidth().height(60.dp)
            .background(if (run.status == ErrandRun.Status.DONE) ink.fill else ink.paper, RoundedCornerShape(20.dp))
            .border(1.dp, ink.hairline, RoundedCornerShape(20.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp)) {
            // Waiting bots peek toward the one that's working.
            PebbleBot(run.mood, BotShape.of(run.personaId, state.personas), 32.dp, gaze = if (run.status == ErrandRun.Status.QUEUED) Offset(-0.8f, 0.1f) else null, lively = run.isActive, tint = BotTint.of(run.personaId, state.personas))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(run.title, style = geist(14, FontWeight.Medium, ink.primary), maxLines = 1)
                Text(status, style = geist(12, color = ink.secondary), maxLines = 1, modifier = Modifier.shimmer(run.status == ErrandRun.Status.RUNNING && run.text.isEmpty()))
            }
        }
        if (run.status == ErrandRun.Status.RUNNING) {
            val p by animateFloatAsState(minOf((run.tokens ?: 0) / 768f * 2.5f, 1f), tween(400), label = "tokens")
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp).padding(bottom = 5.dp).fillMaxWidth().height(2.dp).background(ink.hairline, CircleShape)) {
                Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.02f)).height(2.dp).background(ink.primary, CircleShape))
            }
        }
    }
}

@Composable
private fun ErrandIdeas(choose: (ErrandIdea) -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
        ErrandIdea.ALL.forEachIndexed { i, idea ->
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.rise(appeared, 150 + i * 70, 10f).border(1.dp, ink.hairline, RoundedCornerShape(16.dp)).pressable { choose(idea) }.padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(cadenceIcon(idea.cadence), null, tint = ink.primary, modifier = Modifier.size(13.dp))
                    Text(idea.title, style = geist(14, FontWeight.Medium, ink.primary))
                }
                Text(idea.detail, style = geist(13, color = ink.secondary))
            }
        }
    }
}

@Composable
private fun ErrandComposer(state: UiState, vm: ChatViewModel, setFocused: (Boolean) -> Unit, send: () -> Unit, tint: Color?, openVoice: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth().background(ink.fill, RoundedCornerShape(25.dp)).padding(6.dp)) {
        if (!state.modelReady) {
            DownloadComposer(state) { vm.downloadModel() }
            return@Box
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
                if (state.errandDraft.isEmpty()) Text("Describe an errand", style = geist(16, color = ink.tertiary, lineHeight = 22.sp))
                BasicTextField(
                    state.errandDraft, vm::setErrandDraft, maxLines = 6, textStyle = geist(16, color = ink.primary, lineHeight = 22.sp), cursorBrush = SolidColor(ink.primary),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { setFocused(it.isFocused) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourcesMenu(state, vm)
                CadenceMenu(state.errandCadence, vm::setErrandCadence)
                AnimatedVisibility(state.errandCadence != Cadence.NOW, enter = scaleIn(initialScale = 0.8f) + fadeIn(), exit = scaleOut(targetScale = 0.8f) + fadeOut()) {
                    val label = "%02d:%02d".format(state.errandHour, state.errandMinute)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.height(32.dp).background(ink.paper, CircleShape).pressable {
                            TimePickerDialog(context, { _, h, m -> vm.setErrandTime(h, m) }, state.errandHour, state.errandMinute, android.text.format.DateFormat.is24HourFormat(context)).show()
                        }.padding(horizontal = 11.dp),
                    ) { Text(label, style = geist(13, FontWeight.Medium, ink.primary)) }
                }
                Spacer(Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp, 38.dp).semantics { contentDescription = "Voice errands" }.pressable(0.85f, onClick = openVoice)) { AudioLinesIcon(19.dp) }
                val canSend = state.errandDraft.isNotBlank()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(38.dp).background((tint ?: ink.primary).copy(alpha = if (canSend) 1f else 0.25f), CircleShape)
                        .semantics { contentDescription = if (state.errandCadence == Cadence.NOW) "Hand over errand" else "Schedule errand" }
                        .pressable(0.88f, enabled = canSend, onClick = send),
                ) {
                    Icon(if (state.errandCadence == Cadence.NOW) Icons.Filled.ArrowUpward else Icons.Filled.Add, null, tint = if (tint == null) ink.paper else Color.White, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

/** Which connected sources the errand reads before it runs. */
@Composable
private fun SourcesMenu(state: UiState, vm: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    val chosen = state.connectors.filter { it.id in state.errandSources }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.height(32.dp).background(ink.paper, CircleShape).pressable { open = true }.padding(horizontal = 11.dp),
        ) {
            if (chosen.isEmpty()) {
                Icon(Icons.Outlined.Layers, null, tint = ink.secondary, modifier = Modifier.size(14.dp))
                Text("Sources", style = geist(13, FontWeight.Medium, ink.secondary))
            } else {
                chosen.take(3).forEach { Icon(symbolIcon(it.icon), null, tint = ink.primary, modifier = Modifier.size(14.dp)) }
                if (chosen.size > 3) Text("+${chosen.size - 3}", style = geist(12, FontWeight.Medium, ink.primary))
            }
        }
        DropdownMenu(open, { open = false }, containerColor = ink.paper) {
            state.connectors.forEach { c ->
                DropdownMenuItem(
                    { Text(c.name, style = geist(15, color = ink.primary)) }, { vm.toggleErrandSource(c.id) },
                    leadingIcon = { Icon(if (c.id in state.errandSources) Icons.Filled.Check else symbolIcon(c.icon), null, tint = ink.primary) },
                )
            }
        }
    }
}

@Composable
private fun CadenceMenu(cadence: Cadence, set: (Cadence) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.height(32.dp).background(ink.paper, CircleShape).semantics { contentDescription = "Repeat: ${cadence.label}" }.pressable { open = true }.padding(horizontal = 11.dp),
        ) {
            Icon(cadenceIcon(cadence), null, tint = ink.primary, modifier = Modifier.size(13.dp))
            Text(cadence.short, style = geist(13, FontWeight.Medium, ink.primary))
        }
        DropdownMenu(open, { open = false }, containerColor = ink.paper) {
            Cadence.entries.forEach { c ->
                DropdownMenuItem({ Text(c.label, style = geist(15, color = ink.primary)) }, { set(c); open = false }, leadingIcon = { Icon(if (c == cadence) Icons.Filled.Check else cadenceIcon(c), null, tint = ink.primary) })
            }
        }
    }
}

// MARK: - A run, opened

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSheet(state: UiState, vm: ChatViewModel, id: String, dismiss: () -> Unit, openChat: () -> Unit = {}) {
    val run = state.runs.firstOrNull { it.id == id } ?: return
    val context = LocalContext.current
    // Opening a result (or watching it finish) takes it off the crew.
    LaunchedEffect(run.status) { vm.markSeen(id) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(), containerColor = ink.paper, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PebbleBot(run.mood, BotShape.of(run.personaId, state.personas), 22.dp, lively = run.isActive, tint = BotTint.of(run.personaId, state.personas))
                    Text(
                        when (run.status) { ErrandRun.Status.RUNNING -> "On it"; ErrandRun.Status.QUEUED -> run.stage ?: ErrandRun.NEXT_UP; else -> SimpleDateFormat("EEEE HH:mm", Locale.getDefault()).format(Date(run.startedAt)) },
                        style = geist(12, FontWeight.Medium, ink.secondary),
                    )
                }
                Text(run.title, style = serif(30, color = ink.primary))
                Text(run.instructions, style = geist(14, color = ink.secondary), maxLines = 3)
            }
            val shown = StreamingText.stable(run.text, run.status == ErrandRun.Status.RUNNING)
            if (shown.isEmpty() && run.isActive) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SpiralLoader(18.dp)
                    Text(run.stage ?: "Working", style = geist(15, color = ink.secondary))
                }
            } else if (shown.isNotEmpty()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    HandwrittenText(renderMarkdown(shown), live = run.status == ErrandRun.Status.RUNNING, style = geist(16, color = ink.primary, lineHeight = 24.sp))
                }
                if (run.status == ErrandRun.Status.RUNNING) SpiralLoader(14.dp, ink.secondary)
            }
            (run.note ?: if (run.status == ErrandRun.Status.STOPPED) "Stopped · partial result saved" else null)?.let { Text(it, style = geist(12, color = ink.tertiary)) }
            if (run.isActive) {
                Pill("Stop", Icons.Outlined.Stop) { vm.cancelErrand(run) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Continue in chat", Icons.Outlined.ChatBubbleOutline) { vm.continueInChat(run); dismiss(); openChat() }
                    Pill("Again", Icons.Outlined.Refresh) { vm.rerun(run); dismiss() }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).border(1.dp, ink.hairline, CircleShape).semantics { contentDescription = "Copy" }.pressable {
                        val clip = android.content.ClipData.newPlainText("Loocal", plainText(run.text)).apply {
                            description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                        }
                        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                    }) { Icon(Icons.Outlined.ContentCopy, null, tint = ink.primary, modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Pill(title: String, icon: ImageVector, run: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(32.dp).border(1.dp, ink.hairline, CircleShape).pressable(onClick = run).padding(horizontal = 12.dp),
    ) {
        Icon(icon, null, tint = ink.primary, modifier = Modifier.size(14.dp))
        Text(title, style = geist(13, FontWeight.Medium, ink.primary))
    }
}

// MARK: - Results

@Composable
fun ResultsScreen(state: UiState, vm: ChatViewModel, openChat: () -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    var opened by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(ink.paper)) {
        if (state.runs.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().rise(appeared, 80)) {
                PebbleBot(BotMood.SLEEPY, size = 64.dp, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.height(10.dp))
                Text("No errands run yet.", style = serif(28, italic = true, color = ink.primary))
                Spacer(Modifier.height(10.dp))
                Text("What they bring back lands here, on this phone.", style = geist(14, color = ink.secondary))
            }
        } else {
            val day = { offset: Int -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
            val groups = state.runs.groupBy { when { it.startedAt >= day(0) -> 0; it.startedAt >= day(-1) -> 1; it.startedAt >= day(-6) -> 2; else -> 3 } }.toSortedMap()
            val names = listOf("Today", "Yesterday", "Previous 7 days", "Earlier")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                groups.forEach { (g, runs) ->
                    item(key = "h$g") { Text(names[g], style = geist(13, FontWeight.Medium, ink.secondary), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                    items(runs.sortedByDescending { it.startedAt }, key = { it.id }) { run ->
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().rise(appeared, minOf(40 * g, 300), 8f)
                                    .pointerInput(run.id) { detectTapGestures(onTap = { opened = run.id }, onLongPress = { if (!run.isActive) menu = true }) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            ) {
                                PebbleBot(run.mood, BotShape.of(run.personaId, state.personas), 26.dp, lively = run.isActive, tint = BotTint.of(run.personaId, state.personas))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(run.title, style = geist(16, color = ink.primary), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                        if (run.errandId != null) Icon(Icons.Outlined.Repeat, null, tint = ink.tertiary, modifier = Modifier.padding(start = 6.dp).size(11.dp))
                                        Spacer(Modifier.weight(1f))
                                        Text(SimpleDateFormat(if (run.startedAt >= day(0)) "HH:mm" else "MMM d", Locale.getDefault()).format(Date(run.startedAt)), style = geist(12, color = ink.tertiary))
                                    }
                                    Text(preview(run), style = geist(14, color = ink.secondary), maxLines = 2)
                                }
                            }
                            DropdownMenu(menu, { menu = false }, containerColor = ink.paper) {
                                DropdownMenuItem({ Text("Delete", style = geist(15, color = Color(0xFFFF3B30))) }, { menu = false; vm.deleteRun(run) })
                            }
                        }
                        Hairline(Modifier.padding(start = 58.dp))
                    }
                }
            }
        }
    }
    opened?.let { RunSheet(state, vm, it, dismiss = { opened = null }, openChat = openChat) }
}

private fun preview(run: ErrandRun): String {
    if (run.isActive && run.text.isEmpty()) return run.stage ?: "Working on it…"
    if (run.status == ErrandRun.Status.FAILED) return run.note ?: "Couldn’t finish."
    val lines = plainText(run.text).lines().map { it.trim() }.filter { it.isNotEmpty() }
    // Each line reads as its own sentence once they're run together.
    return (if (lines.size > 1) lines.drop(1) else lines).joinToString(" ") { if (it.last() in ".!?:…") it else "$it." }
}

// MARK: - Routines

@Composable
fun RoutinesScreen(state: UiState, vm: ChatViewModel, newErrand: () -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    Box(Modifier.fillMaxSize().background(ink.paper)) {
        if (state.errands.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).rise(appeared, 80)) {
                PebbleBot(BotMood.WAITING, size = 64.dp, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.height(10.dp))
                Text("Nothing on repeat.", style = serif(28, italic = true, color = ink.primary))
                Spacer(Modifier.height(10.dp))
                Text("Schedule an errand and it’s ready when you are.", style = geist(14, color = ink.secondary), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.height(32.dp).border(1.dp, ink.hairline, CircleShape).pressable(onClick = newErrand).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text("New errand", style = geist(13, FontWeight.Medium, ink.primary))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 32.dp)) {
                SectionLabel("On repeat", Modifier.padding(start = 4.dp))
                state.errands.forEachIndexed { i, errand -> RoutineCard(state, vm, errand, Modifier.rise(appeared, minOf(50 * i, 300), 8f)) }
                Text("At each time Loocal sends a notification. Open it, or be in the app, and the errand runs on this phone.", style = geist(13, color = ink.tertiary), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun RoutineCard(state: UiState, vm: ChatViewModel, errand: Errand, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val last = state.runs.firstOrNull { it.errandId == errand.id }
    val status = when {
        errand.paused -> "Paused"
        last?.status == ErrandRun.Status.RUNNING -> "Running now"
        else -> errand.nextRun?.let { next ->
            val today = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = next }
            val day = when (c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)) { 0 -> "today"; 1 -> "tomorrow"; else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(next)) }
            "Next $day " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next))
        }.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth().border(1.dp, ink.hairline, RoundedCornerShape(22.dp)).alpha(if (errand.paused) 0.55f else 1f).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(ink.fill, CircleShape)) { Icon(cadenceIcon(errand.cadence), null, tint = ink.primary, modifier = Modifier.size(17.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(errand.title, style = geist(16, FontWeight.Medium, ink.primary), maxLines = 1)
                Text(errand.schedule, style = geist(13, color = ink.secondary))
            }
            Box {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).semantics { contentDescription = "Errand options" }.pressable { menu = true }) {
                    Icon(Icons.Outlined.MoreHoriz, null, tint = ink.secondary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(menu, { menu = false }, containerColor = ink.paper) {
                    DropdownMenuItem({ Text("Run now", style = geist(15, color = ink.primary)) }, { menu = false; vm.runErrand(errand.title, errand.instructions, errand.sources, errand.id) }, leadingIcon = { Icon(Icons.Outlined.Bolt, null, tint = ink.primary) })
                    DropdownMenuItem({ Text(if (errand.paused) "Resume" else "Pause", style = geist(15, color = ink.primary)) }, { menu = false; vm.togglePause(errand) }, leadingIcon = { Icon(if (errand.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null, tint = ink.primary) })
                    DropdownMenuItem({ Text("Delete", style = geist(15, color = Color(0xFFFF3B30))) }, { menu = false; vm.deleteRoutine(errand) })
                }
            }
        }
        Text(errand.instructions, style = geist(14, color = ink.secondary), maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.connectors.filter { it.id in errand.sources }.forEach { Icon(symbolIcon(it.icon), it.name, tint = ink.secondary, modifier = Modifier.size(12.dp)) }
            Spacer(Modifier.weight(1f))
            Text(status, style = geist(12, color = ink.tertiary))
        }
    }
}
