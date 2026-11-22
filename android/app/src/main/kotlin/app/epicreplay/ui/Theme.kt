package app.epicreplay.ui

import android.content.res.AssetManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The colours of the design (`specs/001-epic-replay/contracts/design.md` section 2): a near-black ground, and one accent that is the chosen narrator's. */
object Ink {
    val ground = Color(0xFF0D1012); val surface = Color(0xFF161A1D); val raised = Color(0xFF1E2327); val line = Color(0xFF2C3338); val lineStrong = Color(0xFF414A51)
    val controlLine = Color(0xFF5A646B); val text = Color(0xFFF2F4F3); val textSoft = Color(0xFFD9DEE0); val muted = Color(0xFFAAB3B9); val waiting = Color(0xFF7A858D)
    val ink = Color(0xFF121416); val dangerText = Color(0xFFFF7C8A)
}

/** The narrator's accent: the fill (`accent`), what is written on it (`onAccent`) and the colour for small text on the ground (`accentText`). */
class Accent(val fill: Color, val onAccent: Color, val text: Color)

val DefaultAccent = Accent(Color(0xFFFFD23F), Ink.ink, Color(0xFFFFD23F))
val LocalAccent = compositionLocalOf { DefaultAccent }

fun parseColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/** The accent of a pack: its `graphics.accent`, the text on it dark or white by contrast (design.md), and `accent_text` when the pack has one. */
fun accentOf(accent: String, accentText: String?): Accent {
    val fill = parseColor(accent)
    fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    val lum = 0.2126f * lin(fill.red) + 0.7152f * lin(fill.green) + 0.0722f * lin(fill.blue)
    val dark = (lum + 0.05f) / 0.05f > 1.05f / (lum + 0.05f)
    return Accent(fill, if (dark) Ink.ink else Color.White, accentText?.let(::parseColor) ?: fill)
}

/** The three faces of the design, from the shared fonts (as TrueType, made at build time): display, text and data. */
class Faces(val display: FontFamily, val text: FontFamily, val data: FontFamily) {
    companion object {
        fun load(assets: AssetManager): Faces {
            fun f(file: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) = Font("assets/fonts/$file", assets, weight, style)
            return Faces(
                FontFamily(f("sofia-sans-extra-condensed-latin-900-normal.ttf", FontWeight.Black), f("sofia-sans-extra-condensed-latin-700-normal.ttf", FontWeight.Bold)),
                FontFamily(f("sofia-sans-latin-400-normal.ttf", FontWeight.Normal), f("sofia-sans-latin-600-normal.ttf", FontWeight.SemiBold), f("sofia-sans-latin-700-normal.ttf", FontWeight.Bold)),
                FontFamily(f("jetbrains-mono-latin-400-normal.ttf", FontWeight.Normal)),
            )
        }
    }
}

val LocalFaces = compositionLocalOf<Faces?> { null }

/** The dark broadcast look: the design's tokens as a Material colour scheme, the narrator's accent as its primary. */
@Composable
fun EpicTheme(accent: Accent = LocalAccent.current, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val loaded = remember(context) { runCatching { Faces.load(context.assets) }.getOrNull() }
    val scheme = darkColorScheme(
        primary = accent.fill, onPrimary = accent.onAccent, background = Ink.ground, onBackground = Ink.text, surface = Ink.surface, onSurface = Ink.text,
        surfaceVariant = Ink.raised, onSurfaceVariant = Ink.muted, outline = Ink.controlLine, outlineVariant = Ink.line, error = Ink.dangerText,
    )
    val text = loaded?.text ?: FontFamily.Default
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = loaded?.display ?: FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 52.sp),
        headlineMedium = TextStyle(fontFamily = loaded?.display ?: FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 38.sp),
        titleLarge = TextStyle(fontFamily = loaded?.display ?: FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 30.sp),
        titleMedium = TextStyle(fontFamily = text, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        bodyLarge = TextStyle(fontFamily = text, fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = text, fontSize = 15.sp, lineHeight = 21.sp),
        labelSmall = TextStyle(fontFamily = text, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.2.sp),
        labelMedium = TextStyle(fontFamily = loaded?.data ?: FontFamily.Default, fontSize = 14.sp),
    )
    CompositionLocalProvider(LocalAccent provides accent, LocalFaces provides loaded) {
        MaterialTheme(colorScheme = scheme, typography = typography) { androidx.compose.material3.Surface(color = Ink.ground, contentColor = Ink.text, content = content) }
    }
}
