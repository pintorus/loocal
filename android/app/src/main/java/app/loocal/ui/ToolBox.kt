package app.loocal.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.loocal.tools.Connector
import app.loocal.tools.ToolUse
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink

/**
 * One step a reply took before answering (port of iOS `ToolBox`): a source it read, a tool it
 * called, or an action it took. Running, its title shimmers; done, it can open to show its links.
 */
@Composable
fun ToolBox(use: ToolUse, undo: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(ink.fill).animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.pressable(0.98f, enabled = use.links.isNotEmpty()) { open = !open }.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).background(ink.paper, CircleShape)) {
                when (use.status) {
                    ToolUse.Status.RUNNING -> SpiralLoader(14.dp)
                    ToolUse.Status.FAILED -> Icon(Icons.Filled.PriorityHigh, null, tint = ink.primary, modifier = Modifier.size(14.dp))
                    ToolUse.Status.UNDONE -> Icon(Icons.AutoMirrored.Filled.Undo, null, tint = ink.primary, modifier = Modifier.size(14.dp))
                    ToolUse.Status.DONE -> Icon(symbolIcon(use.icon), null, tint = ink.primary, modifier = Modifier.size(14.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = if (open) Modifier.weight(1f) else Modifier) {
                Text(
                    use.title, style = geist(14, FontWeight.Medium, if (use.status == ToolUse.Status.RUNNING) ink.secondary else ink.primary),
                    modifier = Modifier.shimmer(use.status == ToolUse.Status.RUNNING),
                )
                val summary = summary(use)
                if (summary.isNotEmpty()) Text(summary, style = geist(12, color = ink.secondary), maxLines = 1)
            }
            if (use.action?.undoable == true && use.status == ToolUse.Status.DONE) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(start = 6.dp).height(26.dp).background(ink.paper, CircleShape).pressable(0.9f) { undo(use.id) }.padding(horizontal = 10.dp),
                ) { Text("Undo", style = geist(12, FontWeight.Medium, ink.primary)) }
            }
            if (use.links.isNotEmpty()) {
                val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = ink.tertiary, modifier = Modifier.size(16.dp).rotate(turn))
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(bottom = 6.dp)) {
                use.links.forEachIndexed { i, link ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.pressable(0.98f) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } }.padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text("${i + 1}", style = geist(11, FontWeight.Medium, ink.tertiary), modifier = Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(link.title, style = geist(13, color = ink.primary), maxLines = 1)
                            Text(link.host, style = geist(11, color = ink.secondary), maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.NorthEast, null, tint = ink.tertiary, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

/** The query, or the sources it found, in one quiet line. */
private fun summary(use: ToolUse): String {
    if (use.status == ToolUse.Status.DONE && use.links.isNotEmpty()) {
        val hosts = use.links.map { it.host }.distinct()
        return (if (use.detail.isEmpty()) "" else "“${use.detail}” · ") + "${use.links.size} result${if (use.links.size == 1) "" else "s"} · " + hosts.take(2).joinToString(", ")
    }
    return if (use.detail.isEmpty()) "" else if (use.kind == Connector.WEB.id) "“${use.detail}”" else use.detail
}
