package app.loocal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif
import app.loocal.voice.Phase
import app.loocal.voice.VoiceConversation
import kotlin.math.PI
import kotlin.math.sin

/** Full-screen voice conversation (port of App/ConversationView.swift): the Tide with handwritten captions. */
@Composable
fun ConversationScreen(conversation: VoiceConversation, close: () -> Unit) {
    val state by conversation.state.collectAsState()
    var appeared by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { appeared = true; conversation.start(); onDispose { conversation.stop() } }
    BackHandler(onBack = close)
    val enter by animateFloatAsState(if (appeared) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 120f), label = "enter")

    Box(Modifier.fillMaxSize().background(ink.paper)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = enter; val s = 0.96f + 0.04f * enter; scaleX = s; scaleY = s }) {
            Tide(conversation, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp).offset(y = 120.dp))
            Captions(state.lines, Modifier.fillMaxSize().statusBarsPadding().padding(top = 40.dp, bottom = 170.dp))
        }
        // Status, top centre.
        val status = if (state.muted && state.phase == Phase.LISTENING) "Muted" else when (state.phase) {
            Phase.LISTENING -> "Listening"; Phase.THINKING -> "Thinking"; Phase.SPEAKING -> "Speaking"
        }
        AnimatedContent(status, label = "status", transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp)) {
            Text(it, style = geist(14, FontWeight.Medium, ink.secondary), modifier = Modifier.shimmer(!(state.muted && state.phase == Phase.LISTENING)))
        }
        // Over the tide the controls invert: they sit on ink, not paper.
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(60.dp).background(if (state.muted) ink.paper else ink.paper.copy(alpha = 0.14f), CircleShape)
                    .semantics { contentDescription = if (state.muted) "Unmute" else "Mute" }
                    .pressable(0.9f) { conversation.setMuted(!state.muted) },
            ) { Icon(if (state.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, null, tint = if (state.muted) ink.primary else ink.paper, modifier = Modifier.size(22.dp)) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(60.dp).background(ink.paper, CircleShape).semantics { contentDescription = "End conversation" }.pressable(0.9f, onClick = close),
            ) { Icon(Icons.Filled.Close, null, tint = ink.primary, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Captions: you on the right in italic, the model on the left; older lines fade back. */
@Composable
private fun Captions(lines: List<app.loocal.voice.Line>, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.lastOrNull()?.text) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
            }
            .verticalScroll(scroll).padding(horizontal = 24.dp),
    ) {
        lines.forEachIndexed { i, line ->
            val current = i == lines.lastIndex
            HandwrittenText(
                AnnotatedString(line.text.ifEmpty { " " }), live = current,
                style = (if (line.fromUser) serif(24, italic = true, color = ink.secondary) else serif(26, color = ink.primary))
                    .copy(textAlign = if (line.fromUser) TextAlign.End else TextAlign.Start, lineHeight = 30.sp, letterSpacing = 0.sp),
                modifier = Modifier.fillMaxWidth()
                    .padding(start = if (line.fromUser) 40.dp else 0.dp, end = if (line.fromUser) 0.dp else 40.dp)
                    .graphicsLayer { alpha = if (current) 1f else 0.45f },
            )
        }
    }
}

/**
 * A liquid ink swell rising from the bottom edge, behind the controls. It heaves with the voice,
 * settles low and breathes while thinking (same curve as iOS `TideSwell`).
 */
@Composable
private fun Tide(conversation: VoiceConversation, modifier: Modifier) {
    val time = rememberClock()
    val state by conversation.state.collectAsState()
    val thinking = state.phase == Phase.THINKING
    val primary = ink.primary
    val d = androidx.compose.ui.platform.LocalDensity.current.density
    Box(modifier) {
        val level = if (thinking) 0.1 + 0.05 * sin(time * 2.2) else conversation.level(time)
        fun surface(w: Float, h: Float, lift: Float): Path {
            val crest = h * (0.42f - 0.26f * level.toFloat())
            return Path().apply {
                moveTo(0f, h)
                for (k in 0..90) {
                    val u = k / 90.0
                    val dome = sin(u * PI)
                    val swell = (5 + 20 * level) * sin(u * 2 * PI * 1.4 + time * 2.3) + (3 + 12 * level) * sin(u * 2 * PI * 3.2 - time * 3.4) + 2.5 * sin(u * 2 * PI * 0.7 + time * 0.9)
                    val y = h - (h - crest) * (0.5 + 0.5 * dome) + swell * d
                    lineTo((w * u).toFloat(), y.toFloat() - lift)
                }
                lineTo(w, h); close()
            }
        }
        // Soft glow above the surface, brighter with the voice.
        Canvas(Modifier.fillMaxSize().blur(34.dp).graphicsLayer { alpha = (0.16 + 0.32 * level).toFloat() }) {
            drawPath(surface(size.width, size.height, 22.dp.toPx()), primary)
        }
        Canvas(Modifier.fillMaxSize()) { drawPath(surface(size.width, size.height, 0f), primary) }
        AnimatedVisibility(thinking, enter = scaleIn(initialScale = 0.5f) + fadeIn(), exit = scaleOut(targetScale = 0.5f) + fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 168.dp)) {
            SpiralLoader(40.dp, ink.paper)
        }
    }
}

/** The "audio-lines" glyph (six rounded strokes, after Lucide), as on iOS. */
@Composable
fun AudioLinesIcon(size: Dp = 20.dp, color: Color = ink.primary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        val bars = listOf(Triple(2f, 10f, 13f), Triple(6f, 6f, 17f), Triple(10f, 3f, 21f), Triple(14f, 8f, 15f), Triple(18f, 5f, 18f), Triple(22f, 10f, 13f))
        bars.forEach { (x, top, bottom) ->
            drawLine(color, androidx.compose.ui.geometry.Offset(x * s, top * s), androidx.compose.ui.geometry.Offset(x * s, bottom * s), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
        }
    }
}
