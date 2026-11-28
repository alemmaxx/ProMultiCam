package com.promulticam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.hypot

/* a small moving example of a setting: how the switch effect, auto-switch or recording fade will look */
class DemoView(c: Context) : View(c) {
    var mode = "trans"; var trans = "fade"; var auto = 0; var fi = 0f; var fo = 0f
    private val t0 = SystemClock.uptimeMillis()
    private val cols = listOf(0xFFE07B39.toInt() to 0xFF5A2A10.toInt(), 0xFF2D8F8A.toInt() to 0xFF0E2B33.toInt(), 0xFF7A55C8.toInt() to 0xFF221438.toInt())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val clip = Path()

    private fun cam(cv: Canvas, k: Int, l: Float, t: Float, r: Float, b: Float, a: Float = 1f) {
        val (c1, c2) = cols[k % cols.size]; p.alpha = 255; p.shader = LinearGradient(l, t, r, b, c1, c2, Shader.TileMode.CLAMP); p.alpha = (a * 255).toInt()
        cv.drawRect(l, t, r, b, p); p.shader = null
        tp.alpha = (a * 255).toInt(); tp.textSize = (b - t) * 0.26f; cv.drawText("📷 " + (k + 1), (l + r) / 2, (t + b) / 2 + tp.textSize * 0.35f, tp)
    }
    private fun cover(cv: Canvas, col: Int, a: Float) { if (a <= 0f) return; p.shader = null; p.color = col; p.alpha = (a.coerceIn(0f, 1f) * 255).toInt(); cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p); p.alpha = 255 }

    override fun onDraw(cv: Canvas) {
        val W = width.toFloat(); val H = height.toFloat(); val now = (SystemClock.uptimeMillis() - t0) / 1000f
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, W, H), H * 0.08f, H * 0.08f, Path.Direction.CW); cv.save(); cv.clipPath(clip)
        when (mode) {
            "auto" -> {
                if (auto <= 0) { cam(cv, 0, 0f, 0f, W, H); caption(cv, Ui.t("You switch by tapping", "Tukar dengan tekan sendiri")) }
                else { val per = 0.9f + auto * 0.1f; val k = (now / per).toInt(); val q = (now % per) / per
                    cam(cv, k % 3, 0f, 0f, W, H); p.color = Ui.GOLD; p.alpha = 255; cv.drawRect(0f, H - H * 0.04f, W * q, H, p)
                    caption(cv, Ui.t("Every ${auto}s", "Setiap ${auto}s")) }
            }
            "fade" -> {   // a recording of about 4 seconds: starts and ends as chosen
                val len = 4f; val x = now % (len + 0.8f)
                if (x > len) { cover(cv, Color.BLACK, 1f); caption(cv, "■ " + Ui.t("Stopped", "Berhenti")) }
                else { cam(cv, 0, 0f, 0f, W, H)
                    var d = 0f; if (fi > 0) d = maxOf(d, 1 - x / (fi * 0.8f)); if (fo > 0) d = maxOf(d, 1 - (len - x) / (fo * 0.8f)); cover(cv, Color.BLACK, d)
                    caption(cv, "● REC " + String.format(java.util.Locale.US, "0:%02d", x.toInt())) }
            }
            else -> {   // switch effect: camera 1 → 2 → 3, a switch every 1.6 s
                val per = 1.6f; val k = (now / per).toInt(); val x = now % per; val dur = 0.55f
                val old = k % 3; val new = (k + 1) % 3
                if (x < per - dur || trans == "none") { cam(cv, if (x < per - dur) old else new, 0f, 0f, W, H) }
                else draw(cv, old, new, ((x - (per - dur)) / dur).coerceIn(0f, 1f), W, H, if (trans == "auto") AUTO[(k * 7) % AUTO.size] else trans)
                caption(cv, if (trans == "auto") "🎲 Auto · " + Fx.transName(AUTO[(k * 7) % AUTO.size]) else Fx.transName(trans))
            }
        }
        cv.restore(); postInvalidateOnAnimation()
    }
    private fun caption(cv: Canvas, s: String) { tp.alpha = 230; tp.textSize = height * 0.1f; tp.setShadowLayer(4f, 0f, 1f, Color.BLACK); cv.drawText(s, width / 2f, height * 0.9f, tp); tp.clearShadowLayer() }

    /* the same switches as the recording (old picture over the new one) */
    private val AUTO = listOf("fade", "black", "white", "flash", "slide", "slideup", "zoom", "whip", "wipe", "circle", "doors", "leak", "bands", "reveal", "tilt")
    private fun draw(cv: Canvas, a: Int, b: Int, p0: Float, W: Float, H: Float, trans: String) {
        val e = p0 * p0 * (3 - 2 * p0)
        cam(cv, b, 0f, 0f, W, H)
        when (trans) {
            "black", "white" -> { if (p0 < 0.5f) cam(cv, a, 0f, 0f, W, H); cover(cv, if (trans == "white") Color.WHITE else Color.BLACK, if (p0 < 0.5f) p0 * 2 else (1 - p0) * 2) }
            "flash" -> { if (p0 < 0.15f) cam(cv, a, 0f, 0f, W, H); cover(cv, Color.WHITE, if (p0 < 0.15f) p0 / 0.15f else (1 - p0) / 0.85f) }
            "slide" -> cam(cv, a, -e * W, 0f, W - e * W, H)
            "slideup" -> cam(cv, a, 0f, -e * H, W, H - e * H)
            "zoom" -> { val s = 1 + e * 0.6f; val w = W * s; val h = H * s; cam(cv, a, (W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2, 1 - e) }
            "whip" -> { val q = e * e; cam(cv, a, -q * W * 1.2f, 0f, W - q * W * 1.2f, H, 1 - q * 0.5f) }
            "wipe" -> { cv.save(); cv.clipRect(e * W, 0f, W, H); cam(cv, a, 0f, 0f, W, H); cv.restore() }
            "circle" -> { cv.save(); val pa = Path(); pa.fillType = Path.FillType.EVEN_ODD; pa.addRect(0f, 0f, W, H, Path.Direction.CW)
                pa.addCircle(W / 2, H / 2, e * hypot(W / 2, H / 2), Path.Direction.CW); cv.clipPath(pa); cam(cv, a, 0f, 0f, W, H); cv.restore() }
            "doors" -> { val d = e * W / 2; cv.save(); cv.clipRect(0f, 0f, W / 2 - d, H); cam(cv, a, -d, 0f, W - d, H); cv.restore()
                cv.save(); cv.clipRect(W / 2 + d, 0f, W, H); cam(cv, a, d, 0f, W + d, H); cv.restore() }
            "leak" -> { cam(cv, a, 0f, 0f, W, H, 1 - e); cover(cv, 0xFFFF9E40.toInt(), (1 - kotlin.math.abs(2 * p0 - 1)) * 0.75f) }
            "bands" -> for (i in 0..2) { val q = ((e * 1.6f) - i * 0.3f).coerceIn(0f, 1f); val off = (if (i % 2 == 0) -1 else 1) * q * W; val y = i * H / 3
                cv.save(); cv.clipRect(0f, y, W, y + H / 3); cam(cv, a, off, 0f, W + off, H); cv.restore() }
            "reveal" -> { val h = (1 - e) * H / 2; cv.save(); cv.clipRect(0f, 0f, W, h); cam(cv, a, 0f, 0f, W, H); cv.restore(); cv.save(); cv.clipRect(0f, H - h, W, H); cam(cv, a, 0f, 0f, W, H); cv.restore() }
            "tilt" -> { cv.save(); cv.rotate(-14f * e, W / 2, H / 2); val s = 1 + e * 0.35f; val w = W * s; val h = H * s; cam(cv, a, (W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2, 1 - e); cv.restore() }
            else -> cam(cv, a, 0f, 0f, W, H, 1 - p0)   // fade
        }
    }
}
