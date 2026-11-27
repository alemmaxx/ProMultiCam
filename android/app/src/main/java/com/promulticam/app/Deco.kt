package com.promulticam.app

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.TextPaint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* moving decorations drawn over the video: falling petals, see-through bubbles, rain, snow, hearts, confetti, sparkles, soft lights, leaves */
object Deco {
    val ALL = listOf("petals", "sakura", "bubbles", "rain", "snow", "hearts", "confetti", "sparkle", "bokeh", "leaves", "stars", "steam")
    fun name(k: String) = when (k) { "petals" -> Ui.t("Petals", "Kelopak bunga"); "sakura" -> Ui.t("Flowers", "Bunga"); "bubbles" -> Ui.t("Bubbles", "Gelembung")
        "rain" -> Ui.t("Rain", "Hujan"); "snow" -> Ui.t("Snow", "Salji"); "hearts" -> Ui.t("Hearts", "Hati"); "confetti" -> Ui.t("Confetti", "Konfeti")
        "sparkle" -> Ui.t("Sparkle", "Berkilau"); "bokeh" -> Ui.t("Soft lights", "Cahaya lembut"); "leaves" -> Ui.t("Leaves", "Daun gugur"); "stars" -> Ui.t("Stars", "Bintang"); else -> Ui.t("Steam", "Wap panas") }
    fun icon(k: String) = when (k) { "petals" -> "🌸"; "sakura" -> "🌼"; "bubbles" -> "🫧"; "rain" -> "🌧"; "snow" -> "❄️"; "hearts" -> "💗"; "confetti" -> "🎉"; "sparkle" -> "✨"; "bokeh" -> "🔆"; "leaves" -> "🍂"; "stars" -> "⭐"; else -> "♨️" }

    private fun h(i: Int, k: Int): Float { var x = i * 374761393 + k * 668265263; x = (x xor (x ushr 13)) * 1274126177; return ((x xor (x ushr 16)) and 0xFFFF) / 65535f }   // steady random 0..1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val petal = Path()

    /* density: 1 = normal (layer size makes it more or less) */
    fun draw(cv: Canvas, kind: String, t: Long, W: Int, H: Int, alpha: Float, density: Float) {
        val sec = t / 1e6f; val m = minOf(W, H).toFloat(); val a = (alpha * 255).toInt().coerceIn(0, 255)
        fun count(base: Int) = (base * density).toInt().coerceIn(3, base * 4)
        p.shader = null; p.maskFilter = null; p.style = Paint.Style.FILL
        when (kind) {
            "rain" -> { p.strokeWidth = m * 0.004f; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                for (i in 0 until count(70)) { val sp = 1.6f + h(i, 1) * 1.2f; val len = m * (0.05f + h(i, 2) * 0.06f)
                    val y = ((h(i, 3) + sec * sp) % 1.2f - 0.1f) * H; val x = h(i, 4) * (W + H * 0.2f) - (y * 0.15f)
                    p.color = Color.WHITE; p.alpha = (a * (0.25f + h(i, 5) * 0.35f)).toInt(); cv.drawLine(x, y, x - len * 0.15f, y + len, p) }
                p.style = Paint.Style.FILL; p.color = 0xFF9FB8D0.toInt(); p.alpha = (a * 0.10f).toInt(); cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
            "snow" -> for (i in 0 until count(60)) { val sp = 0.08f + h(i, 1) * 0.12f; val r = m * (0.004f + h(i, 2) * 0.01f)
                val y = ((h(i, 3) + sec * sp) % 1.1f - 0.05f) * H; val x = h(i, 4) * W + sin(sec * (0.8f + h(i, 5)) + i) * m * 0.03f
                p.color = Color.WHITE; p.alpha = (a * (0.5f + h(i, 6) * 0.5f)).toInt(); cv.drawCircle(x, y, r, p) }
            "bubbles" -> for (i in 0 until count(22)) { val sp = 0.06f + h(i, 1) * 0.1f; val r = m * (0.02f + h(i, 2) * 0.05f)
                val y = H - ((h(i, 3) + sec * sp) % 1.2f - 0.1f) * H; val x = h(i, 4) * W + sin(sec * 1.3f + i * 2f) * m * 0.03f
                p.style = Paint.Style.FILL; p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.2f, intArrayOf(0x00FFFFFF, 0x10FFFFFF, 0x55BFE8FF), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
                p.alpha = a; cv.drawCircle(x, y, r, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = m * 0.003f; p.color = Color.WHITE; p.alpha = (a * 0.55f).toInt(); cv.drawCircle(x, y, r, p)
                p.style = Paint.Style.FILL; p.alpha = (a * 0.8f).toInt(); cv.drawCircle(x - r * 0.4f, y - r * 0.4f, r * 0.15f, p) }
            "bokeh" -> { p.maskFilter = BlurMaskFilter(m * 0.03f, BlurMaskFilter.Blur.NORMAL)
                val cols = intArrayOf(0xFFFFD27A.toInt(), 0xFFFF9EC7.toInt(), 0xFFFFFFFF.toInt(), 0xFFA7D8FF.toInt())
                for (i in 0 until count(14)) { val r = m * (0.04f + h(i, 2) * 0.08f)
                    val x = (h(i, 4) + sec * 0.02f * (h(i, 1) - 0.5f)) % 1f * W; val y = (h(i, 3) + sin(sec * 0.3f + i) * 0.03f) * H
                    p.color = cols[i % cols.size]; p.alpha = (a * (0.15f + 0.2f * (0.5f + 0.5f * sin(sec * 1.2f + i)))).toInt(); cv.drawCircle(x, y, r, p) }
                p.maskFilter = null }
            "confetti" -> { val cols = intArrayOf(0xFFFF4D6D.toInt(), 0xFFFFC93C.toInt(), 0xFF3DDC97.toInt(), 0xFF4DA3FF.toInt(), 0xFFB57CFF.toInt())
                for (i in 0 until count(50)) { val sp = 0.18f + h(i, 1) * 0.2f; val w = m * (0.01f + h(i, 2) * 0.012f)
                    val y = ((h(i, 3) + sec * sp) % 1.1f - 0.05f) * H; val x = h(i, 4) * W + sin(sec * 2f + i) * m * 0.04f
                    cv.save(); cv.translate(x, y); cv.rotate(sec * 200f * (h(i, 5) - 0.5f) + i * 40f); cv.scale(1f, cos(sec * 5f + i).toFloat())
                    p.color = cols[i % cols.size]; p.alpha = a; cv.drawRect(-w, -w * 0.5f, w, w * 0.5f, p); cv.restore() } }
            "petals" -> for (i in 0 until count(26)) { val sp = 0.07f + h(i, 1) * 0.08f; val s = m * (0.018f + h(i, 2) * 0.02f)
                val y = ((h(i, 3) + sec * sp) % 1.15f - 0.08f) * H; val x = h(i, 4) * W + sin(sec * 0.9f + i * 1.7f) * m * 0.07f
                petal.reset(); petal.moveTo(0f, -s); petal.cubicTo(s * 0.9f, -s * 0.6f, s * 0.7f, s * 0.7f, 0f, s); petal.cubicTo(-s * 0.7f, s * 0.7f, -s * 0.9f, -s * 0.6f, 0f, -s)
                cv.save(); cv.translate(x, y); cv.rotate(sec * 60f * (h(i, 5) - 0.5f) * 2 + i * 33f); cv.scale(1f, 0.6f + 0.4f * cos(sec * 2f + i).toFloat())
                p.color = if (i % 3 == 0) 0xFFFFC1D9.toInt() else 0xFFFF9EC4.toInt(); p.alpha = (a * 0.9f).toInt(); cv.drawPath(petal, p); cv.restore() }
            "sparkle" -> for (i in 0 until count(30)) { val tw = 0.5f + 0.5f * sin(sec * (2f + h(i, 1) * 3f) + i * 2f); if (tw < 0.2f) continue
                val x = h(i, 4) * W; val y = h(i, 3) * H; val r = m * (0.008f + h(i, 2) * 0.018f) * tw
                p.color = Color.WHITE; p.alpha = (a * tw).toInt(); val pa = Path(); pa.moveTo(x, y - r * 2); pa.quadTo(x, y, x + r * 2, y); pa.quadTo(x, y, x, y + r * 2); pa.quadTo(x, y, x - r * 2, y); pa.quadTo(x, y, x, y - r * 2); cv.drawPath(pa, p)
                p.alpha = (a * tw * 0.35f).toInt(); cv.drawCircle(x, y, r * 1.2f, p) }
            "steam" -> { p.maskFilter = BlurMaskFilter(m * 0.04f, BlurMaskFilter.Blur.NORMAL)
                for (i in 0 until count(10)) { val q = (h(i, 3) + sec * (0.12f + h(i, 1) * 0.08f)) % 1f
                    val x = (0.3f + h(i, 4) * 0.4f) * W + sin(sec * 1.5f + i) * m * 0.05f * q; val y = H * (0.75f - q * 0.6f); val r = m * (0.05f + q * 0.1f)
                    p.color = Color.WHITE; p.alpha = (a * 0.22f * sin(q * PI.toFloat())).toInt(); cv.drawCircle(x, y, r, p) }
                p.maskFilter = null }
            else -> {   // emoji that fall or float: flowers, hearts, leaves, stars
                val (em, up) = when (kind) { "sakura" -> listOf("🌸", "🌼", "🌺") to false; "hearts" -> listOf("💗", "💖", "❤️") to true; "leaves" -> listOf("🍂", "🍁", "🍃") to false; else -> listOf("⭐", "🌟", "✨") to false }
                for (i in 0 until count(16)) { val sp = 0.07f + h(i, 1) * 0.08f; val q = (h(i, 3) + sec * sp) % 1.15f - 0.08f
                    val y = if (up) H - q * H else q * H; val x = h(i, 4) * W + sin(sec * 1.1f + i * 1.3f) * m * 0.06f
                    tp.textSize = m * (0.04f + h(i, 2) * 0.05f); tp.alpha = (a * if (up) (1f - q).coerceIn(0f, 1f) else 1f).toInt()
                    cv.save(); cv.translate(x, y); cv.rotate(sin(sec * 1.5f + i) * 25f); cv.drawText(em[i % em.size], 0f, tp.textSize * 0.35f, tp); cv.restore() }
            }
        }
        p.alpha = 255; p.shader = null; p.maskFilter = null
    }
}
