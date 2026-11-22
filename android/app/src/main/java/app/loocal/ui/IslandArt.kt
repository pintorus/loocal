package app.loocal.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.loocal.R
import app.loocal.domain.Island
import app.loocal.domain.IslandTint
import java.io.File
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws an island as a bitmap (port of App/IslandArt.swift), so the same capsule sits in the
 * prompt, in sent messages and in the chips above the prompt. Each island is a tiny scene of the
 * app it stands for, painted edge to edge under its name.
 */
object IslandArt {
    const val HEIGHT = 26f
    /** Room around the capsule for its shadow, in points: left, top, right, bottom. */
    val MARGIN = floatArrayOf(2f, 1f, 2f, 4f)
    private val cache = HashMap<String, Bitmap>()
    private var geist: Typeface? = null
    private var serif: Typeface? = null
    /** Where attached photos live, so a photo island can show the picture itself. */
    var photos: File? = null

    private class Style(val ink: Int, val glyphInk: Int? = null, val halo: Pair<Int, Float>? = null, val serif: Boolean = false, val trailing: Float = 0f, val glyph: String? = null, val polaroid: Boolean = false)

    private fun style(tint: IslandTint) = when (tint) {
        IslandTint.GREEN -> Style(0x1C2B20, 0xEA4335, 0xF4F2EA to 3f, glyph = "pin")
        IslandTint.RED -> Style(0xD92D23, trailing = 20f)
        IslandTint.ORANGE -> Style(0x1A1A1A, 0xFF9500, trailing = 22f)
        IslandTint.BLUE -> Style(0xFFFFFF, halo = 0x0B4FB8 to 3f, glyph = "compass")
        IslandTint.PURPLE -> Style(0x5A2FD0, trailing = 12f)
        IslandTint.SLATE -> Style(0x111111, halo = 0xFAFAF7 to 3f, serif = true)
        IslandTint.AMBER -> Style(0x3A2E00, 0xD9A000)
        IslandTint.PINK -> Style(0x2A2724, polaroid = true)
        IslandTint.TEAL -> Style(0xE6FFFC, 0x5BE0D3, 0x0B2E2C to 3f)
        IslandTint.INDIGO -> Style(0xFFFFFF, halo = 0x2F28A8 to 3f)
    }

    private fun rgb(c: Int, alpha: Float = 1f) = ((alpha * 255).toInt() shl 24) or (c and 0xFFFFFF)

    private fun fonts(context: Context) {
        if (geist != null) return
        val base = ResourcesCompat.getFont(context, R.font.geist)
        geist = Typeface.create(base, 600, false)
        serif = ResourcesCompat.getFont(context, R.font.instrument_serif)
    }

    /** The capsule as a bitmap at [density] pixels per point. [faded] draws it at 30 % (a dragged island's old place). */
    fun bitmap(context: Context, island: Island, density: Float, faded: Boolean = false): Bitmap {
        val key = "${island.icon}|${island.title}|${island.tint}|${if (island.kind == Island.Kind.PHOTO) island.ref else ""}|$faded|$density"
        cache[key]?.let { return it }
        fonts(context)
        val look = style(island.tint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (look.serif) serif else geist
            textSize = if (look.serif) 16f else 13f
            color = rgb(look.ink)
            letterSpacing = if (look.serif) 0f else -0.15f / 13f
            look.halo?.let { (c, r) -> setShadowLayer(r, 0f, 0f, rgb(c, 0.9f)) }
        }
        val title = if (island.title.length > 24) island.title.take(23) + "…" else island.title
        val markPaint = Paint(textPaint).apply { textSize = 17f }
        val print = 15f to 17.5f
        val glyphW = when { look.polaroid -> print.first + 2; look.serif -> markPaint.measureText("W"); else -> 11f }
        val textW = ceil(textPaint.measureText(title))
        val w = ceil(10 + glyphW + 5 + textW + 11 + look.trailing)
        val h = HEIGHT
        val bitmap = Bitmap.createBitmap(((w + MARGIN[0] + MARGIN[2]) * density).toInt().coerceAtLeast(1), ((h + MARGIN[1] + MARGIN[3]) * density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.scale(density, density)
        if (faded) c.saveLayerAlpha(0f, 0f, w + MARGIN[0] + MARGIN[2], h + MARGIN[1] + MARGIN[3], (0.3f * 255).toInt())
        c.translate(MARGIN[0], MARGIN[1])
        val rect = RectF(0f, 0f, w, h)
        val capsule = Path().apply { addRoundRect(rect, h / 2, h / 2, Path.Direction.CW) }
        // Raised off the page: a tight contact shadow.
        c.drawPath(capsule, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rgb(island.tint.stops.last()); setShadowLayer(3f, 0f, 1.5f, rgb(0, 0.22f)) })
        c.save()
        c.clipPath(capsule)
        paint(island.tint, rect, c)
        relief(rect, c)
        val gx = 10f; val gy = h / 2
        when {
            look.polaroid -> polaroid(if (island.kind == Island.Kind.PHOTO) thumbnail(island.ref) else null, print, gx + glyphW / 2, gy, c)
            look.serif -> c.drawText("W", gx, gy + markPaint.textSize * 0.32f, markPaint)
            else -> glyph(look.glyph ?: island.icon, gx, gy, rgb(look.glyphInk ?: look.ink), look.halo, c)
        }
        val fm = textPaint.fontMetrics
        c.drawText(title, gx + glyphW + 5, gy - (fm.ascent + fm.descent) / 2, textPaint)
        c.restore()
        if (faded) c.restore()
        cache[key] = bitmap
        return bitmap
    }

    /** Points the capsule takes in a line of text, shadow margin included. */
    fun widthPoints(context: Context, island: Island): Float {
        val b = bitmap(context, island, 1f)
        return b.width.toFloat()
    }

    // MARK: Glyphs (small line drawings in place of SF Symbols)

    private fun glyph(name: String, x: Float, cy: Float, color: Int, halo: Pair<Int, Float>?, c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 1.6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; halo?.let { (h, r) -> setShadowLayer(r, 0f, 0f, rgb(h, 0.9f)) } }
        val fillP = Paint(p).apply { style = Paint.Style.FILL }
        val s = 11f; val l = x; val t = cy - s / 2
        when (name) {
            "pin" -> { // map pin
                val path = Path().apply {
                    moveTo(l + s / 2, t + s); cubicTo(l + s * 0.1f, t + s * 0.55f, l + s * 0.12f, t, l + s / 2, t)
                    cubicTo(l + s * 0.88f, t, l + s * 0.9f, t + s * 0.55f, l + s / 2, t + s); close()
                }
                c.drawPath(path, fillP)
                c.drawCircle(l + s / 2, t + s * 0.36f, s * 0.14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() })
            }
            "compass" -> {
                c.drawCircle(l + s / 2, cy, s / 2 - 0.5f, p)
                val needle = Path().apply { moveTo(l + s * 0.72f, t + s * 0.28f); lineTo(l + s * 0.42f, t + s * 0.58f); lineTo(l + s * 0.28f, t + s * 0.72f); lineTo(l + s * 0.58f, t + s * 0.42f); close() }
                c.drawPath(needle, fillP)
            }
            "calendar" -> { c.drawRoundRect(RectF(l, t + 1, l + s, t + s), 2f, 2f, p); c.drawLine(l, t + 4, l + s, t + 4, p) }
            "checklist" -> for (i in 0..2) { val y = t + 2 + i * 3.5f; c.drawCircle(l + 1.5f, y, 1.1f, fillP); c.drawLine(l + 4.5f, y, l + s, y, p) }
            "clipboard" -> { c.drawRoundRect(RectF(l + 1, t + 1.5f, l + s - 1, t + s), 2f, 2f, fillP) }
            "doc" -> { c.drawRoundRect(RectF(l + 1.5f, t, l + s - 1.5f, t + s), 2f, 2f, fillP) }
            "server" -> for (i in 0..1) c.drawRoundRect(RectF(l, t + 1 + i * 5f, l + s, t + 4.5f + i * 5f), 1.5f, 1.5f, fillP)
            else -> c.drawCircle(l + s / 2, cy, s / 2 - 0.5f, p)
        }
    }

    // MARK: Light

    /** A gloss across the top, shade gathering underneath, a lit rim above and a darker one below. */
    private fun relief(r: RectF, c: Canvas) {
        val h = r.height(); val w = r.width()
        c.drawRect(0f, 0f, w, h * 0.55f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.55f, rgb(0xFFFFFF, 0.42f), rgb(0xFFFFFF, 0.06f), Shader.TileMode.CLAMP) })
        c.drawRect(0f, h * 0.5f, w, h, Paint().apply { shader = LinearGradient(0f, h * 0.5f, 0f, h, rgb(0, 0f), rgb(0, 0.16f), Shader.TileMode.CLAMP) })
        val inner = RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
        c.save(); c.clipRect(0f, 0f, w, h * 0.5f); rim.color = rgb(0xFFFFFF, 0.6f); c.drawRoundRect(inner, (h - 1) / 2, (h - 1) / 2, rim); c.restore()
        c.save(); c.clipRect(0f, h * 0.5f, w, h); rim.color = rgb(0, 0.12f); c.drawRoundRect(inner, (h - 1) / 2, (h - 1) / 2, rim); c.restore()
    }

    // MARK: Scenes

    private fun paint(tint: IslandTint, r: RectF, c: Canvas) = when (tint) {
        IslandTint.GREEN -> map(r, c)
        IslandTint.RED -> calendar(r, c)
        IslandTint.ORANGE -> reminders(r, c)
        IslandTint.BLUE -> globe(r, c)
        IslandTint.PURPLE -> clipboard(r, c)
        IslandTint.SLATE -> page(r, c)
        IslandTint.AMBER -> notepad(r, c)
        IslandTint.PINK -> paper(r, c)
        IslandTint.TEAL -> circuit(r, c)
        IslandTint.INDIGO -> letters(r, c)
    }

    private fun fill(color: Int, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = rgb(color, alpha) }
    private fun stroke(color: Int, width: Float, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = rgb(color, alpha); style = Paint.Style.STROKE; strokeWidth = width; strokeCap = Paint.Cap.ROUND }
    private fun gradient(stops: List<Int>, r: RectF, c: Canvas, y0: Float = r.top, y1: Float = r.bottom) =
        c.drawRect(r, Paint().apply { shader = LinearGradient(0f, y0, 0f, y1, stops.map { rgb(it) }.toIntArray(), null, Shader.TileMode.CLAMP) })

    /** A scrap of street map: land, a park, the sea along the right, streets and a highway. */
    private fun map(r: RectF, c: Canvas) {
        val w = r.width(); val h = r.height()
        c.drawRect(r, fill(0xF2EFE6))
        c.drawOval(RectF(w * 0.04f, h * 0.5f, w * 0.36f, h * 1.45f), fill(0xCDE7B4))
        c.drawOval(RectF(w * 0.5f, -h * 0.55f, w * 0.7f, h * 0.35f), fill(0xD5ECC2))
        c.drawRoundRect(RectF(w * 0.46f, h * 0.58f, w * 0.56f, h * 0.88f), 1.5f, 1.5f, fill(0xE9E3D3))
        val coast = Path().apply {
            moveTo(w * 0.74f, -1f); cubicTo(w * 0.84f, h * 0.35f, w * 0.7f, h * 0.7f, w * 0.79f, h + 1)
            lineTo(w + 1, h + 1); lineTo(w + 1, -1f); close()
        }
        c.drawPath(coast, fill(0xBFE0F7))
        c.save(); c.translate(w * 0.035f, 0f); c.drawPath(coast, fill(0xA7D3F3)); c.restore()
        for (b in listOf(floatArrayOf(0.08f, 0.12f, 0.07f, 0.22f), floatArrayOf(0.2f, 0.08f, 0.06f, 0.18f), floatArrayOf(0.28f, 0.42f, 0.05f, 0.16f), floatArrayOf(0.58f, 0.5f, 0.06f, 0.2f), floatArrayOf(0.62f, 0.1f, 0.05f, 0.14f), floatArrayOf(0.12f, 0.42f, 0.06f, 0.12f))) {
            c.drawRoundRect(RectF(w * b[0], h * b[1], w * (b[0] + b[2]), h * (b[1] + b[3])), 1f, 1f, fill(0xE8E3D6))
        }
        val lanes = stroke(0xFFFFFF, 0.9f)
        for (l in listOf(floatArrayOf(0f, 0.36f, 0.7f, 0.44f), floatArrayOf(0.05f, -0.1f, 0.2f, 1.1f), floatArrayOf(0.52f, -0.1f, 0.6f, 1.1f), floatArrayOf(0.25f, 1f, 0.68f, 0.62f), floatArrayOf(0.27f, -0.1f, 0.3f, 0.5f))) {
            c.drawLine(w * l[0], h * l[1], w * l[2], h * l[3], lanes)
        }
        fun road(path: Path, width: Float, color: Int, casing: Int) { c.drawPath(path, stroke(casing, width + 1.2f)); c.drawPath(path, stroke(color, width)) }
        road(Path().apply { moveTo(-2f, h * 0.82f); cubicTo(w * 0.25f, h * 0.62f, w * 0.5f, h * 0.2f, w * 0.76f, h * 0.18f) }, 2.2f, 0xFFFFFF, 0xE0DACB)
        road(Path().apply { moveTo(w * 0.36f, -2f); lineTo(w * 0.43f, h + 2) }, 1.8f, 0xFFFFFF, 0xE0DACB)
        road(Path().apply { moveTo(w * 0.16f, -2f); cubicTo(w * 0.3f, h * 0.6f, w * 0.55f, h * 0.75f, w * 0.7f, h + 2) }, 2.4f, 0xFAD877, 0xE9BE57)
    }

    /** A ruled page with a red top edge and today's date fading off the end. */
    private fun calendar(r: RectF, c: Canvas) {
        val w = r.width(); val h = r.height()
        gradient(listOf(0xFFFFFF, 0xFBF4F3), r, c)
        val grid = stroke(0xF3E1DF, 0.6f)
        var x = 8f
        while (x < w) { c.drawLine(x, 5f, x, h, grid); x += 9 }
        for (y in listOf(h * 0.52f, h * 0.8f)) c.drawLine(0f, y, w, y, grid)
        gradient(listOf(0xFF6A5E, 0xE5302A), RectF(0f, 0f, w, 4.5f), c)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; textSize = 24f; color = rgb(0xE5302A, 0.22f) }
        c.drawText(day, w - p.measureText(day) - 7, h / 2 + 2 + 8, p)
    }

    /** A list: faint rules and the three coloured dots of a Reminders list. */
    private fun reminders(r: RectF, c: Canvas) {
        val w = r.width(); val h = r.height()
        gradient(listOf(0xFFFFFF, 0xF6F6F8), r, c)
        for (y in listOf(h * 0.3f, h * 0.7f)) c.drawLine(0f, y, w, y, stroke(0xEAEAEE, 0.7f))
        listOf(0x3478F6, 0xFF3B30, 0xFF9500).forEachIndexed { i, color -> c.drawCircle(w - 25 + i * 6.5f, h / 2, 2.6f, fill(color)) }
    }

    /** Safari blue with a globe's meridians showing through. */
    private fun globe(r: RectF, c: Canvas) {
        val w = r.width(); val h = r.height()
        gradient(listOf(0x63C3FF, 0x1A73F0), r, c)
        val cx = w - 14; val cy = h * 0.55f; val rad = 19f
        val lines = stroke(0xFFFFFF, 0.8f, 0.28f)
        c.drawCircle(cx, cy, rad, lines)
        for (k in listOf(0.35f, 0.7f)) c.drawOval(RectF(cx - rad * k, cy - rad, cx + rad * k, cy + rad), lines)
        for (dy in listOf(-0.5f, 0f, 0.5f)) { val y = cy + rad * dy; val half = sqrt(max(0f, rad * rad - (rad * dy) * (rad * dy))); c.drawLine(cx - half, y, cx + half, y, lines) }
        val ticks = stroke(0xFFFFFF, 0.7f, 0.14f)
        for (i in 0 until 14) { val x = 4 + i * 7f; c.drawLine(x, h - 3, x, h - if (i % 2 == 0) 6f else 4.5f, ticks) }
    }

    /** Lavender with two copied sheets stacked at the end. */
    private fun clipboard(r: RectF, c: Canvas) {
        val w = r.width()
        gradient(listOf(0xF4EEFF, 0xE7DDFF), r, c)
        listOf(22f, 14f).forEachIndexed { i, dx ->
            val sheet = RectF(w - dx, 4 + i * 4f, w - dx + 16, 26 + i * 4f)
            c.drawRoundRect(sheet, 3f, 3f, fill(0xFFFFFF, 0.9f)); c.drawRoundRect(sheet, 3f, 3f, stroke(0xC9B6FF, 0.7f, 0.9f))
        }
        for (y in listOf(13f, 17f)) c.drawLine(w - 11, y, w - 3, y, stroke(0xD8CBFF, 0.8f))
    }

    /** An encyclopedia page: off-white with faint lines of text. */
    private fun page(r: RectF, c: Canvas) {
        val w = r.width()
        c.drawRect(r, fill(0xFAFAF7))
        val widths = listOf(0.5f, 0.3f, 0.62f, 0.22f, 0.4f, 0.55f, 0.18f, 0.35f)
        listOf(4f, 9.5f, 15f, 20.5f).forEachIndexed { row, y ->
            var x = if (row % 2 == 0) 4f else 12f
            widths.forEachIndexed { i, part -> if ((i + row) % 3 != 0) { val len = w * part * 0.35f; c.drawRoundRect(RectF(x, y, x + len, y + 2), 1f, 1f, fill(0xE9E9E3)); x += len + 4 } }
        }
    }

    /** A notepad: Notes-yellow top, ruled paper below. */
    private fun notepad(r: RectF, c: Canvas) {
        val w = r.width()
        c.drawRect(r, fill(0xFFFDF4))
        gradient(listOf(0xFFD95A, 0xF6C12E), RectF(0f, 0f, w, 6f), c)
        for (y in listOf(12.5f, 18.5f, 24.5f)) c.drawLine(0f, y, w, y, stroke(0xF0E6C4, 0.7f))
    }

    /** Warm photo paper, faintly grained, for the photo island. */
    private fun paper(r: RectF, c: Canvas) {
        gradient(listOf(0xFFFDF8, 0xF1ECE2), r, c)
        val grain = fill(0xE6DFD2, 0.55f)
        for (i in 0 until (r.width() / 5).toInt()) c.drawOval(RectF(i * 5f + (i * 7) % 3, ((i * 11) % max(1, (r.height() - 2).toInt())).toFloat(), i * 5f + (i * 7) % 3 + 0.9f, ((i * 11) % max(1, (r.height() - 2).toInt())) + 0.9f), grain)
    }

    private val thumbnails = HashMap<String, Bitmap>()

    private fun thumbnail(name: String): Bitmap? {
        thumbnails[name]?.let { return it }
        val file = photos?.let { listOf(File(it, "images/$name"), File(it, "images-temporary/$name")).firstOrNull { f -> f.exists() } } ?: return null
        val full = BitmapFactory.decodeFile(file.path) ?: return null
        val side = min(full.width, full.height)
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, 40, 40, true).also { thumbnails[name] = it }
    }

    /** A tiny instant print, tilted: a white frame, deeper at the bottom, around the picture. */
    private fun polaroid(photo: Bitmap?, size: Pair<Float, Float>, cx: Float, cy: Float, c: Canvas) {
        val (pw, ph) = size
        c.save(); c.translate(cx, cy); c.rotate(-8f)
        val frame = RectF(-pw / 2, -ph / 2, pw / 2, ph / 2)
        c.drawRoundRect(frame, 1.2f, 1.2f, fill(0xFFFFFF).apply { setShadowLayer(1.6f, 0f, 0.8f, rgb(0, 0.3f)) })
        val window = RectF(frame.left + 1.6f, frame.top + 1.6f, frame.left + pw - 1.6f, frame.top + pw - 1.6f)
        c.save(); c.clipRect(window)
        if (photo != null) c.drawBitmap(photo, null, window, Paint(Paint.FILTER_BITMAP_FLAG))
        else { c.translate(window.left, window.top); sunset(RectF(0f, 0f, window.width(), window.height()), c) }
        c.restore(); c.restore()
    }

    /** A photo of a sunset: warm sky, low sun, hills in the Photos petals' purples. */
    private fun sunset(r: RectF, c: Canvas) {
        val w = r.width(); val h = r.height()
        gradient(listOf(0xFFC685, 0xFF8EA0), r, c)
        val sun = min(11f, w * 0.32f)
        c.drawOval(RectF(w - sun - min(11f, w * 0.18f), h * 0.2f, w - min(11f, w * 0.18f), h * 0.2f + sun), fill(0xFFF1B8, 0.95f))
        c.drawPath(Path().apply { moveTo(-1f, h * 0.7f); cubicTo(w * 0.3f, h * 0.35f, w * 0.6f, h * 0.95f, w + 1, h * 0.62f); lineTo(w + 1, h + 1); lineTo(-1f, h + 1); close() }, fill(0xD98AE0))
        c.drawPath(Path().apply { moveTo(-1f, h * 0.92f); cubicTo(w * 0.45f, h * 0.62f, w * 0.7f, h * 1.05f, w + 1, h * 0.78f); lineTo(w + 1, h + 1); lineTo(-1f, h + 1); close() }, fill(0x9A63EE))
    }

    /** Deep teal circuit board: traces and solder points. */
    private fun circuit(r: RectF, c: Canvas) {
        val w = r.width(); val h = r.height()
        gradient(listOf(0x124643, 0x0B2E2C), r, c)
        val traces = stroke(0x2FC6B8, 0.9f, 0.4f).apply { strokeCap = Paint.Cap.BUTT }
        var x = 18f; var up = true
        while (x < w) {
            val y1 = if (up) 5f else h - 5; val y2 = if (up) 10f else h - 10
            c.drawPath(Path().apply { moveTo(x, if (up) -1f else h + 1); lineTo(x, y1); lineTo(x + 6, y2); lineTo(x + 14, y2) }, traces)
            c.drawCircle(x + 14.5f, y2, 1.5f, fill(0x5BE0D3, 0.55f))
            x += 17; up = !up
        }
    }

    /** Indigo with letters from other scripts floating behind. */
    private fun letters(r: RectF, c: Canvas) {
        val w = r.width(); val h = r.height()
        gradient(listOf(0x6C64FF, 0x4038D6), r, c)
        val marks = listOf("文", "A", "あ", "Ж", "ع", "é", "한")
        marks.forEachIndexed { i, m ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = if (i % 2 == 0) 15f else 11f; isFakeBoldText = true; color = rgb(0xFFFFFF, 0.14f) }
            c.drawText(m, i * w / marks.size + 2, if (i % 2 == 0) 11f else h - 2, p)
        }
    }

}
