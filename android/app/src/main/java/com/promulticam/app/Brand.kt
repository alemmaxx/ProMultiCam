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
