package app.loocal.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.loocal.tools.Connector
import app.loocal.tools.ToolUse
import app.loocal.tools.ToolWidget
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * What a reply did on the way, as one line of grey text: "Searched the web · 3 results" (port of
 * iOS `ToolTrail`). Several steps fold into "Used 3 tools". Tap to open the steps.
 */
@Composable
fun ToolTrail(tools: List<ToolUse>, undo: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val running = tools.lastOrNull { it.status == ToolUse.Status.RUNNING }
    val single = tools.singleOrNull()
    val opens = single?.let { it.links.isNotEmpty() || stepSummary(it).isNotEmpty() } ?: true
    val title = when {
        running != null -> running.title
        single != null -> single.links.size.let { n -> if (n > 0 && single.status == ToolUse.Status.DONE) "${single.title} · $n result${if (n == 1) "" else "s"}" else single.title }
        else -> tools.count { it.status == ToolUse.Status.FAILED }.let { failed -> "Used ${tools.size} tools" + if (failed > 0) " · $failed didn’t work" else "" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.pressable(0.98f, enabled = opens) { open = !open },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.width(14.dp)) {
                    if (running != null) SpiralLoader(12.dp, ink.secondary)
                    else Icon(single?.let { stepIcon(it) } ?: Icons.Outlined.Layers, null, tint = ink.secondary, modifier = Modifier.size(13.dp))
                }
                Text(title, style = geist(14, color = ink.secondary), maxLines = 1, modifier = Modifier.shimmer(running != null))
                if (opens) {
                    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
                    Icon(Icons.Filled.ChevronRight, null, tint = ink.tertiary, modifier = Modifier.size(12.dp).rotate(turn))
                }
            }
            single?.let { UndoLink(it, undo) }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = 12.dp).drawLeftRule(),
                ) {
                    if (single != null) StepDetails(single)
                    else tools.forEach { use ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.width(14.dp)) {
                                    if (use.status == ToolUse.Status.RUNNING) SpiralLoader(11.dp, ink.secondary)
                                    else Icon(stepIcon(use), null, tint = ink.secondary, modifier = Modifier.size(12.dp))
                                }
                                Text(use.title, style = geist(13.5f, color = ink.secondary), modifier = Modifier.shimmer(use.status == ToolUse.Status.RUNNING))
                                UndoLink(use, undo)
                            }
                            Box(Modifier.padding(start = 21.dp)) { StepDetails(use) }
                        }
                    }
                }
            }
        }
    }
}

/** The thin rule down the left of opened steps. */
@Composable
private fun Modifier.drawLeftRule(): Modifier {
    val rule = ink.hairline
    return this.drawBehind { drawRule(rule) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRule(rule: androidx.compose.ui.graphics.Color) {
    drawLine(rule, Offset(-6.dp.toPx(), 0f), Offset(-6.dp.toPx(), size.height), 1.dp.toPx())
}

private fun stepIcon(use: ToolUse) = when (use.status) {
    ToolUse.Status.FAILED -> Icons.Filled.ErrorOutline
    ToolUse.Status.UNDONE -> Icons.AutoMirrored.Filled.Undo
    else -> symbolIcon(use.icon)
}

private fun stepSummary(use: ToolUse) = if (use.detail.isEmpty()) "" else if (use.kind == Connector.WEB.id) "“${use.detail}”" else use.detail

/** The query or note, then the sources it found, each one a link. */
@Composable
private fun StepDetails(use: ToolUse) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        stepSummary(use).takeIf { it.isNotEmpty() }?.let { Text(it, style = geist(12.5f, color = ink.tertiary), maxLines = 3) }
        use.links.forEach { link ->
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.pressable(0.98f) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } },
            ) {
                Text(link.title, style = geist(13, color = ink.secondary), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Text(link.host, style = geist(12, color = ink.tertiary), maxLines = 1)
                Icon(Icons.Filled.NorthEast, null, tint = ink.tertiary, modifier = Modifier.size(9.dp))
            }
        }
    }
}

@Composable
private fun UndoLink(use: ToolUse, undo: (String) -> Unit) {
    if (use.action?.undoable == true && use.status == ToolUse.Status.DONE) {
        Text(
            "Undo", style = geist(13, FontWeight.Medium, ink.primary).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
            modifier = Modifier.pressable(0.9f) { undo(use.id) },
        )
    }
}

// MARK: - Widgets in the reply

/** What a tool found, drawn into the reply: the one place colour comes onto the page. */
@Composable
fun ToolWidgetView(widget: ToolWidget, speak: (String) -> Unit) {
    when (widget) {
        is ToolWidget.Map -> MapWidget(widget)
        is ToolWidget.Calculation -> CalculationWidget(widget)
        is ToolWidget.Translation -> TranslationWidget(widget, speak)
    }
}

@Composable
private fun WidgetLabel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = ink.secondary, modifier = Modifier.size(12.dp))
        Text(title, style = geist(13, FontWeight.Medium, ink.secondary), maxLines = 1)
    }
}

@Composable
private fun Modifier.widgetBox() = fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(ink.fill)

@Composable
private fun CalculationWidget(w: ToolWidget.Calculation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widgetBox().padding(horizontal = 18.dp, vertical = 16.dp)) {
        WidgetLabel(w.expression.ifEmpty { "Calculator" }, Icons.Filled.Functions)
        SelectionContainer { Text(w.result, style = serif(if (w.result.length > 18) 28 else 44, color = ink.primary), maxLines = 2) }
    }
}

@Composable
private fun TranslationWidget(w: ToolWidget.Translation, speak: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widgetBox().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WidgetLabel(w.language, Icons.Outlined.Translate)
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).pressable(0.88f) { speak(w.text) }) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Read the translation aloud", tint = ink.primary, modifier = Modifier.size(16.dp))
            }
        }
        SelectionContainer { Text(w.text, style = serif(28, color = ink.primary)) }
    }
}

/** Places on a small still map (OpenStreetMap tiles), numbered, with you as a dot; the first three listed under it. */
@Composable
private fun MapWidget(w: ToolWidget.Map) {
    val context = LocalContext.current
    val pins = w.pins.take(5)
    Column(Modifier.widgetBox()) {
        Box(Modifier.padding(6.dp).fillMaxWidth().height(164.dp).clip(RoundedCornerShape(16.dp)).background(ink.paper)) {
            StillMap(pins, w.here)
        }
        Column(Modifier.padding(top = 2.dp, bottom = 6.dp)) {
            pins.take(3).forEachIndexed { i, pin ->
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().pressable(0.98f) { pin.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("${i + 1}", style = geist(13, FontWeight.Medium, ink.tertiary), modifier = Modifier.width(12.dp))
                    Text(pin.name, style = geist(15, color = ink.primary), maxLines = 1, modifier = Modifier.weight(1f))
                    Text(pin.detail, style = geist(13, color = ink.secondary), maxLines = 1)
                }
            }
        }
    }
}

private object Tiles {
    private val cache = HashMap<String, Bitmap>()
    suspend fun get(z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$z/$x/$y"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        runCatching {
            val c = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Loocal-Android/1.0")
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { synchronized(cache) { cache[key] = it } }
    }
}

private fun worldX(lon: Double, z: Int) = (lon + 180) / 360 * 256 * (1 shl z)
private fun worldY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat)
    return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 256 * (1 shl z)
}

@Composable
private fun StillMap(pins: List<ToolWidget.Pin>, here: ToolWidget.Pin?) {
    val points = pins + listOfNotNull(here)
    if (points.isEmpty()) return
    val primary = ink.primary
    val paper = ink.paper
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(164.dp)) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        // Tiles are 256 px at zoom z; draw them at `scale` so they read at phone density.
        val scale = max(1f, density / 1.5f)
        val z = (17 downTo 3).firstOrNull { z ->
            val xs = points.map { worldX(it.longitude, z) }; val ys = points.map { worldY(it.latitude, z) }
            (xs.max() - xs.min()) * scale < wPx * 0.78 && (ys.max() - ys.min()) * scale < hPx * 0.7
        } ?: 3
        val cx = points.map { worldX(it.longitude, z) }.let { (it.max() + it.min()) / 2 }
        val cy = points.map { worldY(it.latitude, z) }.let { (it.max() + it.min()) / 2 }
        val left = cx - wPx / 2 / scale; val top = cy - hPx / 2 / scale
        val tiles by produceState(initialValue = emptyList<Triple<Int, Int, Bitmap>>(), z, cx, cy) {
            val out = mutableListOf<Triple<Int, Int, Bitmap>>()
            for (tx in floor(left / 256).toInt()..floor((left + wPx / scale) / 256).toInt())
                for (ty in floor(top / 256).toInt()..floor((top + hPx / scale) / 256).toInt()) {
                    val n = 1 shl z
                    if (ty !in 0 until n) continue
                    Tiles.get(z, ((tx % n) + n) % n, ty)?.let { out.add(Triple(tx, ty, it)); value = out.toList() }
                }
        }
        Canvas(Modifier.fillMaxWidth().height(164.dp)) {
            tiles.forEach { (tx, ty, bitmap) ->
                val x = ((tx * 256 - left) * scale).toInt(); val y = ((ty * 256 - top) * scale).toInt()
                drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(x, y), dstSize = IntSize((256 * scale).toInt() + 1, (256 * scale).toInt() + 1))
            }
            fun at(p: ToolWidget.Pin) = Offset(((worldX(p.longitude, z) - left) * scale).toFloat(), ((worldY(p.latitude, z) - top) * scale).toFloat())
            here?.let { h -> drawCircle(paper, 8.dp.toPx(), at(h)); drawCircle(primary, 5.dp.toPx(), at(h)) }
            val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = paper.toArgb(); textSize = 12.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true
            }
            pins.forEachIndexed { i, p ->
                // Numbered disc standing on its place.
                val o = at(p) - Offset(0f, 12.dp.toPx())
                drawCircle(primary, 12.dp.toPx(), o)
                drawIntoCanvas { it.nativeCanvas.drawText("${i + 1}", o.x, o.y + 4.dp.toPx(), label) }
            }
        }
    }
}
