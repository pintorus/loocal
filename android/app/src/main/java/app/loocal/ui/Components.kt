package app.loocal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.loocal.ui.theme.geist
import app.loocal.ui.theme.ink
import kotlinx.coroutines.delay

/** iOS `PressStyle`: a small squeeze and dim while pressed, released with a spring. No ripple. */
fun Modifier.pressable(scale: Float = 0.96f, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "press")
    this
        .graphicsLayer { scaleX = s; scaleY = s; alpha = if (pressed) 0.8f else 1f }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** A soft band of ink sweeping across text while something is under way ("Thinking"). */
fun Modifier.shimmer(active: Boolean = true): Modifier = composed {
    if (!active) return@composed this
    val time = rememberClock()
    val primary = ink.primary
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // 1.1 s sweep, then a short rest, so it breathes rather than strobes.
            val t = ((time % 1.6) / 1.1).coerceAtMost(1.0).toFloat()
            val band = maxOf(40.dp.toPx(), size.width * 0.45f)
            val x = -band + t * (size.width + band)
            drawRect(
                Brush.horizontalGradient(listOf(primary.copy(alpha = 0f), primary, primary.copy(alpha = 0f)), startX = x, endX = x + band),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** Behind the prompt bar and History's search: the page fades into the paper. */
fun Modifier.bottomFade(): Modifier = composed {
    val paper = ink.paper
    drawWithContent {
        val top = -28.dp.toPx()
        drawRect(
            Brush.verticalGradient(
                0f to paper.copy(alpha = 0f), 0.35f to paper.copy(alpha = 0.75f), 1f to paper.copy(alpha = 0.97f),
                startY = top, endY = size.height,
            ),
            topLeft = Offset(0f, top), size = size.copy(height = size.height - top),
        )
        drawContent()
    }
}

/** iOS `SectionLabel`: small muted Geist medium. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = geist(13, androidx.compose.ui.text.font.FontWeight.Medium, ink.secondary), modifier = modifier.fillMaxWidth())
}

/** Errors drop in from the top instead of blocking the screen; swipe up or tap to dismiss. */
@Composable
fun Toast(message: String?, onDismiss: () -> Unit) {
    LaunchedEffect(message) { if (message != null) { delay(6000); onDismiss() } }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn() + scaleIn(initialScale = 0.96f),
        exit = slideOutVertically { -it } + fadeOut() + scaleOut(targetScale = 0.96f),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 14.dp).padding(top = 4.dp)
                .fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(22.dp), ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f))
                .background(ink.paper, RoundedCornerShape(22.dp))
                .border(1.dp, ink.hairline, RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -8) onDismiss() } }
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = ink.primary, modifier = Modifier.padding(top = 1.dp).width(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(message.orEmpty(), style = geist(14, color = ink.primary, lineHeight = androidx.compose.ui.unit.TextUnit(19f, androidx.compose.ui.unit.TextUnitType.Sp)))
        }
    }
}

/** A thin rule, iOS `hairline`. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ink.hairline))
}

/** Rise: fade and slide into place once shown, optionally staggered (iOS `.rise`). */
fun Modifier.rise(shown: Boolean, delayMillis: Int = 0, distance: Float = 12f): Modifier = composed {
    val progress by animateFloatAsState(
        if (shown) 1f else 0f,
        androidx.compose.animation.core.tween(550, delayMillis, androidx.compose.animation.core.FastOutSlowInEasing),
        label = "rise",
    )
    graphicsLayer { alpha = progress; translationY = (1 - progress) * distance * density }
}

