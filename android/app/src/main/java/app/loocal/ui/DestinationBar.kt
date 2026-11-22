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
import kotlin.math.cos
import kotlin.math.sin

enum class Tab(val label: String) { HISTORY("History"), CHAT("New chat"), SETTINGS("Settings") }

/**
 * Soma-style destination bar (RootView.swift `DestinationBar`): three label-free icons in a
 * row of at most 348 pt, the selected one sitting on a soft pill. No container chrome.
 */
@Composable
fun DestinationBar(selected: Tab, choose: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(modifier.widthIn(max = 348.dp).fillMaxWidth().height(54.dp)) {
        // Three 72 pt slots spread across the row with 24 pt side padding.
        val inner = maxWidth - 48.dp
        val gap = (inner - 72.dp * 3) / 2
        fun slotX(i: Int) = 24.dp + (72.dp + gap) * i
        val pillX by animateDpAsState(slotX(selected.ordinal), spring(dampingRatio = 0.78f, stiffness = 380f), label = "pill")
        Box(Modifier.offset(x = pillX, y = 6.dp).size(72.dp, 42.dp).background(ink.fill, CircleShape))
        Tab.entries.forEachIndexed { i, tab ->
            val isSelected = tab == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = slotX(i), y = 6.dp)
                    .size(72.dp, 42.dp)
                    .semantics { contentDescription = tab.label; this.selected = isSelected }
                    .pressable(0.9f) {
                        if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        choose(tab)
                    },
            ) {
                val color = if (isSelected) ink.primary else ink.tertiary
                when (tab) {
                    Tab.HISTORY -> HistoryGlyph(isSelected, color)
                    Tab.CHAT -> ChatGlyph(isSelected, color)
                    Tab.SETTINGS -> GearGlyph(isSelected, color)
                }
            }
        }
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
