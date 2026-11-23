package app.loocal.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loocal.R
import app.loocal.ui.theme.InstrumentSerif
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import app.loocal.ui.theme.serif
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** The acrylic palette: cobalt, cyan, lime, yolk, coral, magenta, violet. */
val PaintColors = listOf(Color(0xFF004DFF), Color(0xFF0DCCFF), Color(0xFF8CF70D), Color(0xFFFFD61A), Color(0xFFFF5C6B), Color(0xFFF21F9E), Color(0xFF8038FF))

/** A small repeatable random sequence, so a canvas looks the same every time it's drawn. */
class SplitMix(seed: Long) {
    private var state = seed
    fun next(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    fun unit(): Float = ((next() ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
}

/** Wet, flowing paint: the colours blend round the palette continuously, drifting slowly. */
@Composable
fun liquidPaintBrush(): Brush {
    val t by rememberInfiniteTransition(label = "liquid").animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    return remember(t) {
        object : androidx.compose.ui.graphics.ShaderBrush() {
            override fun createShader(size: Size): android.graphics.Shader {
                val a = t * 2 * PI.toFloat()
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.width / 2
                val from = c + Offset(cos(a) * r, sin(a) * size.height)
                val to = c - Offset(cos(a) * r, sin(a) * size.height)
                val cols = (PaintColors + PaintColors.first()).map { c: Color -> c.toArgb() }.toIntArray()
                return android.graphics.LinearGradient(from.x, from.y, to.x, to.y, cols, null, android.graphics.Shader.TileMode.MIRROR)
            }
        }
    }
}


// MARK: The painter's page

/** The empty chat in paint mode: "What are we painting?" over a village after Pissarro (Knokke, 1894), at the foot of the page. */
@Composable
fun PainterPage(bottomInset: androidx.compose.ui.unit.Dp) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Always the full width; with the keyboard up its foot is cropped instead of shrinking.
        val aspect = 668f / 800f
        Image(
            painterResource(R.drawable.paint_village), null,
            contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(minOf(maxWidth * aspect, maxHeight * 0.55f)).clipToBounds(),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = maxHeight * 0.16f).padding(horizontal = 32.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append("What are we ")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontFamily = InstrumentSerif)) { append("painting?") }
                },
                style = serif(40, color = ink.primary).copy(textAlign = TextAlign.Center, lineHeight = 44.sp),
            )
        }
    }
}


// MARK: Painting a picture

/**
 * The reply while a picture is painted: a grey rounded square slowly covered in textured brush
 * strokes laid at random, overlapping, with the percentage in the corner. The finished picture is
 * brushed in over them. Afterwards, just the picture.
 */
@Composable
fun PaintCanvas(id: String, done: Int, total: Int, image: File?, fresh: Boolean, stopped: Boolean = false) {
    val side = 288.dp
    val bitmap = remember(image?.path) { image?.let { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    // Glides toward the target between the model's few steps, so a step finishing never jumps it.
    var shown by remember { mutableFloatStateOf(0f) }
    val target = if (total == 0) 0.04f else (done + 0.85f) / (total + 0.5f)
    LaunchedEffect(bitmap == null) {
        var last = 0L
        while (bitmap == null) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                shown += (minOf(target, 0.97f) - shown) * (1 - exp(-dt / 0.9f))
            }
        }
    }
    val reveal = remember { Animatable(if (fresh && bitmap == null) 0f else 1f) }
    LaunchedEffect(bitmap) { if (bitmap != null && reveal.value < 1f) reveal.animateTo(1f, tween(1400)) }
    Box(Modifier.size(side).clip(RoundedCornerShape(22.dp)).border(1.dp, ink.hairline, RoundedCornerShape(22.dp))) {
        if (bitmap == null || reveal.value < 1f) {
            Canvas(Modifier.fillMaxSize().background(Color(0xFF8E8E93).copy(alpha = 0.35f))) { strokes(id, if (bitmap != null) 1f else shown) }
        }
        if (bitmap != null) {
            Canvas(Modifier.fillMaxSize()) {
                if (reveal.value >= 1f) drawImage(bitmap, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                else clipPath(revealPath(id, size, reveal.value)) { drawImage(bitmap, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
            }
        } else {
            Text(
                if (stopped) "Stopped" else "${(shown * 100).toInt()}%",
                style = geist(11, FontWeight.SemiBold, ink.primary),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(ink.paper.copy(alpha = 0.8f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** One stroke: a filled ribbon whose width swells and pinches, cut flat at a slant at both ends, frayed by bristles. */
private fun DrawScope.ribbon(r: SplitMix, color: Color, w: Float) {
    val x0 = r.unit() * size.width; val y0 = r.unit() * size.height
    val angle = r.unit() * 2 * PI.toFloat()
    val length = size.width * (0.25f + r.unit() * 0.45f)
    val bend = (r.unit() - 0.5f) * length * 0.5f
    val nx = -sin(angle); val ny = cos(angle)
    val n = 18
    val top = ArrayList<Offset>(); val bottom = ArrayList<Offset>()
    val slant = (r.unit() - 0.5f) * w
    for (i in 0..n) {
        val t = i / n.toFloat()
        val along = t * length
        val curve = sin(t * PI.toFloat()) * bend
        val cx = x0 + cos(angle) * along + nx * curve
        val cy = y0 + sin(angle) * along + ny * curve
        val half = w / 2 * (0.75f + 0.35f * sin(t * PI.toFloat() * (1.5f + r.unit())))
        val cut = if (i == 0) slant else if (i == n) -slant else 0f
        top.add(Offset(cx + nx * half + cos(angle) * cut, cy + ny * half + sin(angle) * cut))
        bottom.add(Offset(cx - nx * half - cos(angle) * cut, cy - ny * half - sin(angle) * cut))
    }
    val path = Path().apply {
        moveTo(top[0].x, top[0].y); top.drop(1).forEach { lineTo(it.x, it.y) }
        bottom.reversed().forEach { lineTo(it.x, it.y) }; close()
    }
    drawPath(path, color.copy(alpha = 0.78f))
    // Bristles: thin streaks along the stroke, a little lighter, of different lengths.
    repeat(5) { k ->
        val off = (k / 4f - 0.5f) * w * 0.8f
        val l = length * (0.6f + r.unit() * 0.45f)
        drawLine(Color.White.copy(alpha = 0.12f), Offset(x0 + nx * off, y0 + ny * off), Offset(x0 + nx * off + cos(angle) * l, y0 + ny * off + sin(angle) * l), strokeWidth = w * 0.06f)
    }
}

/** Strokes laid at random for [coverage] (0…1) of the canvas. */
private fun DrawScope.strokes(id: String, coverage: Float) {
    val r = SplitMix(id.hashCode().toLong())
    val count = (coverage * 46).toInt()
    repeat(count) { ribbon(r, PaintColors[(r.next() ushr 1).rem(PaintColors.size).toInt()], size.width * (0.08f + r.unit() * 0.1f)) }
}

/** Broad strokes that brush the finished picture in, growing with [progress]. */
private fun revealPath(id: String, size: Size, progress: Float): Path {
    val r = SplitMix(id.hashCode().toLong() xor 0x5EED)
    val path = Path()
    val bands = 7
    for (i in 0 until bands) {
        val start = i / bands.toFloat() * 0.6f
        val p = ((progress - start) / 0.4f).coerceIn(0f, 1f)
        if (p <= 0f) continue
        val h = size.height / bands * 1.6f
        val y = size.height * (i + 0.5f) / bands + (r.unit() - 0.5f) * h * 0.3f
        val leftToRight = i % 2 == 0
        val w = size.width * 1.1f * p
        val x = if (leftToRight) -size.width * 0.05f else size.width * 1.05f - w
        path.addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y - h / 2, x + w, y + h / 2, h / 2, h / 2))
    }
    return path
}

// MARK: Entering paint mode

private const val POUR = """
uniform float2 size; uniform float time; uniform float fill; uniform float drain; uniform float2 origin; uniform float2 pill;
float hash21(float2 p) { p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(float2 p) { float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash21(i), hash21(i + float2(1, 0)), u.x), mix(hash21(i + float2(0, 1)), hash21(i + float2(1, 1)), u.x), u.y); }
float fbm(float2 p) { float v = 0.0; float a = 0.5; for (int i = 0; i < 4; i++) { v += a * noise(p); p = float2(1.6 * p.x - 1.2 * p.y, 1.2 * p.x + 1.6 * p.y); a *= 0.5; } return v; }
float smin(float a, float b, float k) { float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0); return mix(b, a, h) - k * h * (1.0 - h); }
half4 main(float2 position) {
  float2 uv = position / size.y; float2 o = origin / size.y; float y = uv.y - o.y; float x = uv.x - o.x;
  float2 hp = pill * 0.5 / size.y; float margin = 0.014; float aa = 1.5 / size.y;
  float r = hp.y + margin;
  float collar = length(float2(max(abs(x) - (hp.x - hp.y), 0.0), y)) - r;
  float edge = size.x / size.y * 0.5; float t = clamp(y / 0.26, 0.0, 1.0); float side = x < 0.0 ? 0.0 : 7.3;
  float lobes = 0.05 * sin(y * 11.0 - time * 7.0 + side) + 0.07 * (fbm(float2(y * 4.0 - time * 1.5, side)) - 0.5);
  float width = (hp.x + margin) + (edge + 0.09 - hp.x) * t * t * (3.0 - 2.0 * t) + lobes * t;
  float fall = max(abs(x) - width, -y);
  float shape = smin(collar, fall, 0.05);
  float fingers = fbm(float2(uv.x * 7.0, 1.3));
  float drips = (0.16 * fingers * fingers + 0.05 * fbm(float2(uv.x * 18.0, time * 0.6))) * min(fill * 3.0, 1.0);
  float front = -hp.y - margin + fill * 1.35 + drips;
  float inside = smoothstep(aa, -aa, shape) * smoothstep(-aa, aa * 2.0, front - y);
  float top = drain * 1.5 - 0.25 + drips * 0.7;
  if (drain > 0.0) inside *= smoothstep(0.0, aa * 2.0, y - top);
  if (inside <= 0.0) return half4(0.0);
  float2 p = uv * 2.7 + float2(0.0, -time * 0.9);
  float2 q = float2(fbm(p + float2(0.0, time * 0.35)), fbm(p + float2(5.2, 1.3) - time * 0.25));
  float2 rr = float2(fbm(p + 3.8 * q + float2(1.7, 9.2) + time * 0.45), fbm(p + 3.8 * q + float2(8.3, 2.8) - time * 0.3));
  float f = fbm(p + 3.4 * rr);
  float3 navy = float3(0.03, 0.04, 0.20); float3 cobalt = float3(0.0, 0.30, 1.0); float3 cyan = float3(0.05, 0.80, 1.0);
  float3 lime = float3(0.55, 0.97, 0.05); float3 yolk = float3(1.0, 0.84, 0.10); float3 coral = float3(1.0, 0.36, 0.42);
  float3 magenta = float3(0.95, 0.12, 0.62); float3 violet = float3(0.50, 0.22, 1.0); float3 gold = float3(1.0, 0.66, 0.18);
  float3 col = mix(yolk, coral, smoothstep(0.44, 0.52, q.x));
  col = mix(col, magenta, smoothstep(0.58, 0.64, q.x) * smoothstep(0.45, 0.55, rr.y));
  col = mix(col, cyan, smoothstep(0.45, 0.50, rr.x));
  col = mix(col, cobalt, smoothstep(0.50, 0.55, f));
  col = mix(col, violet, smoothstep(0.56, 0.62, rr.y) * smoothstep(0.52, 0.58, f));
  col = mix(col, lime, smoothstep(0.62, 0.67, rr.y) * (1.0 - smoothstep(0.52, 0.56, f)));
  col = mix(col, navy, smoothstep(0.62, 0.68, f) * smoothstep(0.48, 0.56, rr.x));
  float vein = 1.0 - smoothstep(0.0, 0.022, abs(fract(q.y * 5.0 + f * 3.0) - 0.5));
  col = mix(col, gold, vein * 0.95 * smoothstep(0.4, 0.55, rr.y));
  float strand = 1.0 - smoothstep(0.0, 0.012, abs(fract(rr.x * 7.0 + f * 4.0) - 0.5));
  col = mix(col, float3(1.0), strand * 0.6 * smoothstep(0.5, 0.56, f));
  float grey = dot(col, float3(0.299, 0.587, 0.114));
  col = clamp(mix(float3(grey), col, 1.3), 0.0, 1.0);
  col += 0.14 * pow(1.0 - abs(fract(f * 3.0) - 0.5) * 2.0, 12.0);
  float2 cp = (uv + float2(0.0, -time * 0.45)) * 30.0 + rr * 4.0; float2 ci = floor(cp); float2 cf = fract(cp);
  float2 c = float2(hash21(ci + 3.1), hash21(ci + 7.7)) * 0.6 + 0.2; float radius = 0.07 + 0.22 * hash21(ci + 1.9);
  float cell = hash21(ci) > 0.7 ? 1.0 - smoothstep(radius - 0.05, radius, length(cf - c)) : 0.0;
  col = mix(col, float3(1.0), cell * 0.95 * smoothstep(0.48, 0.54, f));
  float lip = smoothstep(0.03, 0.0, front - y);
  col = mix(col, col * 0.8 + 0.12, lip * 0.6);
  return half4(half3(col * inside), half(inside));
}
"""

/** Whether the pour can be drawn here (AGSL needs Android 13). */
val canPour get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * A fluid-acrylic pour: marbled paint wells up round the model selector ([origin], [pill], in px),
 * pours down to fill the screen, then drains from the top while fading (1.3 s). [covered] is
 * called when the paint covers the whole page, so what's under it can change unseen.
 */
@Composable
fun PaintPour(origin: Offset, pill: Size, covered: () -> Unit, finished: () -> Unit) {
    if (!canPour) { LaunchedEffect(Unit) { covered(); finished() }; return }
    val shader = remember { RuntimeShader(POUR) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var told by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (elapsed < 1.3f) {
            withFrameNanos { now -> elapsed = (now - start) / 1e9f }
            if (!told && elapsed >= 0.55f) { told = true; covered() }
        }
        finished()
    }
    Canvas(Modifier.fillMaxSize()) {
        val fill = Math.pow((elapsed / 0.55).coerceIn(0.0, 1.0), 1.4).toFloat()
        val drain = Math.pow(((elapsed - 0.5) / 0.8).coerceIn(0.0, 1.0), 1.3).toFloat()
        val fade = ((elapsed - 0.65f) / 0.65f).coerceIn(0f, 1f)
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", elapsed + 3f)
        shader.setFloatUniform("fill", fill)
        shader.setFloatUniform("drain", drain)
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("pill", pill.width, pill.height)
        drawRect(ShaderBrush(shader), alpha = 1 - fade * fade * (3 - 2 * fade))
    }
}
