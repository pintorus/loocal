package app.loocal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.loocal.ui.theme.ink

/**
 * The Loocal loop, ported from Shared/LoopGeometry.swift and App/SpiralLoader.swift:
 * the cursive three-loop stroke (Lottie vertex / in / out tangents, centred on the origin,
 * about 24 x 12 units) and the timing of the spiral loader that writes it.
 */
object LoopGeometry {
    private val v = listOf(-12f to 6f, -4.975f to -1.012f, -8f to -6f, -11.025f to -1.012f, -4f to 6f, 3.025f to -1.012f, 0f to -6f, -3.025f to -1.012f, 4f to 6f, 11.025f to -1.012f, 8f to -6f, 4.98f to -1.012f, 12f to 6f)
    private val i = listOf(0f to 0f, -0.289f to 3.296f, 2.218f to 0f, -0.23f to -2.627f, -4.452f to 0f, -0.289f to 3.296f, 2.218f to 0f, -0.23f to -2.627f, -4.452f to 0f, -0.289f to 3.296f, 2.218f to 0f, -0.232f to -2.627f, -4.443f to 0f)
    private val o = listOf(4.452f to 0f, 0.23f to -2.627f, -2.218f to 0f, 0.289f to 3.296f, 4.452f to 0f, 0.23f to -2.627f, -2.218f to 0f, 0.289f to 3.296f, 4.452f to 0f, 0.23f to -2.627f, -2.218f to 0f, 0.292f to 3.296f, 0f to 0f)

    /** Appends the loop to [path], starting with a moveTo at its left end (-12, 6). */
    fun addLoop(path: Path) {
        path.moveTo(v[0].first, v[0].second)
        for (k in 1 until v.size) {
            path.cubicTo(
                v[k - 1].first + o[k - 1].first, v[k - 1].second + o[k - 1].second,
                v[k].first + i[k].first, v[k].second + i[k].second,
                v[k].first, v[k].second,
            )
        }
    }

    val loop: Path = Path().also(::addLoop)

    data class Frame(val offset: Float, val start: Float, val end: Float)

    /** 4 x 0.5 s fast + 2 x 1 s slow = 4 s cycle, keyframes from the Lottie source. */
    fun frame(time: Double): Frame {
        val cycle = time % 4.0
        val fast = cycle < 2
        val p = if (fast) (cycle % 0.5) / 0.5 else (cycle - 2) % 1.0
        val startEase = if (fast) Bezier(0.32, 0.154, 0.826, 0.579) else Bezier(0.32, 0.313, 0.826, 0.143)
        val endEase = if (fast) Bezier(0.341, 0.488, 0.269, 0.75) else Bezier(0.341, 0.992, 0.269, 0.491)
        val offset = 12 - 8 * Bezier(0.167, 0.167, 0.833, 0.833).value(p)
        return Frame(offset.toFloat(), (0.23 + 0.34 * startEase.value(p)).toFloat(), (0.44 + 0.33 * endEase.value(p)).toFloat())
    }
}

/** CSS-style cubic-bezier timing curve. */
class Bezier(private val x1: Double, private val y1: Double, private val x2: Double, private val y2: Double) {
    fun value(x: Double): Double {
        var t = x
        repeat(8) {
            val dx = sample(t, x1, x2) - x
            val d = slope(t, x1, x2)
            if (kotlin.math.abs(dx) < 1e-5 || kotlin.math.abs(d) < 1e-6) return sample(t.coerceIn(0.0, 1.0), y1, y2)
            t -= dx / d
        }
        return sample(t.coerceIn(0.0, 1.0), y1, y2)
    }
    private fun sample(t: Double, a: Double, b: Double) = ((1 - 3 * b + 3 * a) * t + (3 * b - 6 * a)) * t * t + 3 * a * t
    private fun slope(t: Double, a: Double, b: Double) = 3 * (1 - 3 * b + 3 * a) * t * t + 2 * (3 * b - 6 * a) * t + 3 * a
}

/** Seconds since the composable started, ticking every frame. */
@Composable
fun rememberClock(paused: Boolean = false): Double {
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        val start = withFrameNanos { it } - nanos
        while (true) withFrameNanos { nanos = it - start }
    }
    return nanos / 1e9
}

private fun DrawScope.strokeTrimmed(source: Path, transform: Matrix, from: Float, to: Float, color: Color, width: Float, dash: PathEffect? = null) {
    val moved = Path().apply { addPath(source); transform(transform) }
    val measure = PathMeasure().apply { setPath(moved, false) }
    val out = Path()
    measure.getSegment(measure.length * from, measure.length * to, out, true)
    drawPath(out, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash))
}

/** Native port of the 21st.dev Spiral Loader: a trimmed segment written in place. */
@Composable
fun SpiralLoader(size: Dp = 16.dp, color: Color = ink.primary, modifier: Modifier = Modifier) {
    val time = rememberClock()
    Canvas(modifier.size(size).clipToBounds()) {
        val frame = LoopGeometry.frame(time)
        val scale = this.size.width / 16f
        val m = Matrix().apply { scale(scale, scale); translate(frame.offset, 8f) }
        strokeTrimmed(LoopGeometry.loop, m, frame.start, frame.end, color, 1.4f * scale)
    }
}

/** The whole loop, the Loocal mark, in a 2:1 box. Dashed: the temporary-chat mark. */
@Composable
fun LoopMark(modifier: Modifier = Modifier, color: Color = ink.primary, dashed: Boolean = false) {
    Canvas(modifier.aspectRatio(2f)) {
        val scale = size.width / 26f
        val m = Matrix().apply { translate(size.width / 2, size.height / 2); scale(scale, scale) }
        val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.4f * scale, 2.6f * scale)) else null
        strokeTrimmed(LoopGeometry.loop, m, 0f, 1f, color, 1.4f * scale, dash)
    }
}

/**
 * The loop that unravels into a ghost (temporary chat) and winds back: one thread, the loop,
 * a short slack from its right end, then a ghost outline. [progress] 0 is the loop, 1 the ghost.
 */
@Composable
fun LoopGhostMark(progress: Float, modifier: Modifier = Modifier, color: Color = ink.primary) {
    Canvas(modifier.aspectRatio(2f)) {
        val p = progress.coerceIn(0f, 1f)
        val from = p * Ghost.ghostStart
        val to = Ghost.loopEnd + p * (1 - Ghost.loopEnd)
        val scale = size.width / 26f
        val m = Matrix().apply { translate(size.width / 2, size.height / 2); scale(scale, scale) }
        strokeTrimmed(Ghost.thread, m, from, to, color, 1.4f * scale)
    }
}

private object Ghost {
    val thread = Path()
    val loopEnd: Float
    val ghostStart: Float

    init {
        LoopGeometry.addLoop(thread)
        val loopLength = length(thread)
        thread.quadraticTo(9f, 7.6f, 5f, 4.8f) // bridge, from the loop's right end (12, 6)
        val withBridge = length(thread)
        thread.lineTo(5f, -0.6f)
        thread.cubicTo(5f, -3.9f, 2.8f, -6f, 0f, -6f)
        thread.cubicTo(-2.8f, -6f, -5f, -3.9f, -5f, -0.6f)
        thread.lineTo(-5f, 4.8f)
        for (k in 0 until 4) {
            val x0 = -5f + k * 2.5f
            thread.quadraticTo(x0 + 1.25f, 6.6f, x0 + 2.5f, 4.8f)
        }
        val total = length(thread)
        loopEnd = loopLength / total
        ghostStart = withBridge / total
    }

    private fun length(path: Path) = PathMeasure().apply { setPath(path, false) }.length
}

/** The loop centred at a point, for drawing inside other canvases. */
fun DrawScope.drawLoop(center: Offset, width: Float, color: Color) {
    val scale = width / 26f
    val m = Matrix().apply { translate(center.x, center.y); scale(scale, scale) }
    strokeTrimmed(LoopGeometry.loop, m, 0f, 1f, color, 1.4f * scale)
}
