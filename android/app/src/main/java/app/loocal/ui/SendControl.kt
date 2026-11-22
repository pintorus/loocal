package app.loocal.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.loocal.domain.Persona
import app.loocal.ui.theme.ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private sealed interface Item {
    data object None : Item
    data class Choice(val persona: Persona) : Item
    data object Add : Item
}

/**
 * Send button that doubles as the persona switcher (port of App/SendControl.swift).
 * Tap: send. Press and hold, or slide left: a capsule unrolls leftward with the persona icons.
 * Slide along it and an ink circle glides under the icon you're on; release to pick it.
 * Releasing on the button itself leaves the capsule open for a tap; it folds away after 3 s.
 */
@Composable
fun SendControl(
    personas: List<Persona>,
    activeId: String?,
    canSend: Boolean,
    open: Boolean,
    setOpen: (Boolean) -> Unit,
    send: () -> Unit,
    choose: (String?) -> Unit,
    addPersona: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val items: List<Item> = listOf(Item.None) + personas.map { Item.Choice(it) } + if (personas.size < Persona.LIMIT) listOf(Item.Add) else emptyList()
    val size = 38.dp
    val slot = minOf(42.dp, 246.dp / items.size.coerceAtLeast(1))
    fun dx(i: Int) = -slot * (i + 1) - 6.dp
    val trayWidth = if (open) slot * items.size + size + 18.dp else size

    var pressing by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    val activeIndex = items.indexOfFirst { it is Item.Choice && it.persona.id == activeId }.takeIf { it >= 0 } ?: 0
    val marker = highlighted ?: if (pressing) null else activeIndex

    val currentItems by rememberUpdatedState(items)
    val currentOpen by rememberUpdatedState(open)
    val currentCanSend by rememberUpdatedState(canSend)

    fun pick(item: Item) {
        when (item) {
            Item.None -> choose(null)
            is Item.Choice -> choose(item.persona.id)
            Item.Add -> addPersona()
        }
        if (item != Item.Add) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        highlighted = null
        setOpen(false)
    }

    // Left open by a plain hold, the capsule folds away if ignored.
    LaunchedEffect(open, pressing) {
        if (open && !pressing) { delay(3000); if (!pressing) setOpen(false) }
    }

    val spring = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.8f, stiffness = 420f)
    val width by animateDpAsState(trayWidth, spring, label = "tray")
    val trayAlpha by animateFloatAsState(if (open) 1f else 0f, label = "trayAlpha")

    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier
            .size(width = if (open) trayWidth - 4.dp else size, height = size)
            .semantics { contentDescription = "Send message" }
            .pointerInput(items.size, canSend) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressing = true
                    var openedThisPress = false
                    var hold: Job? = null
                    // The button's centre, from the right edge of this (possibly widened) box.
                    fun centerX() = this.size.width - with(density) { size.toPx() } / 2
                    fun hit(x: Float, y: Float): Int? {
                        if (abs(y - this.size.height / 2f) > with(density) { 48.dp.toPx() }) return null
                        val offset = x - centerX()
                        val list = currentItems
                        val index = list.indices.minByOrNull { abs(with(density) { dx(it).toPx() } - offset) } ?: return null
                        val tolerance = with(density) { (slot / 2 + if (index == list.lastIndex) 14.dp else 0.dp).toPx() }
                        return if (abs(with(density) { dx(index).toPx() } - offset) < tolerance) index else null
                    }
                    fun reveal() {
                        openedThisPress = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        setOpen(true)
                    }
                    if (!currentOpen) hold = scope.launch { delay(180); if (pressing && !currentOpen) reveal() }
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!currentOpen && change.position.x - down.position.x < -10.dp.toPx()) { hold?.cancel(); reveal() }
                        if (currentOpen) {
                            val h = hit(change.position.x, change.position.y)
                            if (h != highlighted && h != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            highlighted = h
                        }
                        if (!change.pressed) break
                    }
                    hold?.cancel()
                    pressing = false
                    if (currentOpen) {
                        val index = hit(last.x, last.y)
                        if (index != null) pick(currentItems[index])
                        else if (!openedThisPress) { highlighted = null; setOpen(false) }
                        highlighted = null
                    } else if (currentCanSend) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        send()
                    }
                }
            },
    ) {
        // The tray: a paper capsule growing leftward out of the button.
        Box(
            Modifier
                .offset(x = 2.dp) // requiredSize centres the wider capsule; this puts its right edge 4 dp past the button
                .requiredSize(width = width, height = if (open) 46.dp else size)
                .alpha(trayAlpha)
                .shadow(if (open) 14.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.1f))
                .background(ink.paper, CircleShape)
                .border(1.dp, ink.hairline, CircleShape),
        )
        val itemSize = minOf(36.dp, slot - 4.dp)
        if (marker != null) {
            val markerX by animateDpAsState(if (open) dx(marker) else 0.dp, spring(dampingRatio = 0.72f, stiffness = 450f), label = "marker")
            Box(Modifier.offset(x = markerX - (size - itemSize) / 2).size(itemSize).alpha(trayAlpha).background(ink.primary, CircleShape).align(Alignment.CenterEnd))
        }
        items.forEachIndexed { i, item ->
            val lit = marker == i && open
            val x by animateDpAsState(if (open) dx(i) else 0.dp, spring(dampingRatio = 0.8f, stiffness = 420f), label = "item$i")
            val s by animateFloatAsState(if (open) (if (highlighted == i) 1.08f else 1f) else 0.4f, label = "itemScale$i")
            val color = if (lit) ink.paper else if (item == Item.Add) ink.secondary else ink.primary
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = x - (size - itemSize) / 2).size(itemSize).scale(s).alpha(trayAlpha),
            ) {
                when (item) {
                    Item.None -> LoopMark(Modifier.width(18.dp), color = color)
                    is Item.Choice -> Icon(personaIcon(item.persona.icon), null, tint = color, modifier = Modifier.size(18.dp))
                    Item.Add -> Icon(Icons.Filled.Add, null, tint = color, modifier = Modifier.size(18.dp))
                }
            }
        }
        // The button itself.
        val scale by animateFloatAsState(if (pressing && !open) 0.9f else if (canSend || open) 1f else 0.92f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "send")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).scale(scale)
                .background(if (open) ink.fill else if (canSend) ink.primary else ink.hairline, CircleShape),
        ) {
            Icon(
                if (open) Icons.Filled.Close else Icons.Filled.ArrowUpward, null,
                tint = if (open) ink.secondary else if (canSend) ink.paper else ink.tertiary,
                modifier = Modifier.size(if (open) 15.dp else 19.dp),
            )
        }
    }
}

