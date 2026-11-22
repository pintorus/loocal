package app.loocal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loocal.domain.Errand
import app.loocal.domain.ErrandRun
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif
import app.loocal.voice.Phase
import app.loocal.voice.VoiceConversation

/**
 * Voice mode for errands (port of App/ErrandVoice.swift): talk to the bot. Each thing you ask
 * becomes an errand on the crew, the bot says it's on it, and when a result comes back it reads it to you.
 */
@Composable
fun ErrandVoiceScreen(state: UiState, vm: ChatViewModel, source: VoiceConversation, close: () -> Unit) {
    val voice by source.state.collectAsState()
    var dispatched by remember { mutableStateOf(setOf<String>()) }
    var announced by remember { mutableStateOf(setOf<String>()) }
    var hops by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { source.start(); onDispose { source.stop() } }
    BackHandler(onBack = close)

    // Errands that joined while the voice screen is open are this conversation's.
    val activeIds = state.runs.filter { it.isActive }.map { it.id }.toSet()
    var lastActive by remember { mutableStateOf(activeIds) }
    LaunchedEffect(activeIds) { val added = activeIds - lastActive; if (added.isNotEmpty()) { dispatched = dispatched + added; hops++ }; lastActive = activeIds }
    // Reads each finished errand from this conversation back once.
    val doneIds = state.runs.filter { it.status == ErrandRun.Status.DONE }.map { it.id }
    LaunchedEffect(doneIds, voice.phase) {
        val run = state.runs.firstOrNull { it.id in dispatched && it.id !in announced && it.status == ErrandRun.Status.DONE } ?: return@LaunchedEffect
        if (voice.phase != Phase.LISTENING) return@LaunchedEffect
        announced = announced + run.id
        hops++
        val lines = plainText(run.text).lines().filter { it.isNotBlank() }
        val body = (if (lines.size > 1) lines.drop(1) else lines).joinToString(" ")
        val first = body.split(Regex("[.!?]")).firstOrNull()?.trim()?.let { "$it." }.orEmpty()
        // Short requests are read back whole; long ones by their title.
        val request = Errand.stripped(run.instructions).trim { !it.isLetterOrDigit() }
        val what = if (request.split(" ").size <= 10) request else run.title
        source.announce("Done: ${what.replaceFirstChar { it.lowercase() }}. $first")
        vm.markSeen(run.id)
    }

    val mood = when {
        voice.muted && voice.phase == Phase.LISTENING -> BotMood.SLEEPY
        voice.phase == Phase.LISTENING -> if (voice.lines.lastOrNull { it.fromUser }?.text?.isNotEmpty() == true) BotMood.CURIOUS else BotMood.ATTENTIVE
        voice.phase == Phase.THINKING -> BotMood.EXCITED
        else -> BotMood.HAPPY
    }
    val status = if (voice.muted && voice.phase == Phase.LISTENING) "Muted" else when (voice.phase) {
        Phase.LISTENING -> "Tell me an errand"; Phase.THINKING -> "Taking it"; Phase.SPEAKING -> "Speaking"
    }
    val time = rememberClock()
    val persona = state.errandPersona

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().background(ink.paper).statusBarsPadding().navigationBarsPadding()) {
        AnimatedContent(status, label = "status", transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.padding(top = 14.dp)) {
            Text(it, style = geist(14, FontWeight.Medium, ink.secondary), modifier = Modifier.shimmer(!(voice.muted && voice.phase == Phase.LISTENING)))
        }
        Spacer(Modifier.weight(1f))
        // Listening, it leans in with your voice; speaking, it bounces with its own.
        val level = source.level(time).toFloat()
        Box(Modifier.height(200.dp), contentAlignment = Alignment.Center) {
            PebbleBot(
                mood, BotShape.of(persona?.id, state.personas), 190.dp, gaze = if (voice.phase == Phase.LISTENING) Offset(0f, 0.35f) else null,
                reaction = hops, tint = BotTint.of(persona?.id, state.personas),
                modifier = Modifier.graphicsLayer {
                    scaleX = 1 + level * 0.05f; scaleY = 1 + level * (if (voice.phase == Phase.SPEAKING) -0.06f else 0.07f)
                    translationY = if (voice.phase == Phase.SPEAKING) -level * 10 * density else 0f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            )
        }
        // Your last words in italic, the bot's answer under them.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(170.dp).padding(top = 20.dp).padding(horizontal = 32.dp)) {
            voice.lines.lastOrNull { it.fromUser }?.takeIf { it.text.isNotEmpty() }?.let {
                Text(it.text, style = serif(24, italic = true, color = ink.secondary).copy(textAlign = TextAlign.Center, letterSpacing = 0.sp), maxLines = 3)
            }
            voice.lines.lastOrNull { !it.fromUser }?.takeIf { it.text.isNotEmpty() }?.let {
                HandwrittenText(AnnotatedString(it.text), live = voice.phase == Phase.SPEAKING, style = serif(30, color = ink.primary).copy(textAlign = TextAlign.Center, letterSpacing = 0.sp))
            }
        }
        Spacer(Modifier.weight(1f))
        // The errands this conversation sent off, as a row of little bots.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(60.dp).padding(bottom = 18.dp)) {
            state.runs.filter { it.id in dispatched }.reversed().takeLast(5).forEach { run ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PebbleBot(run.mood, BotShape.of(run.personaId, state.personas), 34.dp, lively = run.isActive, tint = BotTint.of(run.personaId, state.personas))
                    Text(run.title, style = geist(11, FontWeight.Medium, ink.secondary), maxLines = 1, modifier = Modifier.widthIn(max = 70.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(bottom = 18.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(60.dp).background(if (voice.muted) ink.primary else ink.fill, CircleShape)
                    .semantics { contentDescription = if (voice.muted) "Unmute" else "Mute" }.pressable(0.9f) { source.setMuted(!voice.muted) },
            ) { Icon(if (voice.muted) Icons.Filled.MicOff else Icons.Outlined.Mic, null, tint = if (voice.muted) ink.paper else ink.primary, modifier = Modifier.size(22.dp)) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(60.dp).background(ink.primary, CircleShape).semantics { contentDescription = "End voice errands" }.pressable(0.9f, onClick = close),
            ) { Icon(Icons.Filled.Close, null, tint = ink.paper, modifier = Modifier.size(20.dp)) }
        }
    }
}
