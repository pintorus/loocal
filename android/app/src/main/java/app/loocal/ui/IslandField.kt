package app.loocal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import app.loocal.R
import app.loocal.domain.Island
import app.loocal.domain.IslandText
import app.loocal.ui.theme.ink

/**
 * Draws an island's capsule over its marker in the text, without making the line taller: the
 * capsule overhangs a little, so a line holding one is as tall as any other (iOS `IslandType`).
 */
private class IslandSpan(private val bitmap: android.graphics.Bitmap) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = bitmap.width

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        // The capsule (not its shadow) centred on the line's text.
        val fm = paint.fontMetrics
        val centre = y + (fm.ascent + fm.descent) / 2
        val density = bitmap.density.takeIf { it > 0 }?.let { it / 160f } ?: 1f
        val shadowShift = (IslandArt.MARGIN[3] - IslandArt.MARGIN[1]) / 2 * density
        canvas.drawBitmap(bitmap, x, centre - bitmap.height / 2f + shadowShift, null)
    }
}

private val markerPattern = Regex("⟦([^⟧⟦]*)⟧")

/**
 * Backspace into an island takes all of it: whatever is left of a marker (a lone `⟦` with part of
 * its id, or part of an id with its `⟧`) goes too.
 */
internal fun brokenMarkerRanges(text: String): List<IntRange> {
    val whole = markerPattern.findAll(text).map { it.range }.toList()
    fun inWhole(i: Int) = whole.any { i in it }
    val out = mutableListOf<IntRange>()
    for ((i, c) in text.withIndex()) {
        if (inWhole(i)) continue
        if (c == '⟦') {
            var j = i + 1
            while (j < text.length && j - i <= 8 && (text[j].isLetterOrDigit() || text[j] == '-') && !inWhole(j)) j++
            out.add(i until j)
        } else if (c == '⟧') {
            var j = i - 1
            while (j >= 0 && i - j <= 8 && (text[j].isLetterOrDigit() || text[j] == '-') && !inWhole(j)) j--
            out.add(j + 1..i)
        }
    }
    return out
}

private fun dropBrokenMarkers(e: Editable) {
    brokenMarkerRanges(e.toString()).asReversed().forEach { e.delete(it.first, it.last + 1) }
}

/** Lays the islands' capsules over their markers in an editable text. */
private fun decorate(context: Context, text: Editable, islands: List<Island>, density: Float) {
    text.getSpans(0, text.length, IslandSpan::class.java).forEach(text::removeSpan)
    val byId = islands.associateBy { it.id }
    for (m in markerPattern.findAll(text)) {
        val island = byId[m.groupValues[1]] ?: continue
        val bitmap = IslandArt.bitmap(context, island, density).also { it.density = (density * 160).toInt() }
        text.setSpan(IslandSpan(bitmap), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/**
 * The prompt box (port of iOS `IslandField`): a growing text field whose islands sit inline. An
 * island is one piece: backspace takes the whole of it, and the cursor never stops inside it.
 */
@SuppressLint("SetTextI18n")
@Composable
fun IslandField(
    text: String, islands: List<Island>, placeholder: String, enabled: Boolean,
    toInsert: Island?, inserted: () -> Unit, onChange: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val primary = ink.primary.toArgb()
    val tertiary = ink.tertiary.toArgb()
    val holder = remember { arrayOfNulls<EditText>(1) }
    val current = remember { arrayOf(islands) }
    current[0] = islands
    val latestChange = remember { arrayOf(onChange) }
    latestChange[0] = onChange

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            EditText(ctx).apply {
                holder[0] = this
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.TOP or Gravity.START
                typeface = ResourcesCompat.getFont(ctx, R.font.geist)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                // 22 sp lines, opened a touch once two islands could sit on neighbouring lines.
                setLineSpacing(0f, 1f)
                maxLines = 6
                minLines = 1
                inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(text)
                decorate(ctx, editableText, current[0], density)
                var busy = false
                var cut: IntRange? = null
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                        if (busy || count == 0 || after != 0) { cut = null; return }
                        // Deleting part of a marker deletes all of it.
                        val spanned = s as? Spanned ?: return
                        val hits = spanned.getSpans(start, start + count, IslandSpan::class.java)
                        cut = if (hits.isEmpty()) null else {
                            val from = minOf(start, hits.minOf { spanned.getSpanStart(it) })
                            val to = maxOf(start + count, hits.maxOf { spanned.getSpanEnd(it) })
                            from until to
                        }
                    }
                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(e: Editable) {
                        if (busy) return
                        busy = true
                        if (cut != null) dropBrokenMarkers(e)
                        cut = null
                        decorate(context, e, current[0], density)
                        busy = false
                        latestChange[0](e.toString())
                    }
                })
            }
        },
        update = { view ->
            view.isEnabled = enabled
            view.setTextColor(primary)
            view.setHintTextColor(tertiary)
            view.hint = placeholder
            if (view.text.toString() != text) {
                view.setText(text)
                view.setSelection(view.text.length)
                // Sent: the keyboard goes down, as with the old field.
                if (text.isEmpty()) {
                    view.clearFocus()
                    view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
            decorate(context, view.editableText, islands, density)
        },
    )

    // An island drops in at the cursor, spaced from the words around it.
    LaunchedEffect(toInsert) {
        val island = toInsert ?: return@LaunchedEffect
        val view = holder[0] ?: return@LaunchedEffect
        val e = view.editableText
        var at = view.selectionEnd.takeIf { it >= 0 } ?: e.length
        // Never inside a marker.
        markerPattern.findAll(e).firstOrNull { at > it.range.first && at <= it.range.last }?.let { at = it.range.last + 1 }
        val before = if (at > 0 && e[at - 1] != ' ' && e[at - 1] != '\n') " " else ""
        val after = if (at < e.length && e[at] == ' ') "" else " "
        val piece = before + island.marker + after
        e.insert(at, piece)
        view.setSelection(at + piece.length)
        // Typing goes on right after the island, keyboard up.
        view.requestFocus()
        view.post { view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(view, 0) }
        inserted()
    }
}

/** A sent prompt with its islands, as it was typed. */
@Composable
fun IslandText(text: String, islands: List<Island>, style: TextStyle, modifier: Modifier = Modifier) {
    if (islands.isEmpty() || !text.contains(Island.OPEN)) { Text(text, style = style, modifier = modifier); return }
    val context = LocalContext.current
    val density = LocalDensity.current
    val pieces = IslandText.pieces(text, islands)
    val inline = HashMap<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        pieces.forEach { piece ->
            when (piece) {
                is IslandText.Piece.Text -> append(piece.text)
                is IslandText.Piece.Isle -> {
                    val island = piece.island
                    val bitmap = IslandArt.bitmap(context, island, density.density)
                    val w = with(density) { bitmap.width.toFloat().toDp().toSp() }
                    val h = with(density) { bitmap.height.toFloat().toDp().toSp() }
                    inline[island.id] = InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                        Image(bitmap.asImageBitmap(), island.title, modifier = Modifier.fillMaxSize())
                    }
                    appendInlineContent(island.id, island.title)
                }
            }
        }
    }
    Text(annotated, style = style, inlineContent = inline, modifier = modifier)
}
