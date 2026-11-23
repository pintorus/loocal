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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
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
 *
 * Picture models sit past the chat models on the far right, across a gap marked by a brush.
 * Crossing it takes a longer drag than moving between neighbours (the carousel holds back, then
 * gives), either way. On a picture model the capsule turns to wet paint.
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
    // First picture model, if any.
    val split = options.indexOfFirst { it.paints }.takeIf { it > 0 }
    val painted = options[index].paints

    /** Where option [i] sits in drag space (fingers) and on screen. */
    fun dragPosition(i: Int) = i + if (split != null && i >= split) BARRIER else 0f
    fun screenPosition(i: Int) = i + if (split != null && i >= split) GAP else 0f
    /** Drag space → screen. Across the barrier the names hold back for the first half of the way, then catch up. */
    fun screen(value: Float): Float {
        if (split == null) return value
        val a = (split - 1).toFloat(); val b = a + 1 + BARRIER
        if (value <= a) return value
        if (value >= b) return value - BARRIER + GAP
        val t = (value - a) / (b - a)
        val eased = if (t < 0.6f) t * 0.45f else 0.27f + (t - 0.6f) / 0.4f * 0.73f
        return a + eased * (1 + GAP)
    }
    fun nearest(value: Float) = options.indices.minByOrNull { abs(dragPosition(it) - value) } ?: 0

    var expanded by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val live = remember { Animatable(dragPosition(index)) }
    LaunchedEffect(index, options.size) { if (!dragging) live.animateTo(dragPosition(index), spring(dampingRatio = 0.82f, stiffness = 300f)) }
    // A tap-opened carousel closes itself after a moment of no interaction.
    LaunchedEffect(expanded, dragging) { if (expanded && !dragging) { delay(2500); expanded = false } }
    // Crossing into pictures (or back) lands with more weight than a neighbour.
    var wasPainted by remember { mutableStateOf(painted) }
    LaunchedEffect(painted) { if (painted != wasPainted) haptics.performHapticFeedback(HapticFeedbackType.LongPress); wasPainted = painted }

    val width by animateDpAsState(if (expanded) 300.dp else collapsed, spring(dampingRatio = 0.82f, stiffness = 300f), label = "width")
    val paint by animateFloatAsState(if (painted) 1f else 0f, androidx.compose.animation.core.tween(500), label = "paint")
    val currentIndex by rememberUpdatedState(index)
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOptions by rememberUpdatedState(options)

    fun rubberBand(value: Float): Float {
        val upper = dragPosition(options.size - 1)
        return when {
            value < 0 -> -(1 - 1 / (1 - value * 0.6f))
            value > upper -> upper + (1 - 1 / (1 + (value - upper) * 0.6f))
            else -> value
        }
    }

    val shown = screen(live.value)
    val wet = Modifier.liquidPaint(paint)
    val fill = ink.fill
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width).height(38.dp)
            .graphicsLayer { val s = if (dragging) 1.02f else 1f; scaleX = s; scaleY = s }
            .shadow(
                if (painted || expanded) 12.dp else 0.dp, CircleShape,
                ambientColor = if (painted) Color(1f, 0.3f, 0.5f, 0.25f) else Color.Black.copy(alpha = 0.08f),
                spotColor = if (painted) Color(1f, 0.3f, 0.5f, 0.25f) else Color.Black.copy(alpha = 0.08f),
            )
            .onGloballyPositioned { placed(it.boundsInWindow()) }
            // The plain capsule, faded out under the wet paint while a picture model is picked.
            .drawBehind { if (paint < 1f) drawRoundRect(fill.copy(alpha = fill.alpha * (1 - paint)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)) }
            .then(if (paint > 0f) wet else Modifier)
            .border(1.dp, if (expanded && !painted) ink.hairline else Color.Transparent, CircleShape)
            .alpha(if (disabled) 0.5f else 1f)
            .semantics { contentDescription = "Model"; stateDescription = options[index].name + if (painted) ", paints pictures" else "" }
            .pointerInput(disabled, options.size, split) {
                if (disabled) return@pointerInput
                val slotPx = slot.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val openedByTap = !currentExpanded
                    val startIndex = currentIndex
                    val start = dragPosition(startIndex)
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
                            val value = rubberBand(start - dx / slotPx)
                            scope.launch { live.snapTo(value) }
                            val near = nearest(value)
                            if (near != currentIndex) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                select(currentOptions[near].id)
                            }
                            change.consume()
                        }
                        if (!change.pressed) break
                    }
                    dragging = false
                    val target: Int? = when {
                        moved -> {
                            val t = nearest(start - (lastX - down.position.x + tracker.calculateVelocity().x * 0.25f) / slotPx)
                            // A fling never jumps the barrier on momentum alone: it stops at the edge.
                            val held = currentIndex
                            if (split != null && (held < split) != (t < split)) held else t
                        }
                        openedByTap -> null // a plain tap just opens the carousel
                        else -> {
                            // Tap on a neighbour while open picks it.
                            val spot = screen(live.value) + (lastX - size.width / 2f) / slotPx
                            options.indices.minByOrNull { abs(screenPosition(it) - spot) } ?: startIndex
                        }
                    }
                    if (target != null) {
                        select(currentOptions[target].id)
                        scope.launch { live.animateTo(dragPosition(target), spring(dampingRatio = 0.82f, stiffness = 300f)) }
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
        // The brush between chat and picture models.
        split?.let { gap ->
            val at = ((gap - 1) + screenPosition(gap)) / 2f
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Brush, null,
                tint = if (painted) Color.White else Color(0xFFFF3D80),
                modifier = Modifier.size(13.dp).graphicsLayer {
                    translationX = (at - shown) * slot.toPx()
                    rotationZ = -35f
                    alpha = if (expanded) 1f else 0f
                },
            )
        }
        options.forEachIndexed { i, option ->
            val distance = abs(screenPosition(i) - shown)
            val centred = distance < 0.5f
            val alpha by animateFloatAsState(if (expanded) maxOf(0f, 1 - distance * 0.42f) else if (centred) 1f else 0f, label = "alpha$i")
            Text(
                option.name,
                maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = geist(15, if (centred) FontWeight.Medium else FontWeight.Normal,
                    if (painted) Color.White.copy(alpha = if (centred) 1f else 0.72f) else if (centred) (if (dimmed) ink.secondary else ink.primary) else ink.tertiary),
                modifier = Modifier
                    // Open, each name keeps to its slot so long shared names don't overlap.
                    .then(if (expanded) Modifier.widthIn(max = slot - 10.dp) else Modifier)
                    .graphicsLayer {
                        translationX = (screenPosition(i) - shown) * slot.toPx()
                        val sc = 1 - minOf(distance, 2f) * 0.07f
                        scaleX = sc; scaleY = sc
                        this.alpha = alpha
                    },
            )
        }
    }
}

/** Extra finger travel, in slots, to cross into picture models (or back). */
private const val BARRIER = 0.8f
/** Extra room on screen, in slots, between the last chat model and the first picture one. */
private const val GAP = 0.55f
