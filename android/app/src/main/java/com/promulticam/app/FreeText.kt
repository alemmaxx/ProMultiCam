package com.promulticam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/* "Free text" brand style: up to 6 lines that can be dragged anywhere, each with its own look, and an info box (several lines) that can be dragged too */
object FreeText {
    // looks for one line: box colour, text colour (0 = no box, white text with a shadow)
    val LOOKS = listOf(0xFFFFFFFF.toInt() to 0xFF1A1A1A.toInt(), 0xF0151210.toInt() to 0xFFFFD84A.toInt(), 0xFFFFD84A.toInt() to 0xFF1A1A1A.toInt(),
        0xFFE5483B.toInt() to 0xFFFFFFFF.toInt(), 0 to 0xFFFFFFFF.toInt(), 0xFFD9B44A.toInt() to 0xFF1A1405.toInt())
    fun lookName(k: Int) = listOf(Ui.t("White box", "Kotak putih"), Ui.t("Black / yellow", "Hitam / kuning"), Ui.t("Yellow box", "Kotak kuning"), Ui.t("Red box", "Kotak merah"), Ui.t("No box", "Tiada kotak"), Ui.t("Gold box", "Kotak emas"))[k % 6]
    val KEYS = (1..6).flatMap { listOf("fl$it", "fp$it", "fs$it", "fz$it") } + listOf("fbOn", "fbTxt", "fbPos", "fbSz")
    private fun sp(c: Context) = c.getSharedPreferences("pmc", Context.MODE_PRIVATE)
    fun pos(c: Context, k: String, dx: Float, dy: Float): Pair<Float, Float> = (sp(c).getString(k, null)?.split(",")?.mapNotNull { it.toFloatOrNull() })?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (dx to dy)
    fun setPos(c: Context, k: String, x: Float, y: Float) = sp(c).edit().putString(k, "${x.coerceIn(0.02f, 0.98f)},${y.coerceIn(0.02f, 0.98f)}").apply()

    /* where each part was drawn last time (frame pixels), for dragging */
    @Volatile var rects: Map<String, RectF> = emptyMap()
    @Volatile var hideLines = false; @Volatile var hideBox = false   // follow "When to show" (Title = lines, Price = box)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(cv: Canvas, c: Context, W: Int, H: Int, font: String, showLines: Boolean, showBox: Boolean) {
        val s = sp(c); val S = minOf(W, H).toFloat(); val out = HashMap<String, RectF>()
        tp.typeface = Overlay.face(c, font.ifEmpty { "anton" })
        if (showLines) for (n in 1..6) {
            val txt = s.getString("fl$n", "")?.trim() ?: ""; if (txt.isEmpty()) continue
            val (cx, cy) = pos(c, "fp$n", 0.5f, 0.12f + n * 0.07f)
            val (bc, tc) = LOOKS[s.getInt("fs$n", if (n == 1) 0 else 1) % LOOKS.size]
            tp.textSize = S * 0.062f * s.getFloat("fz$n", 1f); tp.color = tc
            var tw = tp.measureText(txt); val maxW = W * 0.94f; while (tw > maxW && tp.textSize > 8f) { tp.textSize *= 0.94f; tw = tp.measureText(txt) }   // long line shrinks to fit
            val pad = tp.textSize * 0.42f; val bw = tw + pad * 2; val bh = tp.textSize * 1.45f
            val l = (cx * W - bw / 2).coerceIn(0f, W - bw); val t = (cy * H - bh / 2).coerceIn(0f, H - bh)
            if (bc != 0) { bg.color = bc; cv.drawRoundRect(l, t, l + bw, t + bh, bh * 0.22f, bh * 0.22f, bg); tp.clearShadowLayer() } else tp.setShadowLayer(tp.textSize * 0.12f, 0f, 2f, 0xCC000000.toInt())
            cv.drawText(txt, l + pad, t + bh * 0.5f - (tp.descent() + tp.ascent()) / 2, tp); tp.clearShadowLayer()
            out["fp$n"] = RectF(l, t, l + bw, t + bh)
        }
        if (showBox && s.getBoolean("fbOn", false)) {
            val lines = (s.getString("fbTxt", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(8)
            if (lines.isNotEmpty()) {
                val (cx, cy) = pos(c, "fbPos", 0.25f, 0.55f)
                tp.typeface = Overlay.face(c, "poppins"); tp.textSize = S * 0.04f * s.getFloat("fbSz", 1f); tp.color = Color.WHITE
                val maxW = W * 0.6f; val pad = tp.textSize * 0.8f
                // wrap long lines inside the box
                val rows = ArrayList<String>(); lines.forEach { ln -> var cur = ""; ln.split(' ').forEach { w -> val tst = if (cur.isEmpty()) w else "$cur $w"; if (tp.measureText(tst) > maxW - pad * 2 && cur.isNotEmpty()) { rows.add(cur); cur = w } else cur = tst }; if (cur.isNotEmpty()) rows.add(cur) }
                val lh = tp.textSize * 1.3f; val bw = minOf(maxW, (rows.maxOfOrNull { tp.measureText(it) } ?: 0f) + pad * 2); val bh = lh * rows.size + pad * 1.3f
                val l = (cx * W - bw / 2).coerceIn(0f, W - bw); val t = (cy * H - bh / 2).coerceIn(0f, H - bh)
                bg.color = 0xB34A3A3E.toInt(); cv.drawRoundRect(l, t, l + bw, t + bh, S * 0.03f, S * 0.03f, bg)
                rows.forEachIndexed { k, r -> cv.drawText(r, l + pad, t + pad * 0.65f + lh * (k + 0.78f), tp) }
                out["fbPos"] = RectF(l, t, l + bw, t + bh)
            }
        }
        rects = out
    }
    /* which part is under the finger (frame pixels), the box first if they overlap last drawn on top */
    fun hit(x: Float, y: Float): String? { val r = rects; r["fbPos"]?.let { if (it.contains(x, y)) return "fbPos" }; return r.entries.firstOrNull { it.key != "fbPos" && it.value.contains(x, y) }?.key }
}
