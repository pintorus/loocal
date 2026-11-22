package app.loocal.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay

/** Makes a partially streamed reply safe to lay out (port of iOS `StreamingText.stable`). */
object StreamingText {
    /**
     * While generating: show only whole words, and temporarily close an open `**bold**` or
     * `code` span so its markers never flash as raw text. Finished text is unchanged.
     */
    fun stable(text: String, live: Boolean): String {
        if (!live) return text
        val cut = text.indexOfLast { it.isWhitespace() }
        if (cut < 0) return ""
        var body = text.substring(0, cut + 1)
        val trailing = body.takeLastWhile { it.isWhitespace() }
        body = body.dropLast(trailing.length)
        if (body.split("**").size % 2 == 0) body += "**"
        if (body.count { it == '`' } % 2 == 1) body += "`"
        return body + trailing
    }
}

/** Light inline Markdown (bold, italics, code), as on iOS; anything else stays plain. */
fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text[i] == '`' && text.indexOf('`', i + 1) > i + 1 -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            (text[i] == '*' || text[i] == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                text.indexOf(text[i], i + 1).let { it > i + 1 && !text[it - 1].isWhitespace() } -> {
                val end = text.indexOf(text[i], i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** Plain text of a Markdown reply, for Copy, Share and History previews. */
fun plainText(text: String) = renderMarkdown(text).text

/**
 * Writes each new letter as it arrives, one after another (port of iOS `HandwrittenText`).
 * Each glyph fades in over [STROKE_MS]; a burst of streamed tokens is still written in order.
 */
@Composable
fun HandwrittenText(text: AnnotatedString, live: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    // Arrival time (ms) of each character; earlier characters count as already written.
    val starts = remember { mutableListOf<Long>() }
    var settled by remember { mutableStateOf(true) }
    var known by remember { mutableStateOf(if (live) 0 else text.length) }

    if (text.length < known) { while (starts.size > text.length) starts.removeAt(starts.lastIndex); known = text.length }
    if (text.length > known) {
        val now = System.currentTimeMillis()
        while (starts.size < known) starts.add(0L)
        var next = maxOf(now, (starts.lastOrNull() ?: 0L) + GAP_MS)
        for (k in known until text.length) { starts.add(next); next += GAP_MS }
        known = text.length
        settled = false
    }

    val clock = rememberClock(paused = settled)
    LaunchedEffect(starts.lastOrNull(), settled) {
        val last = starts.lastOrNull() ?: return@LaunchedEffect
        val remaining = last + STROKE_MS + 50 - System.currentTimeMillis()
        if (remaining > 0) delay(remaining)
        settled = true
    }

    if (settled) {
        BasicText(text, modifier, style = style)
        return
    }
    @Suppress("UNUSED_EXPRESSION") clock // recompose every frame while letters are being written
    val now = System.currentTimeMillis()
    val base = style.color
    val shown = buildAnnotatedString {
        append(text)
        for (k in starts.indices) {
            val p = ((now - starts[k]).toFloat() / STROKE_MS).coerceIn(0f, 1f)
            if (p < 1f) addStyle(SpanStyle(color = base.copy(alpha = base.alpha * (1 - (1 - p) * (1 - p) * (1 - p)))), k, k + 1)
        }
    }
    BasicText(shown, modifier, style = style)
}

private const val STROKE_MS = 320L
private const val GAP_MS = 16L

