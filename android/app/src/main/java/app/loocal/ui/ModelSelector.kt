package app.loocal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Brush
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.loocal.inference.ModelManifest
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Centred model picker (port of App/ModelSelector.swift). At rest a capsule hugs the current
 * name. Press and drag sideways and it widens (height never changes) into a carousel of model
 * names that follows the finger and snaps to the nearest one, ticking as each passes centre.
 * A tap opens it; tapping a neighbour picks it; left alone it closes after 2.5 s.
 */
@Composable
fun ModelSelector(
    options: List<ModelManifest>, selection: String, select: (String) -> Unit, disabled: Boolean, dimmed: Boolean,
    /** Where the capsule sits on screen (window px), for the paint poured out of it. */
    placed: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val slot = 124.dp
    val index = options.indexOfFirst { it.id == selection }.coerceAtLeast(0)
    val nameStyle = geist(15, FontWeight.Medium)
    val collapsed = with(density) { measurer.measure(options[index].name, nameStyle).size.width.toDp() } + 34.dp
    // Picture models sit past the chat models on the far right, across a gap marked by a brush.
    val firstPicture = options.indexOfFirst { it.paints }.takeIf { it > 0 }
    val painted = options[index].paints
    val paint = liquidPaintBrush()
    LaunchedEffect(painted) { if (painted) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    var expanded by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val live = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { if (!dragging) live.animateTo(index.toFloat(), spring(dampingRatio = 0.82f, stiffness = 300f)) }
    // A tap-opened carousel closes itself after a moment of no interaction.
    LaunchedEffect(expanded, dragging) { if (expanded && !dragging) { delay(2500); expanded = false } }

    val width by animateDpAsState(if (expanded) 300.dp else collapsed, spring(dampingRatio = 0.82f, stiffness = 300f), label = "width")
    val currentIndex by rememberUpdatedState(index)
    val currentExpanded by rememberUpdatedState(expanded)

    // Crossing the brush takes half a slot more drag than moving between neighbours.
    fun acrossBrush(start: Float, raw: Float): Float {
        val gap = (firstPicture ?: return raw) - 0.5f
        return when {
            start < gap && raw > gap -> maxOf(gap, raw - 0.5f)
            start > gap && raw < gap -> minOf(gap, raw + 0.5f)
            else -> raw
        }
    }

    fun rubberBand(value: Float): Float {
        val upper = (options.size - 1).toFloat()
        return when {
            value < 0 -> -(1 - 1 / (1 - value * 0.6f))
            value > upper -> upper + (1 - 1 / (1 + (value - upper) * 0.6f))
            else -> value
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width).height(38.dp)
            .graphicsLayer { val s = if (dragging) 1.02f else 1f; scaleX = s; scaleY = s }
            .shadow(if (expanded) 12.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
            .onGloballyPositioned { placed(it.boundsInWindow()) }
            .then(if (painted) Modifier.background(paint, CircleShape) else Modifier.background(ink.fill, CircleShape))
            .border(1.dp, if (expanded && !painted) ink.hairline else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .alpha(if (disabled) 0.5f else 1f)
            .semantics { contentDescription = "Model"; stateDescription = options[index].name + if (painted) ", paints pictures" else "" }
            .pointerInput(disabled, options.size) {
                if (disabled) return@pointerInput
                val slotPx = slot.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val openedByTap = !currentExpanded
                    val start = currentIndex.toFloat()
                    dragging = true
                    expanded = true
                    if (openedByTap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val tracker = VelocityTracker()
                    var moved = false
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dx = change.position.x - down.position.x
                        lastX = change.position.x
                        if (abs(dx) > 2 * density.density) {
                            moved = moved || abs(dx) > 4 * density.density
                            val value = rubberBand(acrossBrush(start, start - dx / slotPx))
                            scope.launch { live.snapTo(value) }
                            val nearest = value.roundToInt()
                            if (nearest in options.indices && nearest != currentIndex) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                select(options[nearest].id)
                            }
                            change.consume()
                        }
                        if (!change.pressed) break
                    }
                    dragging = false
                    val target: Int? = when {
                        moved -> acrossBrush(start, start - (lastX - down.position.x + tracker.calculateVelocity().x * 0.25f) / slotPx).roundToInt()
                        openedByTap -> null // a plain tap just opens the carousel
                        else -> (live.value + (lastX - size.width / 2f) / slotPx).roundToInt() // tap on a neighbour picks it
                    }
                    if (target != null) {
                        var t = target.coerceIn(0, options.size - 1)
                        // Crossing the brush takes a longer drag than moving between neighbours; a fling never jumps it.
                        if (firstPicture != null && moved) {
                            val held = live.value.roundToInt()
                            if ((held < firstPicture) != (t < firstPicture)) t = if (held < firstPicture) firstPicture - 1 else firstPicture
                        }
                        select(options[t].id)
                        scope.launch { live.animateTo(t.toFloat(), spring(dampingRatio = 0.82f, stiffness = 300f)) }
                        expanded = false
                    }
                }
            }
            // Soft edges while open, so names fade as they leave.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (expanded) {
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        firstPicture?.let { gap ->
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Brush, "Pictures past here",
                tint = if (painted) Color.White else Color(0xFFF21F9E),
                modifier = androidx.compose.ui.Modifier.size(14.dp).graphicsLayer {
                    translationX = (gap - 0.5f - live.value) * slot.toPx()
                    alpha = if (expanded) maxOf(0f, 1 - abs(gap - 0.5f - live.value) * 0.42f) else 0f
                },
            )
        }
        options.forEachIndexed { i, option ->
            val distance = abs(i - live.value)
            val alpha by animateFloatAsState(if (expanded) maxOf(0f, 1 - distance * 0.42f) else if (distance < 0.5f) 1f else 0f, label = "alpha$i")
            Text(
                option.name,
                maxLines = 1, softWrap = false,
                style = geist(15, if (distance < 0.5f) FontWeight.Medium else FontWeight.Normal,
                    if (painted) Color.White.copy(alpha = if (distance < 0.5f) 1f else 0.72f) else if (distance < 0.5f) (if (dimmed) ink.secondary else ink.primary) else ink.tertiary),
                modifier = Modifier.graphicsLayer {
                    translationX = (i - live.value) * slot.toPx()
                    val s = 1 - minOf(distance, 2f) * 0.07f
                    scaleX = s; scaleY = s
                    this.alpha = alpha
                },
            )
        }
    }
}
