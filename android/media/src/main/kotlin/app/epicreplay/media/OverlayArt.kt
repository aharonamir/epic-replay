package app.epicreplay.media

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.epicreplay.core.compose.OverlayPlan
import app.epicreplay.core.compose.OverlaySkin
import app.epicreplay.core.compose.OverlaySpec
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** The faces of the overlays: the display face in its heavy italic and bold weights, and the data face (the shared fonts as TrueType, made at build time: spike S2, `woff2` does not load on Android). */
class OverlayFonts(val heavyItalic: Typeface, val bold: Typeface, val data: Typeface) {
    companion object {
        fun load(assets: AssetManager): OverlayFonts = OverlayFonts(
            Typeface.createFromAsset(assets, "fonts/sofia-sans-extra-condensed-latin-900-italic.ttf"),
            Typeface.createFromAsset(assets, "fonts/sofia-sans-extra-condensed-latin-700-normal.ttf"),
            Typeface.createFromAsset(assets, "fonts/jetbrains-mono-latin-400-normal.ttf"),
        )
    }
}

/**
 * The overlays of S10 drawn with Canvas from the pack's graphics fields (accent, accent text, skin) at the sizes and places of the PC's HTML template
 * (`graphics/overlay.html`): every size is a share of the shorter side of the frame, so a vertical video looks as a landscape one. Mandarin uses the system's
 * Simplified Chinese face in bold with no capitals, italics or letter spacing (the PC's own rule).
 */
object OverlayArt {
    private const val INK = "#F2F4F5"

    private fun paint(face: Typeface, size: Float, color: String, spacing: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = size; this.color = Color.parseColor(color); letterSpacing = spacing
    }

    private fun layout(text: String, p: TextPaint, maxWidth: Int, lineHeight: Float): StaticLayout {
        val natural = p.fontMetrics.let { it.descent - it.ascent }
        val widest = text.split('\n').maxOf { StaticLayout.getDesiredWidth(it, p) }
        val width = Math.min(maxWidth.toFloat(), Math.ceil(widest.toDouble()).toFloat()).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, lineHeight * p.textSize / natural).setIncludePad(false).build()
    }

    private fun box(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) { c.drawRect(x, y, x + w, y + h, Paint().apply { this.color = color }) }
    private fun argb(a: Double, r: Int, g: Int, b: Int) = Color.argb(Math.round(a * 255).toInt(), r, g, b)

    fun render(spec: OverlaySpec, graphics: JsonObject, width: Int, height: Int, zh: Boolean, fonts: OverlayFonts): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = Math.min(width, height).toFloat(); val w = width.toFloat(); val h = height.toFloat()
        val accent = graphics.getValue("accent").jsonPrimitive.content
        val accentText = (graphics["accent_text"] as? JsonPrimitive)?.content?.ifEmpty { null } ?: accent
        val onAccent = OverlayPlan.onAccent(accent)
        val skin = OverlaySkin.of((graphics["skin"] as? JsonPrimitive)?.content ?: "")
        val zhFace = Typeface.create("sans-serif", Typeface.BOLD)
        val display = if (zh) zhFace else if (skin.heavyItalic) fonts.heavyItalic else fonts.bold
        val data = if (zh) zhFace else fonts.data
        val spacing = if (zh) 0f else skin.letterSpacingEm
        fun caps(s: String) = if (zh) s else s.uppercase(Locale.ROOT)
        fun tp(face: Typeface, size: Float, color: String, sp: Float) = paint(face, size, color, sp).also { if (zh) it.textLocale = Locale.SIMPLIFIED_CHINESE }
        fun withBox(l: StaticLayout, x: Float, y: Float, padH: Float, padV: Float, bg: Int, borderBottom: Float = 0f, borderLeft: Float = 0f) {
            val bw = l.width + 2 * padH + borderLeft; val bh = l.height + 2 * padV + borderBottom
            box(c, x, y, bw, bh, bg)
            if (borderBottom > 0) box(c, x, y + bh - borderBottom, bw, borderBottom, Color.parseColor(accent))
            if (borderLeft > 0) box(c, x, y, borderLeft, bh, Color.parseColor(accent))
            c.save(); c.translate(x + padH + borderLeft, y + padV); l.draw(c); c.restore()
        }
        when (spec.kind) {
            "title" -> {
                val padH = u * 0.035f; val padV = u * 0.02f; val border = u * 0.01f
                val l = layout(caps(spec.text.getValue("headline")), tp(display, u * 0.1f, INK, spacing), (w * 0.92f - 2 * padH).toInt(), 1.02f)
                val bw = l.width + 2 * padH; val bh = l.height + 2 * padV + border
                withBox(l, (w - bw) / 2, (h - bh) / 2, padH, padV, argb(0.62, 10, 12, 14), borderBottom = border)
            }
            "replay_bug" -> {
                val padH = u * 0.022f; val padV = u * 0.008f
                val l = layout(caps(spec.text.getValue("label")), tp(display, u * 0.045f, onAccent, spacing), (w * 0.7f - 2 * padH).toInt(), 1.02f)
                withBox(l, w * 0.04f, h * 0.05f, padH, padV, Color.parseColor(accent))
            }
            "end_card" -> {
                box(c, 0f, 0f, w, h, argb(0.55, 8, 10, 12))
                val gap = u * 0.02f
                val kicker = spec.text["kicker"]?.takeIf { it.isNotEmpty() }?.let { layout(it, tp(data, u * 0.035f, accentText, 0.18f), (w * 0.92f).toInt(), 1.2f) }
                val head = spec.text["headline"]?.takeIf { it.isNotEmpty() }?.let { layout(caps(it), tp(display, u * 0.1f, INK, spacing), (w * 0.92f).toInt(), 1.02f) }
                val ruleH = u * 0.008f
                val parts = listOfNotNull(kicker, head)
                val total = parts.sumOf { it.height.toDouble() }.toFloat() + ruleH + gap * parts.size
                var y = (h - total) / 2
                for (l in parts) { c.save(); c.translate((w - l.width) / 2, y); l.draw(c); c.restore(); y += l.height + gap }
                box(c, w * 0.5f - w * 0.11f, y, w * 0.22f, ruleH, Color.parseColor(accent))
            }
            "ai_label" -> {
                val padH = u * 0.016f; val padV = u * 0.007f; val left = u * 0.006f
                val l = layout(spec.text.getValue("label"), tp(data, u * 0.026f, INK, 0.12f), (w * 0.9f).toInt(), 1.2f)
                val bw = l.width + 2 * padH + left; val bh = l.height + 2 * padV
                withBox(l, w - w * 0.03f - bw, h - h * 0.04f - bh, padH, padV, argb(0.62, 10, 12, 14), borderLeft = left)
            }
        }
        return bmp
    }
}
