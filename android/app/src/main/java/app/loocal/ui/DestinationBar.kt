package app.loocal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.loocal.ui.theme.ink
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.cos
import kotlin.math.sin

enum class Tab(val label: String) {
    HISTORY("History"), CHAT("New chat"), SETTINGS("Settings"), RESULTS("Results"), ERRAND("New errand"), ROUTINES("Routines");
    /** 0: conversations, 1: errands. */
    val space get() = ordinal / 3
}

/**
 * Soma-style destination bar (RootView.swift `DestinationBar`): three label-free icons in a
 * row of at most 348 pt, the selected one sitting on a soft pill. No container chrome.
 */
/**
 * Soma-style destination bar (RootView.swift `DestinationBar`): three label-free icons in a row of
 * at most 348 pt, the selected one on a soft pill. Two sets of three sit side by side on one
 * track: conversations, then errands. Swiping across the bar slides the track under the finger and,
 * released past the middle or flicked, carries on to the other set.
 */
@Composable
fun DestinationBar(selected: Tab, remembered: Map<Int, Tab>, choose: (Tab) -> Unit, flip: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    var hint by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.widthIn(max = 348.dp).fillMaxWidth().height(54.dp)) {
        val width = constraints.maxWidth.toFloat()
        val page = selected.space
        val progress = (page - drag.value / width).coerceIn(0f, 1f)
        LaunchedEffect(hint) { if (hint) { delay(1200); hint = false } }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(page) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val toward = (page == 0 && drag.value < 0) || (page == 1 && drag.value > 0)
                            if (toward && kotlin.math.abs(drag.value) > width * 0.45f) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                hint = true
                                flip(1 - page)
                                scope.launch { drag.snapTo(0f) }
                            } else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 300f)) }
                        },
                    ) { change, amount ->
                        change.consume()
                        val next = drag.value + amount
                        val toward = (page == 0 && next < 0) || (page == 1 && next > 0)
                        // Resistance past the end, with nothing beyond.
                        scope.launch { drag.snapTo(if (toward) next.coerceIn(-width, width) else next * 0.3f) }
                    }
                },
        ) {
            listOf(0, 1).forEach { set ->
                val distance = kotlin.math.abs(progress - set)
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { translationX = (set - page) * width + drag.value; alpha = 1 - minOf(distance, 1f) * 0.65f; val sc = 1 - minOf(distance, 1f) * 0.06f; scaleX = sc; scaleY = sc }
                        .drawWithContent { drawContent() },
                ) { Row3(set, remembered[set] ?: Tab.entries[set * 3 + 1], set == page, choose) }
            }
            // Two small marks for the two sets; the active one stretches as you swipe.
            if (hint || drag.value != 0f) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.align(Alignment.BottomCenter).offset(y = 9.dp)) {
                    val primary = ink.primary
                    Box(Modifier.size((5 + 9 * (1 - progress)).dp, 5.dp).background(primary.copy(alpha = 0.35f + 0.65f * (1 - progress)), CircleShape))
                    Box(Modifier.size((5 + 9 * progress).dp, 5.dp).background(primary.copy(alpha = 0.35f + 0.65f * progress), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun Row3(set: Int, selected: Tab, active: Boolean, choose: (Tab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inner = maxWidth - 48.dp
        val gap = (inner - 72.dp * 3) / 2
        fun slotX(i: Int) = 24.dp + (72.dp + gap) * i
        val pillX by animateDpAsState(slotX(selected.ordinal % 3), spring(dampingRatio = 0.78f, stiffness = 380f), label = "pill")
        Box(Modifier.offset(x = pillX, y = 6.dp).size(72.dp, 42.dp).background(ink.fill, CircleShape))
        Tab.entries.filter { it.space == set }.forEachIndexed { i, tab ->
            val isSelected = tab == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.offset(x = slotX(i), y = 6.dp).size(72.dp, 42.dp)
                    .semantics { contentDescription = tab.label; this.selected = isSelected }
                    .pressable(0.9f, enabled = active) { if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); choose(tab) },
            ) {
                val color = if (isSelected) ink.primary else ink.tertiary
                when (tab) {
                    Tab.HISTORY -> HistoryGlyph(isSelected, color)
                    Tab.CHAT -> ChatGlyph(isSelected, color)
                    Tab.SETTINGS -> GearGlyph(isSelected, color)
                    Tab.RESULTS -> ResultsGlyph(isSelected, color)
                    Tab.ERRAND -> BotGlyph(isSelected, color)
                    Tab.ROUTINES -> RoutineGlyph(isSelected, color)
                }
            }
        }
    }
}

/** New errand: the bot's face. Picked, the pebble fills in and the eyes cut through. */
@Composable
private fun BotGlyph(selected: Boolean, color: Color) {
    val pop = popScale(selected)
    Canvas(Modifier.size(22.dp, 21.dp).graphicsLayer { scaleX = pop; scaleY = pop; compositingStrategy = CompositingStrategy.Offscreen }) {
        val body = botBody(BotShape.PEBBLE, size.width, size.height)
        if (selected) drawPath(body, color) else drawPath(body, color, style = Stroke(1.6.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val eye = Size(2.4.dp.toPx(), 5.4.dp.toPx())
        val cx = size.width / 2 + 0.9.dp.toPx(); val cy = size.height / 2 + 0.6.dp.toPx(); val gap = 3.2.dp.toPx()
        val blend = if (selected) BlendMode.DstOut else BlendMode.SrcOver
        for (x in listOf(cx - gap / 2 - eye.width / 2, cx + gap / 2 + eye.width / 2)) {
            drawRoundRect(if (selected) Color.Black else color, Offset(x - eye.width / 2, cy - eye.height / 2), eye, CornerRadius(eye.width / 2), blendMode = blend)
        }
    }
}

/** Results: a delivered card with a tick, tilting a little when picked. */
@Composable
private fun ResultsGlyph(selected: Boolean, color: Color) {
    val pop = popScale(selected)
    val tilt by animateFloatAsState(if (selected) -6f else 0f, spring(dampingRatio = 0.65f, stiffness = 200f), label = "tilt")
    Canvas(Modifier.size(24.dp).padding(horizontal = 2.5.dp, vertical = 2.dp).graphicsLayer { scaleX = pop; scaleY = pop; rotationZ = tilt; compositingStrategy = CompositingStrategy.Offscreen }) {
        val r = CornerRadius(6.5.dp.toPx())
        if (selected) drawRoundRect(color, cornerRadius = r) else drawRoundRect(color, Offset(0.8.dp.toPx(), 0.8.dp.toPx()), Size(size.width - 1.6.dp.toPx(), size.height - 1.6.dp.toPx()), r, style = Stroke(1.6.dp.toPx()))
        val w = 8.5.dp.toPx(); val h = 6.dp.toPx(); val left = center.x - w / 2; val top = center.y - h / 2 + 0.5.dp.toPx()
        val tick = Path().apply { moveTo(left, top + h / 2); lineTo(left + w * 0.36f, top + h); lineTo(left + w, top) }
        drawPath(tick, if (selected) Color.Black else color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round), blendMode = if (selected) BlendMode.DstOut else BlendMode.SrcOver)
    }
}

/** Routines: a soft triangle as a clock face; picked, it fills in and the hands tick forward. */
@Composable
private fun RoutineGlyph(selected: Boolean, color: Color) {
    val minute by animateFloatAsState(if (selected) 360f else 0f, spring(dampingRatio = 0.8f, stiffness = 50f), label = "minute")
    val pop = popScale(selected)
    Canvas(Modifier.size(25.dp, 24.dp).offset(y = (-2.5).dp).graphicsLayer { scaleX = pop; scaleY = pop; compositingStrategy = CompositingStrategy.Offscreen }) {
        val tri = roundedTriangle(size.width, size.height, -0.04f, 0.24f)
        if (selected) drawPath(tri, color) else drawPath(tri, color, style = Stroke(1.6.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val c = Offset(center.x, center.y + 3.2.dp.toPx())
        val hand = if (selected) Color.Black else color
        val blend = if (selected) BlendMode.DstOut else BlendMode.SrcOver
        val w = 1.7.dp.toPx()
        drawLine(hand, c, Offset(c.x, c.y - 4.4.dp.toPx()), w, StrokeCap.Round, blendMode = blend)
        val a = Math.toRadians(minute.toDouble())
        drawLine(hand, c, Offset(c.x + (4.dp.toPx() * cos(a)).toFloat(), c.y + (4.dp.toPx() * sin(a)).toFloat()), w, StrokeCap.Round, blendMode = blend)
    }
}

/** A little pop when a glyph is picked (iOS `GlyphPop`). */
@Composable
private fun popScale(selected: Boolean): Float {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            scale.animateTo(0.82f, tween(100))
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
    }
    return scale.value
}

/** History: a clock. Picked, the face fills in and the minute hand sweeps a full turn. */
@Composable
private fun HistoryGlyph(selected: Boolean, color: Color) {
    val minute by animateFloatAsState(if (selected) 360f else 0f, spring(dampingRatio = 0.8f, stiffness = 50f), label = "minute")
    val pop = popScale(selected)
    Canvas(
        Modifier.size(24.dp).padding(2.dp)
            .graphicsLayer { scaleX = pop; scaleY = pop; compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val c = center
        val r = size.minDimension / 2
        if (selected) drawCircle(color, r) else drawCircle(color, r - 0.8.dp.toPx(), style = Stroke(1.6.dp.toPx()))
        val handColor = if (selected) Color.Black else color
        val blend = if (selected) BlendMode.DstOut else BlendMode.SrcOver
        val w = 1.7.dp.toPx()
        drawLine(handColor, c, Offset(c.x, c.y - 5.dp.toPx()), w, StrokeCap.Round, blendMode = blend)
        val a = Math.toRadians(minute.toDouble() + 90.0) // starts pointing right (3 o'clock)
        val len = 4.2.dp.toPx()
        drawLine(handColor, c, Offset(c.x + (len * sin(a)).toFloat(), c.y - (len * cos(a)).toFloat()), w, StrokeCap.Round, blendMode = blend)
    }
}

/** Chat: a speech bubble that, picked, fills in and pops up into place like a sent text. */
@Composable
private fun ChatGlyph(selected: Boolean, color: Color) {
    val rise = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            rise.snapTo(3f); scale.snapTo(0.78f)
            kotlinx.coroutines.coroutineScope {
                launch { scale.animateTo(1.03f, spring(dampingRatio = 1f, stiffness = 500f)); scale.animateTo(1f, spring(dampingRatio = 1f, stiffness = 300f)) }
                launch { rise.animateTo(-0.4f, spring(dampingRatio = 1f, stiffness = 500f)); rise.animateTo(0f, spring(dampingRatio = 1f, stiffness = 300f)) }
            }
        }
    }
    Icon(
        if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline, null, tint = color,
        modifier = Modifier.size(22.dp).graphicsLayer {
            scaleX = scale.value; scaleY = scale.value; translationY = rise.value * density
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
        },
    )
}

/** Settings: a gear that turns a quarter when picked. */
@Composable
private fun GearGlyph(selected: Boolean, color: Color) {
    var turns by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(selected) { if (selected) turns += 1f }
    val angle by animateFloatAsState(turns * 90f, spring(dampingRatio = 0.62f, stiffness = 80f), label = "gear")
    val pop = popScale(selected)
    Icon(
        if (selected) Icons.Filled.Settings else Icons.Outlined.Settings, null, tint = color,
        modifier = Modifier.size(23.dp).rotate(angle).graphicsLayer { scaleX = pop; scaleY = pop },
    )
}
