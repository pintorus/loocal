package app.loocal.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One icon in the + tray. [on] shows it filled (an active connector); [enabled] false dims it. */
data class TrayItem(val id: String, val icon: ImageVector, val label: String, val on: Boolean = false, val enabled: Boolean = true)

/**
 * The + button as a tray (port of App/AttachMenu.swift `AttachControl`): one capsule that unrolls
 * rightward out of the button with icons in a row, an ink circle gliding under the icon you're on.
 * Tap + to open it and tap an icon, or press, slide and release.
 */
@Composable
fun AttachControl(open: Boolean, setOpen: (Boolean) -> Unit, items: List<TrayItem>, choose: (TrayItem) -> Unit, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val size = 38.dp
    val slot = minOf(42.dp, 246.dp / items.size.coerceAtLeast(1))
    fun dx(i: Int) = slot * (i + 1) + 6.dp
    val trayWidth = if (open) slot * items.size + size + 18.dp else size

    var pressing by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    val currentItems by rememberUpdatedState(items)
    val currentOpen by rememberUpdatedState(open)

    val spring = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.8f, stiffness = 420f)
    val width by animateDpAsState(trayWidth, spring, label = "tray")
    val trayAlpha by animateFloatAsState(if (open) 1f else 0f, label = "trayAlpha")
    val turn by animateFloatAsState(if (open) 45f else 0f, label = "plus")

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .size(width = if (open) trayWidth - 4.dp else size, height = size)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = "Add photos, files or connectors" }
            .pointerInput(items.size, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressing = true
                    var openedThisPress = false
                    var hold: Job? = null
                    val center = with(density) { size.toPx() } / 2
                    fun hit(x: Float, y: Float): Int? {
                        if (abs(y - this.size.height / 2f) > with(density) { 48.dp.toPx() }) return null
                        val offset = x - center
                        val list = currentItems
                        val index = list.indices.minByOrNull { abs(with(density) { dx(it).toPx() } - offset) } ?: return null
                        val tolerance = with(density) { (slot / 2 + if (index == list.lastIndex) 14.dp else 0.dp).toPx() }
                        return if (list[index].enabled && abs(with(density) { dx(index).toPx() } - offset) < tolerance) index else null
                    }
                    fun reveal() { openedThisPress = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); setOpen(true) }
                    if (!currentOpen) hold = scope.launch { delay(180); if (pressing && !currentOpen) reveal() }
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        // Sliding right opens at once.
                        if (!currentOpen && change.position.x - down.position.x > 10.dp.toPx()) { hold?.cancel(); reveal() }
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
                        if (index != null) choose(currentItems[index])
                        else if (!openedThisPress) setOpen(false) // tapped the x or away from the icons
                        highlighted = null
                    } else {
                        setOpen(true) // plain tap: open and wait for a tap
                    }
                }
            },
    ) {
        Box(
            Modifier
                .offset(x = -2.dp)
                .requiredSize(width = width, height = if (open) 46.dp else size)
                .alpha(trayAlpha)
                .shadow(if (open) 14.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.1f))
                .background(ink.paper, CircleShape)
                .border(1.dp, ink.hairline, CircleShape),
        )
        val itemSize = minOf(36.dp, slot - 4.dp)
        highlighted?.takeIf { open }?.let { h ->
            val markerX by animateDpAsState(dx(h), spring(dampingRatio = 0.72f, stiffness = 450f), label = "marker")
            Box(Modifier.offset(x = markerX + (size - itemSize) / 2).size(itemSize).background(ink.primary, CircleShape))
        }
        items.forEachIndexed { i, item ->
            val lit = (highlighted == i || item.on) && open
            val x by animateDpAsState(if (open) dx(i) else 0.dp, spring, label = "item${item.id}")
            val s by animateFloatAsState(if (open) (if (highlighted == i) 1.08f else 1f) else 0.4f, label = "itemScale${item.id}")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.offset(x = x + (size - itemSize) / 2).size(itemSize).scale(s)
                    .alpha(if (open) (if (item.enabled) 1f else 0.3f) else 0f)
                    .background(if (item.on && highlighted != i) ink.primary else Color.Transparent, CircleShape),
            ) {
                Icon(item.icon, item.label, tint = if (lit) ink.paper else ink.primary, modifier = Modifier.size(18.dp))
            }
        }
        // The + itself, turning into an x while open.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).scale(if (pressing && !open) 0.9f else 1f).background(if (open) ink.fill else Color.Transparent, CircleShape),
        ) {
            Icon(Icons.Filled.Add, null, tint = if (open) ink.secondary else ink.primary, modifier = Modifier.size(22.dp).rotate(turn))
        }
    }
}

/** A chip for an attached file or an active connector (iOS `ChipRow`). */
data class Chip(val id: String, val icon: ImageVector, val title: String)

@Composable
fun ChipRow(chips: List<Chip>, remove: (Chip) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp).padding(top = 4.dp),
    ) {
        chips.forEach { chip ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.height(30.dp).background(ink.paper, CircleShape).border(1.dp, ink.hairline, CircleShape).padding(start = 10.dp, end = 4.dp),
            ) {
                Icon(chip.icon, null, tint = ink.primary, modifier = Modifier.size(14.dp))
                Text(chip.title, style = geist(13, FontWeight.Medium, ink.primary), maxLines = 1)
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp).semantics { contentDescription = "Remove ${chip.title}" }.pressable(0.85f) { remove(chip) }) {
                    Icon(Icons.Filled.Close, null, tint = ink.tertiary, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}
