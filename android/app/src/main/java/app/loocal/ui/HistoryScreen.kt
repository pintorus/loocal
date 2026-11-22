package app.loocal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.loocal.domain.Chat
import app.loocal.domain.Role
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Port of iOS `HistoryView` (RootView.swift). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(state: UiState, open: (Chat) -> Unit, delete: (Chat) -> Unit, togglePin: (Chat) -> Unit) {
    var query by remember { mutableStateOf("") }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val matches = state.chats.filter { chat -> query.isBlank() || chat.messages.any { it.text.contains(query.trim(), ignoreCase = true) } }
    val groups = remember(matches) { group(matches) }

    Box(Modifier.fillMaxSize().background(ink.paper)) {
        if (state.chats.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(bottom = 66.dp).rise(appeared, 80),
            ) {
                LoopMark(Modifier.width(40.dp), color = ink.tertiary)
                Spacer(Modifier.height(10.dp))
                Text("Nothing here yet.", style = serif(28, italic = true, color = ink.primary))
                Spacer(Modifier.height(10.dp))
                Text("Your conversations stay on this phone.", style = geist(14, color = ink.secondary))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)) {
                groups.forEachIndexed { g, (title, chats) ->
                    stickyHeader(key = "h-$title") {
                        Text(
                            title, style = geist(13, FontWeight.Medium, ink.secondary),
                            modifier = Modifier.fillMaxWidth().background(ink.paper).padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(chats, key = { it.id }) { chat ->
                        Column(Modifier.animateItem().rise(appeared, minOf(40 * (g * 2), 300), 8f)) {
                            SwipeRow(chat, selected = chat.id == state.chat.id, enabled = !state.isBusy, open = open, delete = delete, togglePin = togglePin)
                            if (chat != chats.last()) Hairline(Modifier.padding(start = 20.dp))
                        }
                    }
                }
            }
        }

        // Search, same size as the chat's prompt bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().bottomFade()
                .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 8.dp)
                .rise(appeared, 100)
                .height(50.dp).background(ink.fill, CircleShape).padding(horizontal = 18.dp),
        ) {
            Icon(Icons.Outlined.Search, null, tint = ink.tertiary, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search chats", style = geist(16, color = ink.tertiary))
                BasicTextField(query, { query = it }, singleLine = true, textStyle = geist(16, color = ink.primary), cursorBrush = SolidColor(ink.primary), modifier = Modifier.fillMaxWidth())
            }
            AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(Icons.Filled.Cancel, "Clear search", tint = ink.tertiary, modifier = Modifier.size(18.dp).pressable(0.85f) { query = "" })
            }
        }
    }
}

/** Swipe left to delete, right to pin, like the iOS swipe actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(chat: Chat, selected: Boolean, enabled: Boolean, open: (Chat) -> Unit, delete: (Chat) -> Unit, togglePin: (Chat) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.EndToStart -> { delete(chat); true }
            SwipeToDismissBoxValue.StartToEnd -> { togglePin(chat); false }
            SwipeToDismissBoxValue.Settled -> true
        }
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val toDelete = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                contentAlignment = if (toDelete) Alignment.CenterEnd else Alignment.CenterStart,
                modifier = Modifier.fillMaxSize().background(if (toDelete) Color(0xFFFF3B30) else ink.primary).padding(horizontal = 24.dp),
            ) {
                Icon(
                    if (toDelete) Icons.Outlined.Delete else Icons.Outlined.PushPin,
                    if (toDelete) "Delete" else if (chat.isPinned) "Unpin" else "Pin",
                    tint = if (toDelete) Color.White else ink.paper,
                )
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
                .background(if (selected) ink.fill else ink.paper)
                .pressable(0.98f, enabled) { open(chat) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.isPinned) {
                    Icon(Icons.Filled.PushPin, null, tint = ink.tertiary, modifier = Modifier.size(13.dp).rotate(35f))
                    Spacer(Modifier.width(6.dp))
                }
                Text(chat.title, style = geist(16, color = ink.primary), maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(dateLabel(chat.createdAt), style = geist(12, color = ink.tertiary))
            }
            chat.messages.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotEmpty() }?.let {
                Text(plainText(it.text).replace('\n', ' '), style = geist(14, color = ink.secondary), maxLines = 1)
            }
        }
    }
}

private fun group(chats: List<Chat>): List<Pair<String, List<Chat>>> {
    val pinned = chats.filter { it.isPinned }.sortedByDescending { it.createdAt }
    val today = startOfDay(0); val yesterday = startOfDay(-1); val week = startOfDay(-6)
    val buckets = chats.filterNot { it.isPinned }.groupBy {
        when {
            it.createdAt >= today -> 0
            it.createdAt >= yesterday -> 1
            it.createdAt >= week -> 2
            else -> 3
        }
    }
    val names = listOf("Today", "Yesterday", "Previous 7 days", "Earlier")
    val dated = buckets.keys.sorted().map { names[it] to buckets.getValue(it).sortedByDescending { c -> c.createdAt } }
    return (if (pinned.isEmpty()) emptyList() else listOf("Pinned" to pinned)) + dated
}

private fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, offsetDays)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Time for today's chats, otherwise "Nov 22". */
private fun dateLabel(millis: Long): String =
    if (millis >= startOfDay(0)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    else java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(Date(millis))
