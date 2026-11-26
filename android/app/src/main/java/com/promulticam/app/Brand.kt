package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File

/* The brand drawn into the video: logo top-right, title / price / contact bottom-left (like the web Standard style) */
class Brand(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("pmc", Context.MODE_PRIVATE)
    var on: Boolean get() = sp.getBoolean("brand", true); set(v) { sp.edit().putBoolean("brand", v).apply() }
    var hd: Boolean get() = sp.getBoolean("hd", false); set(v) { sp.edit().putBoolean("hd", v).apply() }
    var title: String get() = sp.getString("title", "") ?: ""; set(v) { sp.edit().putString("title", v).apply() }
    var price: String get() = sp.getString("price", "") ?: ""; set(v) { sp.edit().putString("price", v).apply() }
    var phone: String get() = sp.getString("phone", "") ?: ""; set(v) { sp.edit().putString("phone", v).apply() }

    val logoFile: File get() = File(ctx.filesDir, "logo.png")
    private val origFile: File get() = File(ctx.filesDir, "logo_orig.png")
    var removeBg: Boolean get() = sp.getBoolean("logoBg", true); set(v) { sp.edit().putBoolean("logoBg", v).apply(); process() }

    /* a new logo: keep the original, then (if chosen) make its plain background see-through */
    fun setLogo(src: Bitmap) {
        val k = minOf(1f, 600f / maxOf(src.width, src.height))
        val b = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        origFile.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        process()
    }
    fun clearLogo() { logoFile.delete(); origFile.delete(); refresh() }
    private fun process() {
        val o = (if (origFile.exists()) BitmapFactory.decodeFile(origFile.absolutePath) else null) ?: run { refresh(); return }
        val b = if (removeBg) cutBg(o) else o
        logoFile.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        refresh()
    }
    /* flood-fill from the edges: pixels close to the corner colour become transparent */
    private fun cutBg(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height; val px = IntArray(w * h); src.copy(Bitmap.Config.ARGB_8888, false).getPixels(px, 0, w, 0, 0, w, h)
        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        if (corners.all { Color.alpha(it) < 20 }) return src            // already transparent
        val bg = corners[0]
        fun near(c: Int) = Color.alpha(c) < 20 || (Math.abs(Color.red(c) - Color.red(bg)) + Math.abs(Color.green(c) - Color.green(bg)) + Math.abs(Color.blue(c) - Color.blue(bg))) < 90
        val seen = BooleanArray(w * h); val q = IntArray(w * h); var head = 0; var tail = 0
        fun push(i: Int) { if (!seen[i] && near(px[i])) { seen[i] = true; q[tail++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) { val i = q[head++]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w) }
        for (i in 0 until w * h) if (seen[i]) px[i] = 0
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
    @Volatile var logo: Bitmap? = null
    fun loadLogo() { logo = if (logoFile.exists()) BitmapFactory.decodeFile(logoFile.absolutePath) else null }

    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt()) }
    private val pPrice = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1405.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val pPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GOLD }
    private val pPhone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(5f, 0f, 2f, 0xAA000000.toInt()) }
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // cached copies so each frame only draws, never reads settings
    @Volatile var cTitle = ""; @Volatile var cPrice = ""; @Volatile var cPhone = ""
    fun refresh() { cTitle = title; cPrice = price; cPhone = phone; loadLogo() }

    /* the brand as a picture (transparent around it) */
    fun render(W: Int, H: Int, look: Looks.Look? = null): Bitmap? {
        val vig = look?.vig ?: 0f; val bars = look?.bars == true
        if (!on && vig <= 0f && !bars) return null
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        if (vig > 0f) {
            val p = Paint(); p.shader = android.graphics.RadialGradient(W / 2f, H / 2f, maxOf(W, H) * 0.7f,
                intArrayOf(0, 0, android.graphics.Color.argb((vig * 255).toInt(), 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        }
        if (bars) { val p = Paint(); p.color = android.graphics.Color.BLACK; val bh = H * 0.08f; c.drawRect(0f, 0f, W.toFloat(), bh, p); c.drawRect(0f, H - bh, W.toFloat(), H.toFloat(), p) }
        if (on) draw(c, W, H)
        return b
    }

    /* title card / closing card: dark background, logo, big title, price and contact */
    fun card(W: Int, H: Int, closing: Boolean, closingText: String): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawColor(0xF00E0E0F.toInt())
        val m = minOf(W, H).toFloat()
        logo?.let { l -> val s = m * 0.34f; val k = s / maxOf(l.width, l.height); val w = l.width * k; val h = l.height * k
            c.drawBitmap(l, null, RectF((W - w) / 2, H * 0.28f - h / 2, (W + w) / 2, H * 0.28f + h / 2), pBmp) }
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = m * 0.08f }
        val ps = Paint(pt).apply { color = Ui.GOLD; textSize = m * 0.05f }
        val big = if (closing) closingText.ifEmpty { cTitle } else cTitle
        if (big.isNotEmpty()) c.drawText(big, W / 2f, H * 0.5f, pt)
        if (cPrice.isNotEmpty()) c.drawText(cPrice, W / 2f, H * 0.58f, ps)
        if (closing && cPhone.isNotEmpty()) c.drawText(cPhone, W / 2f, H * 0.65f, Paint(pt).apply { textSize = m * 0.05f })
        return b
    }

    /* draw upright into a W x H area */
    fun draw(c: Canvas, W: Int, H: Int) {
        val m = minOf(W, H).toFloat()
        val pad = m * 0.045f
        logo?.let { b ->
            val s = m * 0.2f
            val k = s / maxOf(b.width, b.height)
            val w = b.width * k; val h = b.height * k
            c.drawBitmap(b, null, RectF(W - pad - w, pad, W - pad, pad + h), pBmp)
        }
        var y = H - H * 0.07f
        if (cPhone.isNotEmpty()) {
            pPhone.textSize = m * 0.036f
            c.drawText(cPhone, pad, y, pPhone); y -= m * 0.06f
        }
        if (cPrice.isNotEmpty()) {
            pPrice.textSize = m * 0.042f
            val tw = pPrice.measureText(cPrice); val ph = m * 0.064f; val r = ph / 2.4f
            val rect = RectF(pad, y - ph + m * 0.012f, pad + tw + m * 0.05f, y + m * 0.012f)
            c.drawRoundRect(rect, r, r, pPill)
            c.drawText(cPrice, pad + m * 0.025f, y - m * 0.006f, pPrice); y -= ph + m * 0.02f
        }
        if (cTitle.isNotEmpty()) {
            pTitle.textSize = m * 0.062f
            c.drawText(cTitle, pad, y, pTitle)
        }
    }
}
