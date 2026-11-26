package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/* The brand designs of the web app, drawn the same way: 10 styles x 4 colours x 10 fonts, logo position */
object Overlay {
    val STYLES = listOf("card", "band", "promo", "menu", "flash", "minimal", "polaroid", "gold", "panel", "speaker")
    val STYLE_NAMES = mapOf(
        "card" to Pair("Standard", "Standard"), "band" to Pair("Lower band", "Jalur bawah"), "promo" to Pair("Promo sticker", "Pelekat promo"),
        "menu" to Pair("Menu board", "Papan menu"), "flash" to Pair("Flash sale", "Jualan kilat"), "minimal" to Pair("Minimal", "Minimal"),
        "polaroid" to Pair("Polaroid frame", "Bingkai polaroid"), "gold" to Pair("Premium frame", "Bingkai premium"), "panel" to Pair("Product panel", "Panel produk"),
        "speaker" to Pair("Name by angle", "Nama ikut sudut"))
    val STYLE_DESC = mapOf(
        "card" to Pair("Title, price, contact", "Tajuk, harga, nombor"), "band" to Pair("News-style strip", "Gaya berita"), "promo" to Pair("Big round price", "Harga bulat besar"),
        "menu" to Pair("2 to 4 items with prices", "2 hingga 4 item berharga"), "flash" to Pair("Banner and order button", "Sepanduk dan butang tempah"),
        "minimal" to Pair("Small logo only", "Logo kecil sahaja"), "polaroid" to Pair("White frame, name below", "Bingkai putih, nama di bawah"),
        "gold" to Pair("Colour border, shop name on top", "Garis warna, nama kedai atas"), "panel" to Pair("Video plus info panel", "Video dan panel maklumat"),
        "speaker" to Pair("Podcasts and talks", "Podcast dan ceramah"))
    class Col(val c: Int, val ink: Int)
    val COLORS = linkedMapOf("gold" to Col(0xFFD9B44A.toInt(), 0xFF1B1503.toInt()), "red" to Col(0xFFD6453A.toInt(), Color.WHITE),
        "green" to Col(0xFF2F9E5B.toInt(), Color.WHITE), "white" to Col(Color.WHITE, 0xFF111111.toInt()))
    class Font(val file: String, val w: Int, val caps: Boolean = false, val script: Boolean = false, val en: String, val ms: String)
    val FONTS = linkedMapOf(
        "bricolage" to Font("bricolage.ttf", 800, en = "Bricolage — modern", ms = "Bricolage — moden"),
        "poppins" to Font("poppins.ttf", 700, en = "Poppins — clean", ms = "Poppins — bersih"),
        "montserrat" to Font("montserrat.ttf", 800, en = "Montserrat — bold", ms = "Montserrat — tegas"),
        "bebas" to Font("bebas.ttf", 400, caps = true, en = "Bebas — cinematic", ms = "Bebas — sinematik"),
        "anton" to Font("anton.ttf", 400, en = "Anton — promo", ms = "Anton — promo"),
        "playfair" to Font("playfair.ttf", 700, en = "Playfair — elegant", ms = "Playfair — elegan"),
        "lobster" to Font("lobster.ttf", 400, script = true, en = "Lobster — casual", ms = "Lobster — santai"),
        "pacifico" to Font("pacifico.ttf", 400, script = true, en = "Pacifico — cute", ms = "Pacifico — comel"),
        "dancing" to Font("dancing.ttf", 700, script = true, en = "Dancing Script — handwritten", ms = "Dancing Script — tulisan tangan"),
        "marker" to Font("marker.ttf", 400, script = true, en = "Marker — chalkboard", ms = "Marker — papan tulis"))
    val DEF_FONT = mapOf("card" to "bricolage", "band" to "poppins", "promo" to "anton", "menu" to "montserrat", "flash" to "anton", "minimal" to "poppins",
        "polaroid" to "pacifico", "gold" to "playfair", "panel" to "poppins", "speaker" to "montserrat")
    val POS = listOf("tr" to Pair("Top right", "Atas kanan"), "tl" to Pair("Top left", "Atas kiri"), "br" to Pair("Bottom right", "Bawah kanan"), "bl" to Pair("Bottom left", "Bawah kiri"))

    private val faces = HashMap<String, Typeface>()
    fun face(c: Context, key: String, weight: Int? = null): Typeface {
        val f = FONTS[key] ?: FONTS["bricolage"]!!; val w = weight ?: f.w; val k = "$key/$w"
        return faces.getOrPut(k) {
            try { Typeface.Builder(c.assets, "fonts/" + f.file).setFontVariationSettings("'wght' $w").setWeight(w).build() ?: Typeface.DEFAULT_BOLD }
            catch (e: Exception) { if (w >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        }
    }

    /* everything the drawing needs */
    class In(val ctx: Context, val W: Int, val H: Int, val pro: Boolean, val bars: Boolean, val style: String, val col: String, val font: String, val logoPos: String,
             val title: String, val price: String, val phone: String, val shop: String, val desc: String, val menu: String, val speaker: String, val logo: Bitmap?)

    /* the part of the picture the video uses (polaroid and panel make it smaller); top-left based */
    fun videoRect(st: String, W: Int, H: Int): FloatArray? {
        val P = H > W
        return when (st) {
            "polaroid" -> if (P) floatArrayOf(W * .07f, H * .045f, W * .86f, H * .74f, 1f) else floatArrayOf(W * .04f, H * .06f, W * .92f, H * .72f, 1f)
            "panel" -> if (P) floatArrayOf(0f, 0f, W.toFloat(), H * .68f, 2f) else floatArrayOf(0f, 0f, W * .64f, H.toFloat(), 2f)
            else -> null
        }
    }
    fun bgColor(kind: Float) = if (kind == 1f) 0xFFF4EFE6.toInt() else 0xFF0E0E0F.toInt()

    private lateinit var g: Canvas
    private lateinit var i: In
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun ft(px: Float) { val f = FONTS[i.font]!!; p.typeface = face(i.ctx, i.font); p.textSize = px }
    private fun fi(px: Float, w: Int? = null) { val f = FONTS[i.font]!!; val key = if (f.script) "poppins" else i.font; p.typeface = face(i.ctx, key, w ?: FONTS[key]!!.w); p.textSize = px }
    private fun cap(x: String) = if (FONTS[i.font]?.caps == true) x.uppercase() else x
    private fun fit(t: String, maxW: Float): String { if (p.measureText(t) <= maxW) return t; var s = t; while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1); return "$s…" }
    private fun wrap(txt: String, maxW: Float, max: Int): List<String> {
        val words = txt.split(Regex("\\s+")).filter { it.isNotEmpty() }; val out = ArrayList<String>(); var cur = ""
        var idx = 0
        while (idx < words.size) { val w = words[idx]; val test = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(test) <= maxW || cur.isEmpty()) { cur = test; idx++; continue }
            out.add(cur); cur = w; if (out.size == max - 1) { cur = words.drop(idx).joinToString(" "); break }; idx++ }
        if (cur.isNotEmpty()) out.add(cur)
        return out.mapIndexed { k, l -> if (k == out.size - 1) fit(l, maxW) else l }.take(max)
    }
    private fun text(t: String, x: Float, y: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) { p.color = color; p.textAlign = align; g.drawText(t, x, y, p) }
    private fun shadow(S: Float, on: Boolean) { if (on) p.setShadowLayer(S * 0.018f, 0f, S * 0.003f, 0xBF000000.toInt()) else p.clearShadowLayer() }
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, stroke: Float = 0f) {
        fill.color = color; fill.style = if (stroke > 0) Paint.Style.STROKE else Paint.Style.FILL; fill.strokeWidth = stroke
        g.drawRoundRect(RectF(x, y, x + w, y + h), r, r, fill)
    }
    private fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { fill.style = Paint.Style.FILL; fill.color = color; g.drawRect(x, y, x + w, y + h, fill) }
    private fun logoOk() = i.pro && i.logo != null
    private fun logoAt(x: Float, y: Float, box: Float, alpha: Float = 1f): Float {
        val l = i.logo ?: return 0f; if (!i.pro) return 0f
        val ar = l.width.toFloat() / l.height; var lw = box; var lh = box / ar; if (lh > box) { lh = box; lw = box * ar }
        val pa = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG); pa.alpha = (alpha * 255).toInt()
        g.drawBitmap(l, null, RectF(x + (box - lw) / 2, y + (box - lh) / 2, x + (box - lw) / 2 + lw, y + (box - lh) / 2 + lh), pa); return box
    }
    fun phoneLine(ph: String): String { val s = ph.trim(); return if (Regex("^[+\\d][\\d\\s\\-()+.]{5,}$").matches(s) && s.count { it.isDigit() } >= 7) Ui.t("Order: $s", "Tempah: $s") else s }
    private fun menuItems(): List<Pair<String, String>> = i.menu.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.take(4).map { l ->
        val m = Regex("^(.*?)\\s*[-–:|]\\s*((?:RM)?\\s*[\\d.,]+.*)$", RegexOption.IGNORE_CASE).find(l); if (m != null) Pair(m.groupValues[1], m.groupValues[2]) else Pair(l, "") }

    fun draw(c: Canvas, inp: In) {
        g = c; i = inp
        val W = i.W.toFloat(); val H = i.H.toFloat(); val S = minOf(W, H); val m = Math.round(S * 0.045f).toFloat(); val barH = if (i.bars) Math.round(H * 0.08f).toFloat() else 0f
        val P = H > W; val PRO = i.pro
        val title = i.title.trim(); val price = if (PRO) i.price.trim() else ""; val phone = if (PRO) i.phone.trim() else ""
        val shop = if (PRO) i.shop.trim() else ""; val desc = if (PRO) i.desc.trim() else ""
        val st = if (PRO && i.style in STYLES) i.style else "card"; val C = COLORS[if (PRO) i.col else "gold"] ?: COLORS["gold"]!!
        val info = listOf(price, if (phone.isNotEmpty()) phoneLine(phone) else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
        p.clearShadowLayer()
        if (!PRO) watermark(W, H, S, m, barH)
        val low = H - barH - m
        when (st) {
            "band" -> {
                val bh = Math.round(S * 0.17f).toFloat(); val y = low - bh; rect(0f, y, W, bh, 0xE60E0E0F.toInt()); rect(0f, y, W, maxOf(3f, S * 0.008f), C.c)
                var x = m; val lb = if (logoOk()) Math.round(bh * 0.7f).toFloat() else 0f; if (lb > 0) { logoAt(x, y + (bh - lb) / 2, lb); x += lb + m * 0.6f }
                val mw = W - x - m; ft(bh * 0.32f); text(fit(cap(title.ifEmpty { shop }), mw), x, y + bh * 0.48f, Color.WHITE)
                fi(bh * 0.22f); text(fit(info, mw), x, y + bh * 0.8f, C.c)
            }
            "promo" -> {
                logoAt(m, m + barH, Math.round(S * 0.18f).toFloat())
                if (price.isNotEmpty()) { val r = Math.round(S * 0.14f).toFloat(); val cx = W - m - r; val cy = m + barH + r
                    g.save(); g.translate(cx, cy); g.rotate(Math.toDegrees(-0.17).toFloat()); fill.style = Paint.Style.FILL; fill.color = C.c; g.drawCircle(0f, 0f, r, fill)
                    fi(r * 0.26f, 800); text(Ui.t("PROMO", "PROMO"), 0f, -r * 0.28f, C.ink, Paint.Align.CENTER); ft(r * 0.5f); text(fit(cap(price), r * 1.7f), 0f, r * 0.32f, C.ink, Paint.Align.CENTER); g.restore() }
                shadow(S, true); ft(S / 12); text(fit(cap(title), W - m * 2), m, low - (if (phone.isNotEmpty()) S * 0.08f else 0f), Color.WHITE)
                if (phone.isNotEmpty()) { fi(S / 24); text(fit(phoneLine(phone), W - m * 2), m, low, Color.WHITE) }; shadow(S, false)
            }
            "menu" -> {
                logoAt(W - m - Math.round(S * 0.18f), m + barH, Math.round(S * 0.18f).toFloat())
                var items = menuItems(); if (items.isEmpty() && (title.isNotEmpty() || price.isNotEmpty())) items = listOf(Pair(title, price))
                val fs = Math.round(S / 24).toFloat(); val pad = Math.round(fs * 0.8f).toFloat(); val rowH = fs * 1.55f; val w = W - m * 2
                val h = pad * 2 + fs * 1.4f + items.size * rowH + (if (phone.isNotEmpty()) fs * 1.4f else 0f); val y = low - h
                rr(m, y, w, h, fs * 0.6f, 0xE00E0E0F.toInt()); rr(m, y, w, h, fs * 0.6f, C.c, maxOf(2f, S * 0.004f))
                var cy = y + pad + fs; ft(fs * 1.05f); text(fit(cap(shop.ifEmpty { Ui.t("TODAY'S MENU", "MENU HARI INI") }), w - pad * 2), m + pad, cy, C.c); cy += fs * 1.4f
                for ((n, pr) in items) { fi(fs, 600); text(fit(n, w * 0.62f), m + pad, cy, Color.WHITE); fi(fs, 800); text(pr, m + w - pad, cy, C.c, Paint.Align.RIGHT); cy += rowH }
                if (phone.isNotEmpty()) { fi(fs * 0.85f, 600); text(fit(phoneLine(phone), w - pad * 2), m + pad, cy, 0xFFC8C4BB.toInt()) }
            }
            "flash" -> {
                val bh = Math.round(S * 0.11f).toFloat(); rect(0f, barH, W, bh, C.c); ft(bh * 0.5f)
                text(fit(cap(Ui.t("TODAY'S SALE", "JUALAN HARI INI") + if (price.isNotEmpty()) "  ·  $price" else ""), W - m * 2), W / 2, barH + bh * 0.68f, C.ink, Paint.Align.CENTER)
                fi(S / 30, 700); val tg = Ui.t("Limited stock", "Stok terhad"); val tw = p.measureText(tg) + S * 0.04f
                rr(W - m - tw, barH + bh + m * 0.5f, tw, S * 0.06f, S * 0.015f, 0xD90E0E0F.toInt()); text(tg, W - m - tw / 2, barH + bh + m * 0.5f + S * 0.042f, if (C.c == Color.WHITE) Ui.GOLD else C.c, Paint.Align.CENTER)
                val ch = Math.round(S * 0.12f).toFloat(); val cy = low - ch; rr(m, cy, W - m * 2, ch, ch * 0.25f, C.c)
                fi(ch * 0.36f, 800); text(fit(if (phone.isNotEmpty()) phoneLine(phone) else title, W - m * 4), W / 2, cy + ch * 0.63f, C.ink, Paint.Align.CENTER)
                if (title.isNotEmpty() && phone.isNotEmpty()) { shadow(S, true); ft(S / 13); text(fit(cap(title), W - m * 2), W / 2, cy - S * 0.04f, Color.WHITE, Paint.Align.CENTER); shadow(S, false) }
            }
            "minimal" -> {
                val lb = Math.round(S * 0.11f).toFloat(); logoAt(W - m - lb, low - lb, lb, 0.85f)
                val t0 = shop.ifEmpty { title }; if (t0.isNotEmpty()) { shadow(S, true); fi(S / 28, 600); p.alpha = 217; text(fit(t0, W * 0.6f), m, low - S * 0.02f, Color.WHITE); p.alpha = 255; shadow(S, false) }
            }
            "polaroid" -> {
                val R = videoRect(st, i.W, i.H)!!; val by = R[1] + R[3]; val rest = H - by
                ft(minOf(rest * 0.3f, S / 10)); text(fit(cap(title.ifEmpty { shop }), W * 0.9f), W / 2, by + rest * 0.42f, 0xFF2B1D12.toInt(), Paint.Align.CENTER)
                fi(minOf(rest * 0.15f, S / 24), 600); text(fit(info, W * 0.9f), W / 2, by + rest * 0.7f, 0xFF7A5A32.toInt(), Paint.Align.CENTER)
                logoAt(R[0] + R[2] - m - Math.round(S * 0.13f), R[1] + m, Math.round(S * 0.13f).toFloat())
            }
            "gold" -> {
                val ins = Math.round(S * 0.02f).toFloat(); rr(ins, ins + barH, W - ins * 2, H - ins * 2 - barH * 2, S * 0.03f, C.c, maxOf(3f, S * 0.008f))
                if (shop.isNotEmpty() || logoOk()) { ft(S / 24); val tx = cap(shop); val tw = maxOf(p.measureText(tx), if (shop.isNotEmpty()) 0f else S * 0.1f) + S * 0.08f; val th = S * 0.075f
                    rr(W / 2 - tw / 2, barH, tw, th, S * 0.02f, C.c)
                    if (shop.isNotEmpty()) text(tx, W / 2, barH + th * 0.7f, C.ink, Paint.Align.CENTER) else logoAt(W / 2 - th * 0.45f, barH + th * 0.05f, th * 0.9f) }
                if (shop.isNotEmpty() && logoOk()) logoAt(W - m - Math.round(S * 0.15f), barH + m + S * 0.06f, Math.round(S * 0.15f).toFloat())
                shadow(S, true); ft(S / 11); text(fit(cap(title), W - m * 3), W / 2, low - S * 0.07f, Color.WHITE, Paint.Align.CENTER)
                fi(S / 24); text(fit(info, W - m * 3), W / 2, low, C.c, Paint.Align.CENTER); shadow(S, false)
            }
            "panel" -> {
                val R = videoRect(st, i.W, i.H)!!; val px = if (P) 0f else R[2]; val py = if (P) R[3] else 0f; val pw = if (P) W else W - R[2]; val ph = if (P) H - R[3] else H; val pad = Math.round(S * 0.05f).toFloat()
                if (P) rect(0f, py, W, maxOf(3f, S * 0.008f), C.c) else rect(px, 0f, maxOf(3f, S * 0.008f), H, C.c)
                val x = px + pad; var y = py + pad; val lb = if (logoOk()) Math.round(S * 0.1f).toFloat() else 0f; if (lb > 0) logoAt(x, y, lb)
                val tx = if (lb > 0) x + lb + pad * 0.5f else x; val tw = px + pw - pad - tx
                ft(if (P) S / 16 else minOf(S / 16, pw / 10)); text(fit(cap(title.ifEmpty { shop }), tw), tx, y + (if (lb > 0) lb * 0.62f else S / 16), Color.WHITE)
                y += maxOf(lb, S / 16) + pad * 0.6f
                if (desc.isNotEmpty()) { fi(S / 30, 500); text(fit(desc, pw - pad * 2), x, y + S / 30, 0xFF9B978E.toInt()); y += S / 30 * 1.8f }
                val by = py + ph - pad - S * 0.075f
                if (price.isNotEmpty()) { fi(S / 16, 800); text(fit(price, pw * 0.45f), x, by + S * 0.058f, C.c) }
                if (phone.isNotEmpty()) { fi(S / 30, 800); val bt = Ui.t("Order now", "Tempah"); val bw = p.measureText(bt) + S * 0.06f
                    rr(px + pw - pad - bw, by, bw, S * 0.075f, S * 0.018f, C.c); text(bt, px + pw - pad - bw / 2, by + S * 0.05f, C.ink, Paint.Align.CENTER)
                    fi(S / 34, 600); text(fit(phone, pw - pad * 2), x, by - S * 0.02f, 0xFFC8C4BB.toInt()) }
            }
            "speaker" -> {
                logoAt(W - m - Math.round(S * 0.16f), m + barH, Math.round(S * 0.16f).toFloat())
                val who = i.speaker; if (who.isEmpty() && title.isEmpty()) return
                val fs = Math.round(S / 20).toFloat(); val pad = fs * 0.6f; val y = low - fs * 1.7f
                ft(fs); val nw = minOf(W - m * 2, p.measureText(cap(who)) + pad * 2); rect(m, y, nw, fs * 1.7f, 0xEB0E0E0F.toInt()); text(fit(cap(who), nw - pad * 2), m + pad, y + fs * 1.2f, Color.WHITE)
                if (title.isNotEmpty()) { fi(fs * 0.6f, 800); val tw = p.measureText(title) + pad * 2; rect(m, y - fs * 0.95f, minOf(tw, W - m * 2), fs * 0.95f, C.c); text(fit(title, W - m * 2 - pad * 2), m + pad, y - fs * 0.25f, C.ink) }
            }
            else -> card(W, H, S, m, barH, title, price, phone, C)
        }
        p.clearShadowLayer()
    }

    /* style 1: the recipe card (also what Basic uses) */
    private fun card(W: Float, H: Float, S: Float, m: Float, barH: Float, title: String, price: String, phone: String, C: Col) {
        val hasCard = price.isNotEmpty() || phone.isNotEmpty()
        var cardY = 0f; var cardH = 0f; val fs = Math.round(S / 17).toFloat(); val fs2 = Math.round(S / 24).toFloat(); val pad = Math.round(fs * 0.6f).toFloat()
        val cardW = W - m * 2; var tl = listOf<String>()
        if (hasCard) {
            if (title.isNotEmpty()) { ft(fs); tl = wrap(cap(title), cardW - pad * 2, 2) }
            cardH = pad * 2 + (if (tl.isNotEmpty()) fs * 1.3f + (tl.size - 1) * fs * 1.12f else 0f) + (if (price.isNotEmpty()) fs * 1.55f else 0f) + (if (phone.isNotEmpty()) fs2 * 1.6f else 0f)
            cardY = H - barH - m - cardH
        }
        val l = i.logo
        if (logoOk() && l != null) {
            val pos = i.logoPos; val big = Math.round(S * 0.2f).toFloat()
            val ar = l.width.toFloat() / l.height; var lw = big; var lh = Math.round(big / ar).toFloat(); if (lh > big * 1.2f) { lh = Math.round(big * 1.2f).toFloat(); lw = Math.round(lh * ar).toFloat() }
            val x = if (pos.getOrNull(1) == 'l') m else W - m - lw
            val y = if (pos.getOrNull(0) == 't') m + barH + (if (title.isNotEmpty() && !hasCard) Math.round(S * 0.13f).toFloat() else 0f)
                    else { val yy = H - barH - m - lh; if (hasCard) cardY = yy - Math.round(S * 0.025f) - cardH; yy }
            g.drawBitmap(l, null, RectF(x, y, x + lw, y + lh), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        if (hasCard) {
            shadow(S, true)
            var cy = cardY + pad; val lx = m + pad; val maxW = cardW - pad * 2
            if (tl.isNotEmpty()) { ft(fs); tl.forEachIndexed { k, ln -> text(ln, lx, cy + fs * 0.95f + k * fs * 1.12f, Color.WHITE) }; cy += fs * 1.3f + (tl.size - 1) * fs * 1.12f }
            if (price.isNotEmpty()) { val pf = Math.round(fs * 0.9f).toFloat(); fi(pf, 800); val pt = fit(price, maxW - pad); val pw = p.measureText(pt) + pad * 1.2f
                rr(lx, cy + fs * 0.05f, pw, pf * 1.35f, pf * 0.3f, C.c); shadow(S, false); text(pt, lx + pad * 0.6f, cy + fs * 0.05f + pf * 0.98f, C.ink); shadow(S, true); cy += fs * 1.55f }
            if (phone.isNotEmpty()) { fi(fs2, 800); text(fit(phoneLine(phone), maxW), lx, cy + fs2 * 1.05f, Color.WHITE) }
            shadow(S, false)
        } else if (title.isNotEmpty()) {
            val f = Math.round(S / 15).toFloat(); ft(f)
            val tt = fit(cap(title), W - m * 4); val tw = p.measureText(tt); val pd = f * 0.5f; val ty = Math.round(H * (if (i.bars) 0.1f else 0.06f)).toFloat()
            rr(W / 2 - tw / 2 - pd, ty, tw + pd * 2, f * 1.6f, f * 0.35f, (C.c and 0x00FFFFFF) or 0xF2000000.toInt())
            text(tt, W / 2, ty + f * 1.15f, C.ink, Paint.Align.CENTER)
        }
    }

    private fun watermark(W: Float, H: Float, S: Float, m: Float, barH: Float) {
        val fs = Math.round(S / 30).toFloat(); p.typeface = face(i.ctx, "bricolage", 800); p.textSize = fs
        val txt = "ProMultiCam"; val x = W - m; val y = H - m - barH - Math.round(H * (if (H > W) 0.02f else 0f))
        val tw = p.measureText(txt)
        rr(x - tw - fs * 1.6f, y - fs * 1.2f, tw + fs * 2.1f, fs * 1.6f, fs * 0.4f, 0x5E0E0E0F)
        fill.color = 0xBFD9776B.toInt(); fill.style = Paint.Style.FILL; g.drawCircle(x - tw - fs * 0.8f, y - fs * 0.4f, fs * 0.28f, fill)
        text(txt, x, y, 0xBFFFFFFF.toInt(), Paint.Align.RIGHT)
    }
}
