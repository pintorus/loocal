package app.loocal.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.loocal.R

/** Monochrome palette, same values as the iOS app's `Ink` (App/Theme.swift). */
@Immutable
data class Ink(
    val paper: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    /** Soft fill for bubbles, the selected tab pill and cards. */
    val fill: Color,
    val hairline: Color,
) {
    companion object {
        val Light = Ink(
            paper = Color(0xFFFFFFFF), primary = Color(0xFF111111), secondary = Color(0xFF6B6B6B),
            tertiary = Color(0xFF9A9A9A), fill = Color(0xFFF2F2F2), hairline = Color(0xFFE8E8E8),
        )
        val Dark = Ink(
            paper = Color(0xFF0A0A0A), primary = Color(0xFFF4F4F4), secondary = Color(0xFF9A9A9A),
            tertiary = Color(0xFF6B6B6B), fill = Color(0xFF1C1C1C), hairline = Color(0xFF262626),
        )
    }
}

val LocalInk = staticCompositionLocalOf { Ink.Light }

/** Shorthand: `ink.primary` anywhere in a composable. */
val ink: Ink @Composable get() = LocalInk.current

@Composable
fun LoocalTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInk provides if (dark) Ink.Dark else Ink.Light, content = content)
}

/** Geist, the interface face, from the bundled variable font (one instance per weight). */
@OptIn(ExperimentalTextApi::class)
private fun geistAt(weight: Int) = Font(
    R.font.geist, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Geist = FontFamily(geistAt(300), geistAt(400), geistAt(500), geistAt(600), geistAt(700))

/** Instrument Serif, for display moments only. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** `.geist(size, weight)` from iOS. Sizes are iOS points, used as sp. */
fun geist(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = Geist, fontSize = size.sp, fontWeight = weight, color = color, lineHeight = lineHeight)

/** `.serif(size, italic)` from iOS, with its -0.4 tracking for titles. */
fun serif(size: Int, italic: Boolean = false, color: Color = Color.Unspecified) =
    TextStyle(fontFamily = InstrumentSerif, fontSize = size.sp, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, color = color, letterSpacing = (-0.4).sp)

enum class Appearance(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
