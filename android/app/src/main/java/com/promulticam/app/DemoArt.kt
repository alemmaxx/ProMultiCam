package com.promulticam.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/* Cartoon example pictures for templates, so every template shows a real-looking video before you put your own in.
   All drawn here (no outside pictures): a background, a main object and some decoration, chosen by group and box number. */
object DemoArt {
    private val SETS = mapOf(
        "food" to listOf("plate", "burger", "bowl", "cup", "cake", "wok"),
        "sale" to listOf("bags", "tag", "gift", "phone", "shoe", "shop"),
        "greet" to listOf("lantern", "balloons", "fireworks", "gift", "cake", "moon"),
        "life" to listOf("beach", "hills", "city", "coffee", "park", "road"),
        "music" to listOf("guitar", "mic", "vinyl", "speaker", "stage", "headphones"))
    private val MIX = listOf("plate", "bags", "beach", "guitar", "burger", "lantern", "city", "gift", "cup", "mic", "hills", "phone")

    fun kindFor(cat: String, k: Int): String = (SETS[cat] ?: MIX).let { it[k % it.size] }

    fun draw(cat: String, k: Int, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        paint(c, kindFor(cat, k), k, w.toFloat(), h.toFloat()); return b
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fill(col: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = col }
    private fun line(col: Int, wd: Float) { p.shader = null; p.style = Paint.Style.STROKE; p.color = col; p.strokeWidth = wd; p.strokeCap = Paint.Cap.ROUND }
    private fun grad(c: Canvas, w: Float, h: Float, top: Int, bot: Int) { p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, 0f, 0f, h, top, bot, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p); p.shader = null }
    private fun glow(c: Canvas, x: Float, y: Float, r: Float, col: Int) { p.style = Paint.Style.FILL; p.shader = RadialGradient(x, y, r, col, col and 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawCircle(x, y, r, p); p.shader = null }

    /* ---- backgrounds ---- */
    private fun table(c: Canvas, w: Float, h: Float, k: Int) {
        val walls = intArrayOf(0xFFF3E2C7.toInt(), 0xFFE7F0E4.toInt(), 0xFFF6DCDC.toInt(), 0xFFE3E9F5.toInt())
        grad(c, w, h, walls[k % 4], Color.WHITE)
        val ty = h * 0.58f; grad2(c, 0f, ty, w, h, 0xFFB07A4A.toInt(), 0xFF7A4E2C.toInt())
        line(0x33000000, w * 0.004f); var y = ty + h * 0.06f; while (y < h) { c.drawLine(0f, y, w, y, p); y += h * 0.07f }
        glow(c, w * 0.8f, h * 0.15f, w * 0.5f, 0x55FFF4D6)
    }
    private fun grad2(c: Canvas, l: Float, t: Float, r: Float, b: Float, a: Int, z: Int) { p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, t, 0f, b, a, z, Shader.TileMode.CLAMP); c.drawRect(l, t, r, b, p); p.shader = null }
    private fun studio(c: Canvas, w: Float, h: Float, a: Int, z: Int) { grad(c, w, h, a, z); glow(c, w / 2, h * 0.45f, w * 0.75f, 0x66FFFFFF); fill(0x22000000); c.drawOval(w * 0.18f, h * 0.74f, w * 0.82f, h * 0.8f, p) }
    private fun night(c: Canvas, w: Float, h: Float, k: Int) {
        grad(c, w, h, 0xFF0B1033.toInt(), 0xFF3A1F5C.toInt()); val r = Random(k * 31 + 7); fill(Color.WHITE)
        repeat(60) { c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.7f, w * (0.002f + r.nextFloat() * 0.004f), p) }
    }
    private fun bokeh(c: Canvas, w: Float, h: Float, k: Int, cols: IntArray) { val r = Random(k * 17 + 3); repeat(18) { glow(c, r.nextFloat() * w, r.nextFloat() * h, w * (0.05f + r.nextFloat() * 0.1f), cols[it % cols.size] and 0x66FFFFFF) } }
    private fun confetti(c: Canvas, w: Float, h: Float, k: Int) { val r = Random(k * 13 + 1); val cols = intArrayOf(0xFFFFC83D.toInt(), 0xFFFF5D73.toInt(), 0xFF4CC9F0.toInt(), 0xFF7BD389.toInt())
        repeat(40) { fill(cols[it % 4]); c.save(); c.rotate(r.nextFloat() * 180f, r.nextFloat() * w, r.nextFloat() * h); val x = r.nextFloat() * w; val y = r.nextFloat() * h; c.drawRect(x, y, x + w * 0.02f, y + w * 0.008f, p); c.restore() } }
    private fun steam(c: Canvas, x: Float, y: Float, s: Float) { line(0x99FFFFFF.toInt(), s * 0.04f); for (i in -1..1) { val path = Path(); path.moveTo(x + i * s * 0.25f, y)
        path.cubicTo(x + i * s * 0.25f - s * 0.15f, y - s * 0.3f, x + i * s * 0.25f + s * 0.15f, y - s * 0.5f, x + i * s * 0.25f, y - s * 0.8f); c.drawPath(path, p) } }

    private fun paint(c: Canvas, kind: String, k: Int, w: Float, h: Float) {
        val m = minOf(w, h); val cx = w / 2; val cy = h * 0.55f
        when (kind) {
            "plate" -> { table(c, w, h, k); fill(0x33000000); c.drawOval(cx - m * 0.42f, cy + m * 0.02f, cx + m * 0.42f, cy + m * 0.26f, p)
                fill(Color.WHITE); c.drawOval(cx - m * 0.4f, cy - m * 0.12f, cx + m * 0.4f, cy + m * 0.2f, p); fill(0xFFF1EDE6.toInt()); c.drawOval(cx - m * 0.3f, cy - m * 0.07f, cx + m * 0.3f, cy + m * 0.15f, p)
                fill(0xFFFFFBF2.toInt()); c.drawCircle(cx - m * 0.06f, cy - m * 0.02f, m * 0.13f, p)   // rice
                fill(0xFFD7352B.toInt()); c.drawOval(cx + m * 0.05f, cy - m * 0.04f, cx + m * 0.22f, cy + m * 0.06f, p)   // sambal
                fill(Color.WHITE); c.drawOval(cx - m * 0.26f, cy + m * 0.02f, cx - m * 0.1f, cy + m * 0.12f, p); fill(0xFFFFC62E.toInt()); c.drawCircle(cx - m * 0.18f, cy + m * 0.07f, m * 0.035f, p)   // egg
                fill(0xFF5BAA4A.toInt()); c.drawOval(cx + m * 0.04f, cy + m * 0.06f, cx + m * 0.2f, cy + m * 0.12f, p); fill(0xFFC98A3D.toInt()); for (i in 0..4) c.drawCircle(cx + m * (0.0f + i * 0.04f), cy - m * 0.08f, m * 0.018f, p)
                steam(c, cx, cy - m * 0.18f, m * 0.35f) }
            "burger" -> { studio(c, w, h, 0xFFFFB547.toInt(), 0xFFFF6B3D.toInt()); val s = m * 0.36f
                fill(0xFFE59A3A.toInt()); c.drawArc(cx - s, cy - s * 0.95f, cx + s, cy + s * 0.25f, 180f, 180f, true, p)
                fill(Color.WHITE); for (i in 0..6) c.drawOval(cx - s * 0.6f + i * s * 0.2f, cy - s * 0.6f, cx - s * 0.54f + i * s * 0.2f, cy - s * 0.54f, p)
                fill(0xFF6BBF3B.toInt()); c.drawRoundRect(cx - s * 1.05f, cy - s * 0.38f, cx + s * 1.05f, cy - s * 0.24f, s * 0.07f, s * 0.07f, p)
                fill(0xFFFFCB2E.toInt()); c.drawRect(cx - s * 0.98f, cy - s * 0.26f, cx + s * 0.98f, cy - s * 0.15f, p)
                fill(0xFF6B3A1E.toInt()); c.drawRoundRect(cx - s, cy - s * 0.17f, cx + s, cy + s * 0.12f, s * 0.12f, s * 0.12f, p)
                fill(0xFFE59A3A.toInt()); c.drawRoundRect(cx - s * 0.95f, cy + s * 0.1f, cx + s * 0.95f, cy + s * 0.38f, s * 0.18f, s * 0.18f, p) }
            "bowl" -> { table(c, w, h, k + 1); val s = m * 0.38f; fill(0xFFE8E2D8.toInt()); c.drawOval(cx - s, cy - s * 0.25f, cx + s, cy + s * 0.15f, p)
                fill(0xFFE3A23A.toInt()); c.drawOval(cx - s * 0.9f, cy - s * 0.2f, cx + s * 0.9f, cy + s * 0.08f, p)
                line(0xFFFFE08A.toInt(), s * 0.04f); for (i in 0..5) { val path = Path(); path.moveTo(cx - s * 0.7f + i * s * 0.25f, cy - s * 0.12f); path.quadTo(cx - s * 0.6f + i * s * 0.25f, cy, cx - s * 0.5f + i * s * 0.25f, cy - s * 0.1f); c.drawPath(path, p) }
                fill(0xFFE8604C.toInt()); c.drawCircle(cx + s * 0.35f, cy - s * 0.1f, s * 0.12f, p); fill(0xFF5BAA4A.toInt()); c.drawCircle(cx - s * 0.35f, cy - s * 0.12f, s * 0.08f, p)
                fill(0xFFC8432B.toInt()); c.drawArc(cx - s, cy - s * 0.8f, cx + s, cy + s * 0.8f, 0f, 180f, true, p); steam(c, cx, cy - s * 0.4f, s) }
            "cup" -> { studio(c, w, h, 0xFF8FD3C9.toInt(), 0xFF3C8D93.toInt()); val s = m * 0.3f
                fill(0xEEFFFFFF.toInt()); val path = Path(); path.moveTo(cx - s * 0.6f, cy - s); path.lineTo(cx + s * 0.6f, cy - s); path.lineTo(cx + s * 0.45f, cy + s * 0.7f); path.lineTo(cx - s * 0.45f, cy + s * 0.7f); path.close(); c.drawPath(path, p)
                fill(0xFFB5764A.toInt()); val q = Path(); q.moveTo(cx - s * 0.55f, cy - s * 0.4f); q.lineTo(cx + s * 0.55f, cy - s * 0.4f); q.lineTo(cx + s * 0.45f, cy + s * 0.65f); q.lineTo(cx - s * 0.45f, cy + s * 0.65f); q.close(); c.drawPath(q, p)
                fill(0xFFFFF4E0.toInt()); c.drawOval(cx - s * 0.55f, cy - s * 0.5f, cx + s * 0.55f, cy - s * 0.3f, p); fill(0xFF3D2A1E.toInt()); for (i in 0..8) c.drawCircle(cx - s * 0.35f + (i % 4) * s * 0.22f, cy + s * 0.45f - (i / 4) * s * 0.12f, s * 0.07f, p)
                line(0xFFFF6B6B.toInt(), s * 0.09f); c.drawLine(cx + s * 0.15f, cy - s * 0.5f, cx + s * 0.45f, cy - s * 1.5f, p) }
            "cake" -> { if (kind == "cake" && k % 2 == 1) night(c, w, h, k) else studio(c, w, h, 0xFFFFD1DC.toInt(), 0xFFF48FB1.toInt()); val s = m * 0.32f
                fill(0xFFF8E1C4.toInt()); c.drawRoundRect(cx - s, cy - s * 0.2f, cx + s, cy + s * 0.6f, s * 0.1f, s * 0.1f, p)
                fill(0xFFE85D8A.toInt()); c.drawRoundRect(cx - s * 0.75f, cy - s * 0.75f, cx + s * 0.75f, cy - s * 0.15f, s * 0.1f, s * 0.1f, p)
                fill(Color.WHITE); for (i in 0..5) c.drawCircle(cx - s * 0.62f + i * s * 0.25f, cy - s * 0.75f, s * 0.09f, p)
                for (i in -1..1) { fill(0xFF7EC8E3.toInt()); c.drawRect(cx + i * s * 0.35f - s * 0.04f, cy - s * 1.15f, cx + i * s * 0.35f + s * 0.04f, cy - s * 0.78f, p); glow(c, cx + i * s * 0.35f, cy - s * 1.22f, s * 0.18f, 0xFFFFD54F.toInt()) }
                confetti(c, w, h, k) }
            "wok" -> { grad(c, w, h, 0xFF2B2B2B.toInt(), 0xFF111111.toInt()); glow(c, cx, cy + m * 0.25f, m * 0.5f, 0xAAFF7A1A.toInt()); val s = m * 0.4f
                fill(0xFF2E2E2E.toInt()); c.drawArc(cx - s, cy - s * 0.6f, cx + s, cy + s * 0.6f, 0f, 180f, true, p); line(0xFF555555.toInt(), s * 0.08f); c.drawLine(cx + s, cy, cx + s * 1.5f, cy - s * 0.2f, p)
                val r = Random(k); val cols = intArrayOf(0xFFE8604C.toInt(), 0xFF6BBF3B.toInt(), 0xFFFFC62E.toInt(), 0xFFF5E6C8.toInt())
                repeat(26) { fill(cols[it % 4]); c.drawCircle(cx + (r.nextFloat() - 0.5f) * s * 1.4f, cy - s * 0.05f - r.nextFloat() * s * 0.5f, s * 0.06f, p) }
                for (i in 0..8) glow(c, cx + (r.nextFloat() - 0.5f) * s * 1.6f, cy + s * 0.55f, s * 0.2f, 0xCCFFB300.toInt()) }
            "bags" -> { studio(c, w, h, 0xFFFFE07A.toInt(), 0xFFFF9E3D.toInt()); val cols = intArrayOf(0xFFE53950.toInt(), 0xFF3D7BE5.toInt(), 0xFF2EB67D.toInt())
                for (i in 0..2) { val s = m * (0.22f + i * 0.03f); val x = cx + (i - 1) * m * 0.26f; val y = cy + (if (i == 1) -m * 0.05f else m * 0.04f)
                    fill(cols[i]); c.drawRoundRect(x - s * 0.7f, y - s * 0.6f, x + s * 0.7f, y + s * 0.8f, s * 0.08f, s * 0.08f, p)
                    line(0xFF222222.toInt(), s * 0.07f); c.drawArc(x - s * 0.35f, y - s * 1.05f, x + s * 0.35f, y - s * 0.15f, 180f, 180f, false, p)
                    fill(Color.WHITE); p.textSize = s * 0.45f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true; c.drawText("%", x, y + s * 0.4f, p) }
                confetti(c, w, h, k) }
            "tag" -> { studio(c, w, h, 0xFFE53950.toInt(), 0xFF8E1230.toInt()); val s = m * 0.36f; c.save(); c.rotate(-12f, cx, cy)
                fill(0xFFFFD23F.toInt()); val path = Path(); path.moveTo(cx - s, cy - s * 0.5f); path.lineTo(cx + s * 0.6f, cy - s * 0.5f); path.lineTo(cx + s, cy); path.lineTo(cx + s * 0.6f, cy + s * 0.5f); path.lineTo(cx - s, cy + s * 0.5f); path.close(); c.drawPath(path, p)
                fill(0xFF8E1230.toInt()); c.drawCircle(cx + s * 0.62f, cy, s * 0.09f, p); p.textSize = s * 0.55f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true; c.drawText("-50%", cx - s * 0.15f, cy + s * 0.2f, p); c.restore()
                bokeh(c, w, h, k, intArrayOf(0xFFFFD23F.toInt(), Color.WHITE)) }
            "gift" -> { if (k % 2 == 0) studio(c, w, h, 0xFF7B5CFF.toInt(), 0xFF2A1A6E.toInt()) else night(c, w, h, k); val s = m * 0.3f
                fill(0xFFE53950.toInt()); c.drawRect(cx - s, cy - s * 0.5f, cx + s, cy + s * 0.8f, p); fill(0xFFF04A63.toInt()); c.drawRect(cx - s * 1.1f, cy - s * 0.8f, cx + s * 1.1f, cy - s * 0.45f, p)
                fill(0xFFFFD23F.toInt()); c.drawRect(cx - s * 0.13f, cy - s * 0.8f, cx + s * 0.13f, cy + s * 0.8f, p)
                c.drawOval(cx - s * 0.6f, cy - s * 1.15f, cx, cy - s * 0.75f, p); c.drawOval(cx, cy - s * 1.15f, cx + s * 0.6f, cy - s * 0.75f, p); confetti(c, w, h, k) }
            "phone" -> { studio(c, w, h, 0xFFE9EEF5.toInt(), 0xFFB8C4D6.toInt()); val s = m * 0.28f
                fill(0x33000000); c.drawOval(cx - s, cy + s * 1.3f, cx + s, cy + s * 1.45f, p)
                fill(0xFF1D1F24.toInt()); c.drawRoundRect(cx - s * 0.6f, cy - s * 1.2f, cx + s * 0.6f, cy + s * 1.2f, s * 0.15f, s * 0.15f, p)
                p.shader = LinearGradient(cx, cy - s, cx, cy + s, 0xFF4CC9F0.toInt(), 0xFF7B5CFF.toInt(), Shader.TileMode.CLAMP); c.drawRoundRect(cx - s * 0.52f, cy - s * 1.1f, cx + s * 0.52f, cy + s * 1.1f, s * 0.1f, s * 0.1f, p); p.shader = null
                fill(0x55FFFFFF); for (i in 0..5) c.drawRoundRect(cx - s * 0.4f + (i % 3) * s * 0.28f, cy - s * 0.8f + (i / 3) * s * 0.3f, cx - s * 0.22f + (i % 3) * s * 0.28f, cy - s * 0.62f + (i / 3) * s * 0.3f, s * 0.04f, s * 0.04f, p) }
            "shoe" -> { studio(c, w, h, 0xFFB9F3E4.toInt(), 0xFF3FB89A.toInt()); val s = m * 0.4f
                fill(Color.WHITE); c.drawRoundRect(cx - s, cy + s * 0.15f, cx + s, cy + s * 0.35f, s * 0.1f, s * 0.1f, p)
                fill(0xFFE53950.toInt()); val path = Path(); path.moveTo(cx - s * 0.95f, cy + s * 0.15f); path.lineTo(cx - s * 0.8f, cy - s * 0.45f); path.lineTo(cx - s * 0.3f, cy - s * 0.5f); path.quadTo(cx, cy - s * 0.1f, cx + s * 0.6f, cy - s * 0.05f); path.quadTo(cx + s, cy, cx + s * 0.98f, cy + s * 0.15f); path.close(); c.drawPath(path, p)
                line(Color.WHITE, s * 0.04f); for (i in 0..3) c.drawLine(cx - s * 0.55f + i * s * 0.12f, cy - s * 0.35f, cx - s * 0.45f + i * s * 0.12f, cy - s * 0.15f, p) }
            "shop" -> { grad(c, w, h, 0xFF87CEEB.toInt(), 0xFFE0F4FF.toInt()); val s = m * 0.45f
                fill(0xFFFFF4E0.toInt()); c.drawRect(cx - s, cy - s * 0.4f, cx + s, cy + s * 0.8f, p)
                for (i in 0..7) { fill(if (i % 2 == 0) 0xFFE53950.toInt() else Color.WHITE); c.drawRect(cx - s + i * s * 0.25f, cy - s * 0.65f, cx - s + (i + 1) * s * 0.25f, cy - s * 0.35f, p) }
                fill(0xFF6BB9E8.toInt()); c.drawRect(cx - s * 0.85f, cy - s * 0.2f, cx - s * 0.1f, cy + s * 0.4f, p); fill(0xFF8B5A2B.toInt()); c.drawRect(cx + s * 0.15f, cy - s * 0.2f, cx + s * 0.7f, cy + s * 0.8f, p)
                fill(0xFF5BAA4A.toInt()); c.drawRect(0f, cy + s * 0.8f, w, h, p) }
            "lantern" -> { night(c, w, h, k); for (i in 0..2) { val x = w * (0.25f + i * 0.25f); val y = h * (0.35f + (i % 2) * 0.12f); val s = m * 0.12f
                glow(c, x, y, s * 2.2f, 0x88FFB347.toInt()); fill(0xFFE53950.toInt()); c.drawOval(x - s, y - s * 1.2f, x + s, y + s * 1.2f, p); fill(0xFFFFD23F.toInt()); c.drawRect(x - s * 0.5f, y - s * 1.35f, x + s * 0.5f, y - s * 1.1f, p); c.drawRect(x - s * 0.5f, y + s * 1.1f, x + s * 0.5f, y + s * 1.35f, p)
                line(0xFFFFD23F.toInt(), s * 0.06f); c.drawLine(x, y - s * 1.35f, x, 0f, p) } }
            "balloons" -> { grad(c, w, h, 0xFF9BE7FF.toInt(), 0xFFFFF1F5.toInt()); val cols = intArrayOf(0xFFE53950.toInt(), 0xFFFFC83D.toInt(), 0xFF4CC9F0.toInt(), 0xFF7BD389.toInt(), 0xFFB57BFF.toInt())
                for (i in 0..4) { val x = w * (0.2f + i * 0.15f); val y = h * (0.3f + (i % 2) * 0.1f); val s = m * 0.11f; line(0x88333333.toInt(), s * 0.04f); c.drawLine(x, y + s * 1.2f, cx, h * 0.8f, p)
                    fill(cols[i]); c.drawOval(x - s, y - s * 1.2f, x + s, y + s * 1.2f, p); fill(0x55FFFFFF); c.drawOval(x - s * 0.6f, y - s * 0.9f, x - s * 0.2f, y - s * 0.3f, p) }
                confetti(c, w, h, k) }
            "fireworks" -> { night(c, w, h, k); val cols = intArrayOf(0xFFFFC83D.toInt(), 0xFFFF5D73.toInt(), 0xFF4CC9F0.toInt())
                for (f in 0..2) { val x = w * (0.25f + f * 0.25f); val y = h * (0.25f + (f % 2) * 0.15f); val s = m * 0.2f; line(cols[f], s * 0.04f)
                    for (a in 0 until 16) { val an = a * Math.PI / 8; c.drawLine(x + (cos(an) * s * 0.3f).toFloat(), y + (sin(an) * s * 0.3f).toFloat(), x + (cos(an) * s).toFloat(), y + (sin(an) * s).toFloat(), p) }
                    glow(c, x, y, s * 0.6f, cols[f] and 0x88FFFFFF.toInt()) }
                fill(0xFF0A0A1A.toInt()); c.drawRect(0f, h * 0.82f, w, h, p) }
            "moon" -> { night(c, w, h, k); glow(c, w * 0.7f, h * 0.25f, m * 0.35f, 0x66FFF3B0); fill(0xFFFFE9A0.toInt()); c.drawCircle(w * 0.7f, h * 0.25f, m * 0.14f, p); fill(0xFF1A1442.toInt()); c.drawCircle(w * 0.75f, h * 0.22f, m * 0.13f, p)
                fill(0xFF141030.toInt()); val s = m * 0.35f; c.drawRect(cx - s, h * 0.65f, cx + s, h, p); c.drawArc(cx - s * 0.6f, h * 0.65f - s * 0.6f, cx + s * 0.6f, h * 0.65f + s * 0.6f, 180f, 180f, true, p)
                c.drawRect(cx - s * 1.2f, h * 0.45f, cx - s * 1.05f, h, p); c.drawRect(cx + s * 1.05f, h * 0.45f, cx + s * 1.2f, h, p) }
            "beach" -> { grad(c, w, h, 0xFFFF9A5A.toInt(), 0xFFFFD6A5.toInt()); fill(0xFFFFE08A.toInt()); c.drawCircle(cx, h * 0.48f, m * 0.18f, p)
                grad2(c, 0f, h * 0.5f, w, h * 0.72f, 0xFF2F7FB8.toInt(), 0xFF5DB2D9.toInt()); fill(0xFFF3D39B.toInt()); c.drawRect(0f, h * 0.72f, w, h, p)
                line(0x99FFFFFF.toInt(), m * 0.008f); for (i in 0..4) c.drawLine(w * 0.1f + i * w * 0.18f, h * (0.56f + i * 0.03f), w * 0.2f + i * w * 0.18f, h * (0.56f + i * 0.03f), p)
                line(0xFF5A3A1E.toInt(), m * 0.03f); c.drawLine(w * 0.15f, h * 0.8f, w * 0.22f, h * 0.45f, p); fill(0xFF2E8B57.toInt()); for (a in 0..4) { c.save(); c.rotate(-60f + a * 30f, w * 0.22f, h * 0.45f); c.drawOval(w * 0.22f, h * 0.44f, w * 0.42f, h * 0.47f, p); c.restore() } }
            "hills" -> { grad(c, w, h, 0xFF7EC8F5.toInt(), 0xFFDDF3FF.toInt()); fill(Color.WHITE); for (i in 0..2) c.drawOval(w * (0.1f + i * 0.3f), h * (0.15f + i % 2 * 0.06f), w * (0.35f + i * 0.3f), h * (0.22f + i % 2 * 0.06f), p)
                fill(0xFF7BAF6E.toInt()); c.drawOval(-w * 0.3f, h * 0.55f, w * 0.8f, h * 1.2f, p); fill(0xFF5A9A54.toInt()); c.drawOval(w * 0.2f, h * 0.6f, w * 1.4f, h * 1.3f, p)
                fill(0xFF8C8C9A.toInt()); val path = Path(); path.moveTo(w * 0.3f, h * 0.62f); path.lineTo(w * 0.55f, h * 0.3f); path.lineTo(w * 0.85f, h * 0.62f); path.close(); c.drawPath(path, p)
                fill(Color.WHITE); val sn = Path(); sn.moveTo(w * 0.48f, h * 0.39f); sn.lineTo(w * 0.55f, h * 0.3f); sn.lineTo(w * 0.62f, h * 0.39f); sn.close(); c.drawPath(sn, p) }
            "city" -> { night(c, w, h, k); val r = Random(k + 5); var x = 0f
                while (x < w) { val bw = w * (0.08f + r.nextFloat() * 0.1f); val bh = h * (0.2f + r.nextFloat() * 0.35f); fill(0xFF1E2140.toInt()); c.drawRect(x, h - bh, x + bw, h, p)
                    fill(0xFFFFD77A.toInt()); var yy = h - bh + bw * 0.2f; while (yy < h - bw * 0.2f) { var xx = x + bw * 0.15f; while (xx < x + bw - bw * 0.2f) { if (r.nextFloat() > 0.45f) c.drawRect(xx, yy, xx + bw * 0.12f, yy + bw * 0.12f, p); xx += bw * 0.25f }; yy += bw * 0.28f }
                    x += bw + w * 0.01f }
                bokeh(c, w, h, k, intArrayOf(0xFFFF5D73.toInt(), 0xFF4CC9F0.toInt(), 0xFFFFC83D.toInt())) }
            "coffee" -> { table(c, w, h, k + 2); val s = m * 0.25f; fill(Color.WHITE); c.drawOval(cx - s * 1.3f, cy + s * 0.3f, cx + s * 1.3f, cy + s * 0.7f, p)
                c.drawRoundRect(cx - s * 0.8f, cy - s * 0.6f, cx + s * 0.8f, cy + s * 0.5f, s * 0.3f, s * 0.3f, p); line(Color.WHITE, s * 0.15f); c.drawArc(cx + s * 0.6f, cy - s * 0.35f, cx + s * 1.2f, cy + s * 0.25f, -90f, 180f, false, p)
                fill(0xFF6B3E26.toInt()); c.drawOval(cx - s * 0.7f, cy - s * 0.68f, cx + s * 0.7f, cy - s * 0.38f, p); fill(0xFFF3E1C8.toInt()); c.drawCircle(cx, cy - s * 0.53f, s * 0.15f, p); steam(c, cx, cy - s * 0.8f, s * 1.2f) }
            "park" -> { grad(c, w, h, 0xFFA8E6FF.toInt(), 0xFFE9FAD9.toInt()); fill(0xFF7CC26B.toInt()); c.drawRect(0f, h * 0.65f, w, h, p)
                for (i in 0..2) { val x = w * (0.2f + i * 0.3f); val s = m * (0.13f + (i % 2) * 0.03f); fill(0xFF7A4E2C.toInt()); c.drawRect(x - s * 0.15f, h * 0.65f - s * 1.4f, x + s * 0.15f, h * 0.67f, p)
                    fill(0xFF3E9B4F.toInt()); c.drawCircle(x, h * 0.65f - s * 1.6f, s, p); c.drawCircle(x - s * 0.6f, h * 0.65f - s * 1.3f, s * 0.7f, p); c.drawCircle(x + s * 0.6f, h * 0.65f - s * 1.3f, s * 0.7f, p) }
                fill(0xFFFFE08A.toInt()); c.drawCircle(w * 0.85f, h * 0.15f, m * 0.08f, p) }
            "road" -> { grad(c, w, h, 0xFFFF8E6E.toInt(), 0xFFFFD9A0.toInt()); fill(0xFF5A9A54.toInt()); c.drawRect(0f, h * 0.55f, w, h, p)
                fill(0xFF3A3A3A.toInt()); val path = Path(); path.moveTo(cx - w * 0.04f, h * 0.55f); path.lineTo(cx + w * 0.04f, h * 0.55f); path.lineTo(w * 0.95f, h); path.lineTo(w * 0.05f, h); path.close(); c.drawPath(path, p)
                fill(0xFFFFE08A.toInt()); for (i in 0..5) { val t0 = i / 6f; val t1 = t0 + 0.08f; val y0 = h * (0.55f + 0.45f * t0 * t0); val y1 = h * (0.55f + 0.45f * t1 * t1); c.drawRect(cx - m * 0.005f * (1 + i), y0, cx + m * 0.005f * (1 + i), y1, p) } }
            "guitar" -> { studio(c, w, h, 0xFF2A1A4E.toInt(), 0xFF0E0A1F.toInt()); glow(c, cx, cy, m * 0.6f, 0x66B57BFF); c.save(); c.rotate(-25f, cx, cy); val s = m * 0.22f
                fill(0xFFD9822B.toInt()); c.drawCircle(cx, cy + s * 0.6f, s, p); c.drawCircle(cx, cy - s * 0.5f, s * 0.75f, p); fill(0xFF2B1A10.toInt()); c.drawCircle(cx, cy, s * 0.28f, p)
                fill(0xFF5A3A1E.toInt()); c.drawRect(cx - s * 0.12f, cy - s * 2.6f, cx + s * 0.12f, cy - s * 0.2f, p); c.drawRoundRect(cx - s * 0.2f, cy - s * 3f, cx + s * 0.2f, cy - s * 2.5f, s * 0.05f, s * 0.05f, p)
                line(0xDDFFFFFF.toInt(), s * 0.015f); for (i in -2..2) c.drawLine(cx + i * s * 0.04f, cy - s * 2.6f, cx + i * s * 0.04f, cy + s * 1.1f, p); c.restore(); notes(c, w, h, k) }
            "mic" -> { studio(c, w, h, 0xFF101828.toInt(), 0xFF000000.toInt()); glow(c, cx, h * 0.2f, m * 0.7f, 0x55FFFFFF); val s = m * 0.2f
                fill(0xFF3A3A3A.toInt()); c.drawRect(cx - s * 0.08f, cy, cx + s * 0.08f, h * 0.9f, p); c.drawRoundRect(cx - s * 0.3f, cy - s * 0.4f, cx + s * 0.3f, cy + s * 0.25f, s * 0.15f, s * 0.15f, p)
                fill(0xFFB0B0B8.toInt()); c.drawCircle(cx, cy - s * 0.7f, s * 0.55f, p); line(0xFF6E6E78.toInt(), s * 0.04f); for (i in -2..2) c.drawLine(cx + i * s * 0.18f, cy - s * 1.15f, cx + i * s * 0.18f, cy - s * 0.25f, p); notes(c, w, h, k) }
            "vinyl" -> { studio(c, w, h, 0xFFFF5D73.toInt(), 0xFF7B1E3A.toInt()); val s = m * 0.36f; fill(0xFF111111.toInt()); c.drawCircle(cx, cy, s, p)
                line(0xFF2A2A2A.toInt(), s * 0.015f); var r = s * 0.95f; while (r > s * 0.4f) { c.drawCircle(cx, cy, r, p); r -= s * 0.06f }; fill(0xFFFFC83D.toInt()); c.drawCircle(cx, cy, s * 0.3f, p); fill(0xFF111111.toInt()); c.drawCircle(cx, cy, s * 0.04f, p) }
            "speaker" -> { grad(c, w, h, 0xFF0E2A47.toInt(), 0xFF041220.toInt()); bokeh(c, w, h, k, intArrayOf(0xFF4CC9F0.toInt(), 0xFFB57BFF.toInt()))
                for (i in -1..1 step 2) { val x = cx + i * m * 0.22f; val s = m * 0.17f; fill(0xFF1A1A1A.toInt()); c.drawRoundRect(x - s * 0.7f, cy - s * 1.3f, x + s * 0.7f, cy + s * 1.3f, s * 0.1f, s * 0.1f, p)
                    fill(0xFF333333.toInt()); c.drawCircle(x, cy - s * 0.6f, s * 0.35f, p); c.drawCircle(x, cy + s * 0.5f, s * 0.55f, p); fill(0xFF4CC9F0.toInt()); c.drawCircle(x, cy + s * 0.5f, s * 0.15f, p) } }
            "stage" -> { grad(c, w, h, 0xFF120A24.toInt(), 0xFF000000.toInt()); val cols = intArrayOf(0x66FF5D73, 0x664CC9F0, 0x66FFC83D, 0x66B57BFF)
                for (i in 0..3) { p.style = Paint.Style.FILL; p.shader = LinearGradient(w * (0.1f + i * 0.27f), 0f, cx, h * 0.8f, cols[i], cols[i] and 0x00FFFFFF, Shader.TileMode.CLAMP)
                    val path = Path(); path.moveTo(w * (0.1f + i * 0.27f) - w * 0.02f, 0f); path.lineTo(w * (0.1f + i * 0.27f) + w * 0.02f, 0f); path.lineTo(cx + w * 0.2f, h * 0.8f); path.lineTo(cx - w * 0.2f, h * 0.8f); path.close(); c.drawPath(path, p); p.shader = null }
                fill(0xFF000000.toInt()); val r = Random(k); for (i in 0..14) c.drawCircle(w * i / 14f, h * 0.92f, m * (0.05f + r.nextFloat() * 0.03f), p); c.drawRect(0f, h * 0.92f, w, h, p) }
            "headphones" -> { studio(c, w, h, 0xFF4CC9F0.toInt(), 0xFF1E5BA8.toInt()); val s = m * 0.3f; line(0xFF1A1A1A.toInt(), s * 0.14f); c.drawArc(cx - s, cy - s * 1.1f, cx + s, cy + s * 0.9f, 180f, 180f, false, p)
                fill(0xFF1A1A1A.toInt()); c.drawRoundRect(cx - s * 1.15f, cy - s * 0.2f, cx - s * 0.7f, cy + s * 0.6f, s * 0.15f, s * 0.15f, p); c.drawRoundRect(cx + s * 0.7f, cy - s * 0.2f, cx + s * 1.15f, cy + s * 0.6f, s * 0.15f, s * 0.15f, p)
                fill(0xFFFF5D73.toInt()); c.drawCircle(cx - s * 0.92f, cy + s * 0.2f, s * 0.12f, p); c.drawCircle(cx + s * 0.92f, cy + s * 0.2f, s * 0.12f, p); notes(c, w, h, k) }
            else -> studio(c, w, h, 0xFF444444.toInt(), 0xFF111111.toInt())
        }
    }
    private fun notes(c: Canvas, w: Float, h: Float, k: Int) { val r = Random(k * 7 + 2); p.textAlign = Paint.Align.CENTER
        repeat(6) { fill(intArrayOf(0xCCFFC83D.toInt(), 0xCCFFFFFF.toInt(), 0xCCFF5D73.toInt())[it % 3]); p.textSize = minOf(w, h) * (0.06f + r.nextFloat() * 0.05f); c.drawText(if (it % 2 == 0) "♪" else "♫", r.nextFloat() * w, h * (0.1f + r.nextFloat() * 0.4f), p) } }
}
