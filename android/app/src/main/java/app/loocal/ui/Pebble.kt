package app.loocal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.loocal.ui.theme.ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What the bot's face is doing (iOS `BotMood`). */
enum class BotMood { NEUTRAL, ATTENTIVE, CURIOUS, EXCITED, WORKING, HAPPY, SAD, SLEEPY, WAITING, LOVE, SURPRISED, WINK, DIZZY, PROUD, ANNOYED }

/** A one-off body movement. */
enum class BotReaction { HOP, JUMP, SPIN, SHAKE, NOD, WIGGLE }

/** Each persona gets its own body, so you can tell who's on an errand at a glance. */
enum class BotShape(val label: String, val faceY: Float) {
    PEBBLE("Pebble", 0.015f), ROUND("Round", 0.03f), SQUIRCLE("Squircle", 0.02f), CAPSULE("Capsule", 0f), DROPLET("Droplet", 0.13f), TRIANGLE("Triangle", 0.15f);

    companion object {
        /** The persona's chosen shape, else the next one in line by its Settings position; Loocal itself is the pebble. */
        fun of(personaId: String?, personas: List<app.loocal.domain.Persona>): BotShape {
            personaId ?: return PEBBLE
            val persona = personas.firstOrNull { it.id == personaId }
            persona?.shape?.let { s -> entries.firstOrNull { it.name.equals(s, true) }?.let { return it } }
            val others = listOf(ROUND, SQUIRCLE, CAPSULE, DROPLET, TRIANGLE)
            val index = personas.indexOfFirst { it.id == personaId }.takeIf { it >= 0 } ?: (personaId.hashCode() and 0xff)
            return others[index % others.size]
        }
    }
}

/** A persona bot's colour. Ink is the default; the others are muted so bots sit quietly in the monochrome app. */
enum class BotTint(val color: Color?) {
    INK(null), CLAY(Color(0.76f, 0.41f, 0.27f)), MOSS(Color(0.38f, 0.49f, 0.32f)), SKY(Color(0.30f, 0.48f, 0.67f)), PLUM(Color(0.48f, 0.32f, 0.52f)), SAND(Color(0.72f, 0.59f, 0.37f));

    val label get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun of(personaId: String?, personas: List<app.loocal.domain.Persona>): Color? =
            personas.firstOrNull { it.id == personaId }?.tint?.let { t -> entries.firstOrNull { it.name.equals(t, true) } }?.color
    }
}

/** The body outline in a size x size box. */
fun botBody(shape: BotShape, w: Float, h: Float): Path {
    fun x(f: Float) = f * w
    fun y(f: Float) = f * h
    return Path().apply {
        when (shape) {
            BotShape.PEBBLE -> {
                moveTo(x(0.03f), y(0.59f))
                cubicTo(x(0.01f), y(0.33f), x(0.21f), y(0.15f), x(0.45f), y(0.14f))
                cubicTo(x(0.71f), y(0.13f), x(0.97f), y(0.23f), x(0.97f), y(0.44f))
                cubicTo(x(0.97f), y(0.67f), x(0.79f), y(0.87f), x(0.55f), y(0.87f))
                cubicTo(x(0.29f), y(0.87f), x(0.05f), y(0.82f), x(0.03f), y(0.59f))
                close()
            }
            BotShape.ROUND -> addOval(Rect(x(0.07f), y(0.1f), x(0.93f), y(0.9f)))
            BotShape.SQUIRCLE -> addRoundRect(RoundRect(Rect(x(0.1f), y(0.12f), x(0.9f), y(0.88f)), CornerRadius(w * 0.3f)))
            BotShape.CAPSULE -> addRoundRect(RoundRect(Rect(x(0.02f), y(0.23f), x(0.98f), y(0.77f)), CornerRadius(h * 0.27f)))
            BotShape.DROPLET -> {
                moveTo(x(0.5f), y(0.03f))
                cubicTo(x(0.6f), y(0.2f), x(0.88f), y(0.36f), x(0.88f), y(0.6f))
                cubicTo(x(0.88f), y(0.82f), x(0.71f), y(0.95f), x(0.5f), y(0.95f))
                cubicTo(x(0.29f), y(0.95f), x(0.12f), y(0.82f), x(0.12f), y(0.6f))
                cubicTo(x(0.12f), y(0.36f), x(0.4f), y(0.2f), x(0.5f), y(0.03f))
                close()
            }
            BotShape.TRIANGLE -> addPath(roundedTriangle(w, h, -0.04f, 0.15f))
        }
    }
}

/** A triangle with soft, rounded corners, point up. */
fun roundedTriangle(w: Float, h: Float, inset: Float, radius: Float): Path {
    val top = Offset(w / 2, h * (inset + 0.04f))
    val right = Offset(w - w * inset, h - h * (inset + 0.06f))
    val left = Offset(w * inset, h - h * (inset + 0.06f))
    val r = w * radius
    // Corners rounded by stopping short of each vertex and curving through it.
    fun toward(a: Offset, b: Offset, d: Float): Offset { val v = b - a; val len = v.getDistance(); return a + v * (d / len) }
    return Path().apply {
        val corners = listOf(top, right, left)
        val start = toward(top, right, r)
        moveTo(start.x, start.y)
        for (i in corners.indices) {
            val a = corners[(i + 1) % 3]; val prev = corners[i]; val next = corners[(i + 2) % 3]
            val inPoint = toward(a, prev, r); val outPoint = toward(a, next, r)
            lineTo(inPoint.x, inPoint.y)
            quadraticTo(a.x, a.y, outPoint.x, outPoint.y)
        }
        close()
    }
}

private enum class EyeKind { PILL, HAPPY, CLOSED, HEART, CROSS, LID }
private data class EyeSpec(val kind: EyeKind = EyeKind.PILL, val width: Float, val height: Float, val tilt: Float = 0f)

private fun spec(mood: BotMood, left: Boolean) = when (mood) {
    BotMood.NEUTRAL -> EyeSpec(width = 0.085f, height = 0.19f)
    BotMood.ATTENTIVE -> EyeSpec(width = 0.085f, height = 0.16f)
    BotMood.CURIOUS -> if (left) EyeSpec(width = 0.085f, height = 0.19f) else EyeSpec(width = 0.09f, height = 0.12f)
    BotMood.EXCITED -> EyeSpec(width = 0.135f, height = 0.145f)
    BotMood.WORKING -> EyeSpec(width = 0.11f, height = 0.075f)
    BotMood.HAPPY -> EyeSpec(EyeKind.HAPPY, 0.13f, 0.07f)
    BotMood.SAD -> EyeSpec(width = 0.08f, height = 0.13f, tilt = if (left) 22f else -22f)
    BotMood.SLEEPY -> EyeSpec(EyeKind.CLOSED, 0.12f, 0.05f)
    BotMood.WAITING -> EyeSpec(width = 0.085f, height = 0.12f)
    BotMood.LOVE -> EyeSpec(EyeKind.HEART, 0.15f, 0.14f)
    BotMood.SURPRISED -> EyeSpec(width = 0.11f, height = 0.25f)
    BotMood.WINK -> if (left) EyeSpec(EyeKind.HAPPY, 0.13f, 0.07f) else EyeSpec(width = 0.085f, height = 0.19f)
    BotMood.DIZZY -> EyeSpec(EyeKind.CROSS, 0.12f, 0.12f)
    BotMood.PROUD -> EyeSpec(EyeKind.LID, 0.13f, 0.08f, if (left) 6f else -6f)
    BotMood.ANNOYED -> EyeSpec(EyeKind.LID, 0.13f, 0.065f, if (left) -12f else 12f)
}

/**
 * The errand bot (port of iOS `PebbleBot`): a glossy body with two eyes that blink, glance around,
 * follow what you type and change with its mood. Ink on paper, so it flips with dark mode.
 * [gaze] is -1…1 on each axis; null lets it glance on its own. Bump [reaction] to play [reactionKind].
 */
@Composable
fun PebbleBot(
    mood: BotMood = BotMood.NEUTRAL,
    shape: BotShape = BotShape.PEBBLE,
    size: Dp = 120.dp,
    gaze: Offset? = null,
    lively: Boolean = true,
    reaction: Int = 0,
    tint: Color? = null,
    reactionKind: BotReaction = BotReaction.HOP,
    modifier: Modifier = Modifier,
) {
    val body = tint ?: ink.primary
    val eyeColor = ink.paper
    val time = if (lively) rememberClock() else 0.0
    var blink by remember { mutableStateOf(false) }
    var glance by remember { mutableStateOf(Offset.Zero) }

    // Idle life: blinks now and then, and a glance around when nobody is directing its eyes.
    LaunchedEffect(lively, mood, gaze == null) {
        if (!lively) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(2200, 4800))
            if (mood in setOf(BotMood.NEUTRAL, BotMood.ATTENTIVE, BotMood.CURIOUS, BotMood.EXCITED, BotMood.WORKING, BotMood.SAD, BotMood.WAITING, BotMood.SURPRISED)) {
                blink = true; delay(110); blink = false
                if (Random.nextInt(8) == 0) { delay(140); blink = true; delay(100); blink = false }
            }
            if (gaze == null && Random.nextInt(3) == 0) {
                glance = if (Random.nextInt(3) == 0) Offset.Zero else Offset(Random.nextFloat() * 1.6f - 0.8f, Random.nextFloat() * 0.9f - 0.4f)
            }
        }
    }

    // Reading something: the eyes sweep across a line, then hop back.
    val target = if (mood == BotMood.WORKING && lively) Offset(-0.7f + ((time * 0.9) % 1.0).toFloat() * 1.4f, 0.25f) else gaze ?: glance
    val lookX by animateFloatAsState(target.x, spring(dampingRatio = 0.75f, stiffness = 300f), label = "lookX")
    val lookY by animateFloatAsState(target.y, spring(dampingRatio = 0.75f, stiffness = 300f), label = "lookY")

    // Eye geometry eases between moods.
    val l = spec(mood, true); val r = spec(mood, false)
    val eyeSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 400f)
    val lw by animateFloatAsState(l.width, eyeSpring, label = "lw"); val lh by animateFloatAsState(l.height, eyeSpring, label = "lh"); val lt by animateFloatAsState(l.tilt, eyeSpring, label = "lt")
    val rw by animateFloatAsState(r.width, eyeSpring, label = "rw"); val rh by animateFloatAsState(r.height, eyeSpring, label = "rh"); val rt by animateFloatAsState(r.tilt, eyeSpring, label = "rt")
    val closedness by animateFloatAsState(if (blink) 0.12f else 1f, tween(70), label = "blink")

    // One-off movements.
    val squash = remember { Animatable(1f) }; val lift = remember { Animatable(0f) }
    val turn = remember { Animatable(0f) }; val shift = remember { Animatable(0f) }; val sway = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reaction) {
        if (reaction == 0) return@LaunchedEffect
        fun s(t: Float) = spring<Float>(dampingRatio = 0.6f, stiffness = t)
        when (reactionKind) {
            BotReaction.HOP -> {
                scope.launch { squash.animateTo(1.14f, s(1500f)); squash.animateTo(0.92f, s(900f)); squash.animateTo(1.04f, s(900f)); squash.animateTo(1f, s(500f)) }
                delay(100); lift.animateTo(-0.16f, s(700f)); lift.animateTo(0f, s(500f))
            }
            BotReaction.JUMP -> {
                scope.launch { squash.animateTo(1.12f, s(1500f)); squash.animateTo(0.94f, s(1000f)); squash.animateTo(1.12f, s(1000f)); squash.animateTo(0.92f, s(900f)); squash.animateTo(1f, s(500f)) }
                delay(90); lift.animateTo(-0.12f, s(900f)); lift.animateTo(0f, s(1000f)); lift.animateTo(-0.22f, s(700f)); lift.animateTo(0f, s(500f))
            }
            BotReaction.SPIN -> {
                scope.launch { lift.animateTo(-0.1f, s(600f)); lift.animateTo(0f, s(500f)) }
                turn.snapTo(0f); turn.animateTo(360f, tween(600)); turn.snapTo(0f)
                squash.animateTo(1.1f, s(1200f)); squash.animateTo(1f, s(500f))
            }
            BotReaction.SHAKE -> for (v in listOf(-0.06f, 0.06f, -0.045f, 0.03f, 0f)) shift.animateTo(v, tween(80, easing = LinearEasing))
            BotReaction.NOD -> for (v in listOf(0.9f, 1f, 0.92f, 1f)) squash.animateTo(v, s(1200f))
            BotReaction.WIGGLE -> for (v in listOf(-11f, 10f, -7f, 4f, 0f)) sway.animateTo(v, tween(110))
        }
    }

    val breathe = if (lively) sin(time * 1.7).toFloat() else 0f
    Canvas(
        modifier.size(size).graphicsLayer {
            val px = this.size.width
            val sq = squash.value
            // Breathing, then the current movement: squash keeps the base on the ground.
            scaleX = (1 - breathe * 0.008f) * sq
            scaleY = (1 + breathe * 0.014f) * (2 - sq)
            translationY = -breathe * px * 0.012f + lift.value * px
            translationX = shift.value * px
            rotationZ = lookX * 4 + turn.value + sway.value
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    ) {
        val w = this.size.width; val h = this.size.height
        val outline = botBody(shape, w, h)
        drawPath(outline, body)
        // Clay gloss: a soft light from the top left, a little weight bottom right.
        drawPath(outline, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0f), Color.Black.copy(alpha = 0.12f)), Offset.Zero, Offset(w, h)))
        clipPath(outline) {
            rotate(-28f, Offset(w * 0.3f, h * 0.3f)) {
                drawOval(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)), Offset(w * 0.3f, h * 0.3f), w * 0.16f),
                    topLeft = Offset(w * 0.3f - w * 0.17f, h * 0.3f - h * 0.09f), size = Size(w * 0.34f, h * 0.18f),
                )
            }
        }
        // Eyes, shifted by the gaze and sitting where each shape keeps its face.
        val cx = w / 2 + lookX * w * 0.075f
        val cy = h / 2 + lookY * h * (if (shape == BotShape.CAPSULE) 0.035f else 0.06f) + h * shape.faceY
        val gap = w * 0.12f
        drawEye(l.kind, Offset(cx - gap / 2 - w * 0.07f, cy), lw * w, lh * h, lt, closedness, eyeColor, w)
        drawEye(r.kind, Offset(cx + gap / 2 + w * 0.07f, cy), rw * w, rh * h, rt, closedness, eyeColor, w)
    }
}

private fun DrawScope.drawEye(kind: EyeKind, c: Offset, ew: Float, eh: Float, tilt: Float, closed: Float, color: Color, size: Float) {
    rotate(tilt, c) {
        when (kind) {
            EyeKind.PILL -> {
                val hh = eh * closed
                drawRoundRect(color, Offset(c.x - ew / 2, c.y - hh / 2), Size(ew, hh), CornerRadius(minOf(ew, hh) / 2))
            }
            EyeKind.HAPPY, EyeKind.CLOSED -> {
                val up = kind == EyeKind.HAPPY
                val top = if (up) c.y - eh / 2 else c.y + eh / 2
                val bottom = if (up) c.y + eh / 2 else c.y - eh / 2
                val path = Path().apply { moveTo(c.x - ew / 2, bottom); quadraticTo(c.x, top - (bottom - top) * 0.9f, c.x + ew / 2, bottom) }
                drawPath(path, color, style = Stroke(size * 0.045f, cap = StrokeCap.Round))
            }
            EyeKind.HEART -> {
                fun p(x: Float, y: Float) = Offset(c.x - ew / 2 + x * ew, c.y - eh / 2 + y * eh)
                val path = Path().apply {
                    p(0.5f, 0.95f).let { moveTo(it.x, it.y) }
                    listOf(
                        Triple(p(0.28f, 0.78f), p(0.02f, 0.6f), p(0.02f, 0.32f)), Triple(p(0.02f, 0.02f), p(0.42f, 0f), p(0.5f, 0.2f)),
                        Triple(p(0.58f, 0f), p(0.98f, 0.02f), p(0.98f, 0.32f)), Triple(p(0.98f, 0.6f), p(0.72f, 0.78f), p(0.5f, 0.95f)),
                    ).forEach { (a, b, e) -> cubicTo(a.x, a.y, b.x, b.y, e.x, e.y) }
                    close()
                }
                drawPath(path, color)
            }
            EyeKind.CROSS -> {
                val half = eh / 2 * 0.71f
                drawLine(color, Offset(c.x - half, c.y - half), Offset(c.x + half, c.y + half), size * 0.035f, StrokeCap.Round)
                drawLine(color, Offset(c.x - half, c.y + half), Offset(c.x + half, c.y - half), size * 0.035f, StrokeCap.Round)
            }
            EyeKind.LID -> {
                // A half-closed eye: flat on top, round underneath.
                val left = c.x - ew / 2; val right = c.x + ew / 2; val top = c.y - eh / 2; val bottom = c.y + eh / 2
                val rr = eh * 0.22f
                val path = Path().apply {
                    moveTo(left + rr, top); lineTo(right - rr, top)
                    quadraticTo(right, top, right, top + rr)
                    cubicTo(right, top + (bottom - top) * 1.25f, left, top + (bottom - top) * 1.25f, left, top + rr)
                    quadraticTo(left, top, left + rr, top)
                    close()
                }
                drawPath(path, color)
            }
        }
    }
}

