package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/* ---------------- the project ---------------- */
class Clip(val file: File, var inUs: Long, var outUs: Long, val still: Boolean) {
    var speed = 1f; var vol = 1f; var look = "std"; var bri = 0f; var con = 0f; var sat = 0f; var rot = 0; var mirror = false
    var trans = "none"   // the switch effect at the START of this clip (from the clip before)
    var fileDur = Long.MAX_VALUE; var img = ""; var fx = "none"
    var fi = 0f; var fo = 0f; var fadeVid = true   // fade in / out (seconds) of sound, and of the picture to black
    /* sound level at time t (µs) inside the clip: rises at the start, falls at the end, like a hill */
    fun env(t: Long): Float { var g = 1f; if (fi > 0) g = minOf(g, t / (fi * 1e6f)); if (fo > 0) g = minOf(g, (dur - t) / (fo * 1e6f)); return g.coerceIn(0f, 1f) }
    val dur: Long get() = ((outUs - inUs) / speed).toLong()
    fun copy() = Clip(file, inUs, outUs, still).also { it.speed = speed; it.vol = vol; it.look = look; it.bri = bri; it.con = con; it.sat = sat; it.rot = rot; it.mirror = mirror; it.trans = trans; it.fileDur = fileDur; it.img = img; it.fx = fx; it.fi = fi; it.fo = fo; it.fadeVid = fadeVid }
    fun filter(): String { val l = Looks.ALL.firstOrNull { it.id == look }?.f ?: ""; return "$l brightness(${1 + bri}) contrast(${1 + con}) saturate(${1 + sat})".trim() }
}
/* things on top of the video: text, sticker, photo, brand */
class Layer(var kind: String, var s: Long, var e: Long) {
    var text = ""; var font = "bricolage"; var col = "white"; var style = "boxword"; var anim = "pop"
    var x = 0.5f; var y = 0.78f; var scale = 1f; var file = ""
    fun copy() = Layer(kind, s, e).also { it.text = text; it.font = font; it.col = col; it.style = style; it.anim = anim; it.x = x; it.y = y; it.scale = scale; it.file = file }
}
/* extra sound: music, sound effect, AI voice */
class Sound(var kind: String, var s: Long, var e: Long) {
    var file = ""; var sfx = "ding"; var vol = 1f; var text = ""; var fade = true; var fi = 0.5f; var fo = 0.5f
    fun copy() = Sound(kind, s, e).also { it.file = file; it.sfx = sfx; it.vol = vol; it.text = text; it.fade = fade; it.fi = fi; it.fo = fo }
}

/* ---------------- drawing what sits on top of the video (same for preview and saving) ---------------- */
class TextArt(private val c: Context) {
    companion object {
        val COLS = linkedMapOf("white" to 0xFFFFFFFF.toInt(), "yellow" to 0xFFFFE14D.toInt(), "gold" to 0xFFD9B44A.toInt(), "orange" to 0xFFFF9A3C.toInt(), "red" to 0xFFFF4D4D.toInt(),
            "pink" to 0xFFFF6FB5.toInt(), "purple" to 0xFFA66BFF.toInt(), "blue" to 0xFF4D8BFF.toInt(), "cyan" to 0xFF4DD9FF.toInt(), "green" to 0xFF5BE08A.toInt(), "black" to 0xFF111111.toInt())
        val PARTNER = mapOf("white" to 0xFFFF4D8D.toInt(), "yellow" to 0xFFFF4D4D.toInt(), "gold" to 0xFF7A3CFF.toInt(), "orange" to 0xFF2F6BFF.toInt(), "red" to 0xFFFFE14D.toInt(),
            "pink" to 0xFF4DD9FF.toInt(), "purple" to 0xFFFFE14D.toInt(), "blue" to 0xFFFF6FB5.toInt(), "cyan" to 0xFFFF4D8D.toInt(), "green" to 0xFF2F6BFF.toInt(), "black" to 0xFFFFE14D.toInt())
        val STYLES = listOf("boxword", "outline", "plain", "shadow", "glow", "neon", "gradient", "d3", "bubble", "darkbox", "highlight", "retro")
        val ANIMS = listOf("none", "fade", "pop", "slide", "type", "shake")
        val STICKERS = listOf("🔥", "😋", "❤️", "⭐", "👍", "🎉", "💯", "😍", "🤤", "👉", "✨", "🛒", "📞", "💰", "🍜", "🍗", "🍰", "☕", "🌶️", "✅", "😂", "😱", "🙏", "🆕")
        fun ink(col: Int): Int { val r = Color.red(col); val g = Color.green(col); val b = Color.blue(col); return if ((r * 299 + g * 587 + b * 114) / 1000 > 150) 0xFF111111.toInt() else Color.WHITE }
    }
    private val photos = HashMap<String, Bitmap?>()
    private var brandBmp: Bitmap? = null; private var brandKey = ""
    private fun photo(f: String): Bitmap? = photos.getOrPut(f) { try { val o = BitmapFactory.Options(); o.inSampleSize = 1; BitmapFactory.decodeFile(f, o) } catch (e: Exception) { null } }

    private fun active(l: Layer, t: Long) = t >= l.s && t < l.e
    /* a key that changes only when the picture changes (so pictures are made again only when needed) */
    fun key(list: List<Layer>, t: Long): String {
        val sb = StringBuilder()
        list.forEach { l -> if (active(l, t)) {
            sb.append(l.kind).append(l.text).append(l.font).append(l.col).append(l.style).append(l.x).append(l.y).append(l.scale).append(l.file).append('|')
            val a = t - l.s
            if (l.anim == "shake") sb.append(t / 60_000)
            else if (l.anim == "type") { if (a < typeDur(l)) sb.append(a / 40_000) }
            else if (l.anim != "none" && a < 450_000) sb.append(a / 33_000)
        } }
        return sb.toString()
    }
    private fun typeDur(l: Layer) = minOf(1_500_000L, (l.e - l.s) / 2)

    fun render(list: List<Layer>, t: Long, W: Int, H: Int, rects: MutableMap<Int, RectF>? = null): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        rects?.clear()
        list.forEachIndexed { i, l -> if (active(l, t)) { val r = draw(cv, l, t, W, H); if (r != null) rects?.put(i, r) } }
        return b
    }

    private fun draw(cv: Canvas, l: Layer, t: Long, W: Int, H: Int): RectF? {
        val m = minOf(W, H).toFloat(); val a = t - l.s
        var alpha = 1f; var sc = 1f; var dy = 0f; var dx = 0f
        val p = (a / 450_000f).coerceIn(0f, 1f)
        when (l.anim) {
            "fade" -> alpha = p
            "pop" -> sc = if (p < 0.6f) 0.6f + p / 0.6f * 0.5f else 1.1f - (p - 0.6f) / 0.4f * 0.1f
            "slide" -> { dy = (1 - p) * (1 - p) * m * 0.12f; alpha = p }
            "shake" -> { dx = sin(t / 1e6 * 40).toFloat() * m * 0.006f; dy = sin(t / 1e6 * 33 + 1).toFloat() * m * 0.005f }
        }
        val fadeOut = ((l.e - t) / 200_000f).coerceIn(0f, 1f); if (l.anim != "none") alpha *= fadeOut
        if (alpha <= 0.01f) return null
        val cx = l.x * W + dx; val cy = l.y * H + dy
        cv.save(); cv.translate(cx, cy); cv.scale(sc, sc)
        val sa = cv.saveLayerAlpha(null, (alpha * 255).toInt())
        val r: RectF = when (l.kind) {
            "photo" -> { val bm = photo(l.file); if (bm == null) RectF() else {
                val w = m * 0.42f * l.scale; val h = w * bm.height / bm.width
                val rr = RectF(-w / 2, -h / 2, w / 2, h / 2)
                val sh = Paint(Paint.ANTI_ALIAS_FLAG); sh.color = 0x66000000; sh.maskFilter = BlurMaskFilter(m * 0.02f, BlurMaskFilter.Blur.NORMAL)
                cv.drawRoundRect(RectF(rr.left, rr.top + m * 0.01f, rr.right, rr.bottom + m * 0.01f), m * 0.02f, m * 0.02f, sh)
                val path = android.graphics.Path(); path.addRoundRect(rr, m * 0.02f, m * 0.02f, android.graphics.Path.Direction.CW)
                cv.save(); cv.clipPath(path); cv.drawBitmap(bm, null, rr, Paint(Paint.FILTER_BITMAP_FLAG)); cv.restore(); rr } }
            "brand" -> { cv.restoreToCount(sa); cv.restore(); cv.save()
                val k = "$W/$H"; if (brandBmp == null || brandKey != k) { val bb = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); try { val br = Brand(c); br.refresh(); br.draw(Canvas(bb), W, H) } catch (e: Exception) {}; brandBmp = bb; brandKey = k }
                val pa = Paint(); pa.alpha = (alpha * 255).toInt(); cv.drawBitmap(brandBmp!!, 0f, 0f, pa); cv.restore(); return RectF(0f, 0f, W.toFloat(), H.toFloat()) }
            "sticker" -> { val tp = TextPaint(Paint.ANTI_ALIAS_FLAG); tp.textSize = m * 0.16f * l.scale; tp.textAlign = Paint.Align.CENTER
                val fm = tp.fontMetrics; cv.drawText(l.text, 0f, -(fm.ascent + fm.descent) / 2, tp); val w = tp.measureText(l.text); RectF(-w / 2, -tp.textSize * 0.6f, w / 2, tp.textSize * 0.6f) }
            else -> drawText(cv, l, t, m, W)
        }
        cv.restoreToCount(sa); cv.restore()
        if (r.isEmpty) return null
        return RectF(cx + r.left * sc, cy + r.top * sc, cx + r.right * sc, cy + r.bottom * sc)
    }

    private fun drawText(cv: Canvas, l: Layer, t: Long, m: Float, W: Int): RectF {
        val col = COLS[l.col] ?: Color.WHITE; val part = PARTNER[l.col] ?: 0xFFFF4D8D.toInt()
        val size = m * 0.075f * l.scale
        var txt = if (Overlay.FONTS[l.font]?.caps == true) l.text.uppercase() else l.text
        val full = txt
        if (l.anim == "type") { val n = ((t - l.s).toFloat() / typeDur(l) * full.length).toInt().coerceIn(0, full.length); txt = full.substring(0, n) }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG); tp.typeface = Overlay.face(c, l.font); tp.textSize = size
        val w = (W * 0.88f).toInt()
        fun lay(s: String, p: TextPaint) = StaticLayout.Builder.obtain(s, 0, s.length, p, w).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).setIncludePad(false).build()
        val sizeLay = lay(full, tp)   // the box keeps the full size while typing
        var lw = 0f; for (i in 0 until sizeLay.lineCount) lw = maxOf(lw, sizeLay.getLineWidth(i))
        val h = sizeLay.height.toFloat()
        val left = -w / 2f; val top = -h / 2f
        val pad = size * 0.32f
        val box = RectF(-lw / 2 - pad, top - pad * 0.55f, lw / 2 + pad, top + h + pad * 0.55f)
        fun drawWith(p: TextPaint, ox: Float = 0f, oy: Float = 0f) { val sl = lay(txt, p); cv.save(); cv.translate(left + ox, top + oy); sl.draw(cv); cv.restore() }
        fun stroke(width: Float, color: Int): TextPaint { val s = TextPaint(tp); s.style = Paint.Style.STROKE; s.strokeWidth = width; s.strokeJoin = Paint.Join.ROUND; s.color = color; return s }
        val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        when (l.style) {
            "boxword" -> { bp.color = col; cv.drawRoundRect(box, pad, pad, bp); tp.color = ink(col); drawWith(tp) }
            "darkbox" -> { bp.color = 0xC8000000.toInt(); cv.drawRoundRect(box, pad, pad, bp); tp.color = col; drawWith(tp) }
            "highlight" -> { bp.color = part; cv.drawRect(RectF(box.left, box.top + pad * 0.3f, box.right, box.bottom - pad * 0.3f), bp); tp.color = col; drawWith(stroke(size * 0.08f, 0xFF111111.toInt())); drawWith(tp) }
            "outline" -> { drawWith(stroke(size * 0.16f, if (l.col == "black") Color.WHITE else 0xFF111111.toInt())); tp.color = col; drawWith(tp) }
            "plain" -> { tp.color = col; drawWith(tp) }
            "shadow" -> { tp.color = col; tp.setShadowLayer(size * 0.14f, 0f, size * 0.06f, 0xDD000000.toInt()); drawWith(tp) }
            "glow" -> { val g = TextPaint(tp); g.color = col; g.setShadowLayer(size * 0.45f, 0f, 0f, col); drawWith(g); tp.color = Color.WHITE; drawWith(tp) }
            "neon" -> { val g = stroke(size * 0.1f, col); g.setShadowLayer(size * 0.5f, 0f, 0f, col); drawWith(g); tp.color = 0xFFFFFFFF.toInt(); drawWith(tp) }
            "gradient" -> { drawWith(stroke(size * 0.12f, 0xFF111111.toInt())); tp.shader = LinearGradient(0f, top, 0f, top + h, col, part, Shader.TileMode.CLAMP); drawWith(tp) }
            "d3" -> { val d = TextPaint(tp); d.color = part; for (k in 6 downTo 1) drawWith(d, size * 0.012f * k, size * 0.012f * k); tp.color = col; drawWith(tp) }
            "bubble" -> { drawWith(stroke(size * 0.3f, Color.WHITE)); drawWith(stroke(size * 0.12f, part)); tp.color = col; drawWith(tp) }
            "retro" -> { val d = TextPaint(tp); d.color = part; drawWith(d, size * 0.07f, size * 0.07f); drawWith(stroke(size * 0.06f, 0xFF111111.toInt())); tp.color = col; drawWith(tp) }
            else -> { tp.color = col; drawWith(tp) }
        }
        return box
    }
}

/* ---------------- sound: effects, music, voice, all mixed into one extra track ---------------- */
object Snd {
    const val RATE = 48000
    val SFX = listOf("whoosh", "ding", "pop", "clap", "cash", "drum")
    private val sfxCache = HashMap<String, FloatArray>()
    fun sfx(k: String): FloatArray = sfxCache.getOrPut(k) {
        val r = RATE.toFloat(); val rnd = java.util.Random(7)
        fun arr(sec: Float, f: (Int, Float) -> Float) = FloatArray((sec * r).toInt()) { i -> f(i, i / r) }
        when (k) {
            "whoosh" -> { var lp = 0f; arr(0.55f) { _, t -> val env = sin(PI * t / 0.55).toFloat(); val a = 0.04f + 0.25f * env; lp += a * ((rnd.nextFloat() * 2 - 1) - lp); lp * env * 1.6f } }
            "ding" -> arr(1.1f) { _, t -> ((sin(2 * PI * 1318.5 * t) * 0.6 + sin(2 * PI * 2637 * t) * 0.25 + sin(2 * PI * 3955 * t) * 0.1) * exp(-t * 4.5)).toFloat() * 0.7f }
            "pop" -> { var ph = 0.0; arr(0.12f) { _, t -> val f = 900 * exp(-t * 30.0) + 150; ph += 2 * PI * f / r; (sin(ph) * exp(-t * 35.0)).toFloat() * 0.9f } }
            "clap" -> arr(0.35f) { _, t -> val n = rnd.nextFloat() * 2 - 1; var e = 0f; for (o in floatArrayOf(0f, 0.012f, 0.026f)) if (t >= o) e = maxOf(e, exp(-(t - o) * (if (o == 0.026f) 18f else 70f))); n * e * 0.8f }
            "cash" -> arr(0.9f) { _, t -> val bell = (sin(2 * PI * 2093 * t) * 0.5 * exp(-t * 6) + (if (t > 0.12) sin(2 * PI * 2637 * (t - 0.12)) * 0.5 * exp(-(t - 0.12) * 6) else 0.0)).toFloat()
                val ch = if (t < 0.08) (rnd.nextFloat() * 2 - 1) * exp(-t * 40.0).toFloat() * 0.5f else 0f; (bell + ch) * 0.7f }
            else -> { var ph = 0.0; arr(0.45f) { _, t -> val f = 50 + 110 * exp(-t * 25.0); ph += 2 * PI * f / r; val kick = sin(ph) * exp(-t * 7.0)
                val sn = if (t > 0.22) (rnd.nextFloat() * 2 - 1) * exp(-(t - 0.22) * 25.0) * 0.4 else 0.0; (kick + sn).toFloat() * 0.85f } }
        }
    }
    /* any sound file (music, voice) → mono 48 kHz, at most maxSec seconds */
    fun decode(path: String, maxSec: Int = 300): FloatArray? {
        if (path.endsWith(".wav", true)) return readWav(File(path))
        val ex = MediaExtractor(); try { ex.setDataSource(path) } catch (e: Exception) { return null }
        var ti = -1; for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; break }
        if (ti < 0) { ex.release(); return null }
        ex.selectTrack(ti); val fmt = ex.getTrackFormat(ti)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); dec.configure(fmt, null, null, 0); dec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var out = FloatArray(RATE * 30); var n = 0; val info = MediaCodec.BufferInfo(); var inEnd = false; var guard = 0
        try {
            while (guard++ < 2_000_000) {
                if (!inEnd) { val i = dec.dequeueInputBuffer(5000); if (i >= 0) { val sz = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                    if (sz < 0 || ex.sampleTime > maxSec * 1_000_000L) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true } else { dec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() } } }
                val o = dec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val f = dec.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                else if (o >= 0) {
                    val bb = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN); bb.position(info.offset); bb.limit(info.offset + info.size)
                    val sb = bb.asShortBuffer(); val frames = sb.remaining() / ch
                    if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                    for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get(f * ch + c); out[n++] = s / ch / 32768f }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {} finally { try { dec.stop() } catch (e: Exception) {}; dec.release(); ex.release() }
        return resample(out.copyOf(n), rate)
    }
    fun resample(a: FloatArray, rate: Int): FloatArray {
        if (rate == RATE || a.isEmpty()) return a
        val n = (a.size.toLong() * RATE / rate).toInt(); val k = rate.toDouble() / RATE
        return FloatArray(n) { i -> val x = i * k; val j = x.toInt(); val f = (x - j).toFloat(); if (j + 1 < a.size) a[j] * (1 - f) + a[j + 1] * f else a[minOf(j, a.size - 1)] }
    }
    fun readWav(f: File): FloatArray? = try {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var ch = 1; var rate = 22050; var bits = 16; var data: FloatArray? = null
        b.position(12)
        while (b.remaining() >= 8) {
            val id = String(ByteArray(4).also { b.get(it) }); val sz = b.int; val st = b.position()
            if (id == "fmt ") { b.short; ch = b.short.toInt(); rate = b.int; b.int; b.short; bits = b.short.toInt() }
            else if (id == "data" && bits == 16) { val frames = minOf(sz, b.remaining()) / 2 / ch; data = FloatArray(frames) { i -> var s = 0f; for (c in 0 until ch) s += b.getShort(st + (i * ch + c) * 2); s / ch / 32768f } }
            b.position(minOf(b.limit(), st + sz + (sz and 1)))
        }
        data?.let { resample(it, rate) }
    } catch (e: Exception) { null }

    /* mix every extra sound into one WAV the length of the video */
    fun mix(sounds: List<Sound>, totalUs: Long, out: File, cache: MutableMap<String, FloatArray?>) {
        val total = (totalUs * RATE / 1_000_000).toInt().coerceAtLeast(1)
        val src = sounds.map { s -> s to when (s.kind) { "sfx" -> sfx(s.sfx); else -> cache.getOrPut(s.file) { decode(s.file) } } }
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0); raf.write(wavHeader(total))
            val blk = RATE; val buf = FloatArray(blk); val bytes = ByteBuffer.allocate(blk * 2).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (pos < total) {
                val n = minOf(blk, total - pos); java.util.Arrays.fill(buf, 0f)
                src.forEach { (s, a) -> if (a != null && a.isNotEmpty()) {
                    val st = (s.s * RATE / 1_000_000).toInt(); val en = minOf(total, (s.e * RATE / 1_000_000).toInt())
                    val from = maxOf(pos, st); val to = minOf(pos + n, en)
                    for (i in from until to) { val k = i - st
                        val v = if (s.kind == "music") a[k % a.size] else if (k < a.size) a[k] else 0f
                        var g = s.vol; if (s.kind == "music" && s.fade) { val fiN = (s.fi * RATE).toInt(); val foN = (s.fo * RATE).toInt()
                            if (fiN > 0 && k < fiN) g *= k / fiN.toFloat(); if (foN > 0 && en - i < foN) g *= (en - i) / foN.toFloat() }
                        buf[i - pos] += v * g } } }
                bytes.clear(); for (i in 0 until n) bytes.putShort((buf[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                raf.write(bytes.array(), 0, n * 2); pos += n
            }
        }
    }
    private fun wavHeader(frames: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN); val data = frames * 2
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(data); return b.array()
    }
    fun durUs(a: FloatArray?) = if (a == null) 0L else a.size * 1_000_000L / RATE
    @Suppress("unused") fun peak(a: FloatArray) = a.maxOfOrNull { abs(it) } ?: 0f
}

/* ---------------- switch effects between clips and moving effects on a clip (shared by preview and saving) ---------------- */
object Fx {
    val TRANS = listOf("none", "fade", "black", "white", "flash", "slide", "slideup", "zoom", "whip", "wipe", "circle", "doors")
    val EFFECTS = listOf("none", "zoomin", "zoomout", "panl", "panr", "shake", "pulse", "sway", "blink")
    fun transName(k: String) = when (k) { "none" -> Ui.t("None", "Tiada"); "fade" -> Ui.t("Fade", "Pudar"); "black" -> Ui.t("Dip to black", "Gelap"); "white" -> Ui.t("Dip to white", "Putih")
        "flash" -> Ui.t("Flash", "Kilat"); "slide" -> Ui.t("Slide", "Gelongsor"); "slideup" -> Ui.t("Slide up", "Gelongsor naik"); "zoom" -> "Zoom"; "whip" -> Ui.t("Whip", "Libas")
        "wipe" -> Ui.t("Wipe", "Sapu"); "circle" -> Ui.t("Circle", "Bulatan"); else -> Ui.t("Doors", "Pintu") }
    fun fxName(k: String) = when (k) { "none" -> Ui.t("None", "Tiada"); "zoomin" -> Ui.t("Zoom in", "Zum masuk"); "zoomout" -> Ui.t("Zoom out", "Zum keluar"); "panl" -> Ui.t("Pan left", "Gerak kiri")
        "panr" -> Ui.t("Pan right", "Gerak kanan"); "shake" -> Ui.t("Shake", "Gegar"); "pulse" -> Ui.t("Pulse", "Denyut"); "sway" -> Ui.t("Sway", "Hayun"); else -> Ui.t("Flash beat", "Kilat rentak") }
    /* half of the switch time, on each side of the join */
    fun win(k: String): Long = when (k) { "none" -> 0L; "flash", "whip" -> 150_000L; "fade", "zoom" -> 190_000L; "black" -> 240_000L; "slide", "slideup" -> 180_000L; else -> 220_000L }
    class St { var s = 1f; var tx = 0f; var ty = 0f; var rot = 0f; var dark = 0f; var white = 0f; var mask = ""; var vis = 1f; var maskIn = true }
    private fun ease(q: Float) = 1 - (1 - q) * (1 - q) * (1 - q)
    /* t: time inside this clip (as played), dur: clip length; inT: effect from the clip before; outT: effect into the next clip */
    fun state(eff: String, inT: String, outT: String, t: Long, dur: Long, fadeIn: Float = 0f, fadeOut: Float = 0f): St {
        val st = St(); val sec = t / 1e6f; val p = if (dur > 0) (t.toFloat() / dur).coerceIn(0f, 1f) else 0f
        // the clip's own fade in / out to black
        var g = 1f; if (fadeIn > 0) g = minOf(g, t / (fadeIn * 1e6f)); if (fadeOut > 0) g = minOf(g, (dur - t) / (fadeOut * 1e6f)); st.dark = 1 - g.coerceIn(0f, 1f)
        when (eff) {
            "zoomin" -> st.s = 1f + 0.15f * p
            "zoomout" -> st.s = 1.15f - 0.15f * p
            "panl" -> { st.s = 1.15f; st.tx = 0.13f - 0.26f * p }
            "panr" -> { st.s = 1.15f; st.tx = -0.13f + 0.26f * p }
            "shake" -> { st.s = 1.06f; st.tx = 0.02f * sin(sec * 40.0).toFloat(); st.ty = 0.02f * sin(sec * 33.0 + 1).toFloat() }
            "pulse" -> st.s = 1f + 0.05f * (0.5f + 0.5f * sin(2 * PI * sec * 2).toFloat())
            "sway" -> { st.s = 1.08f; st.rot = 2.5f * sin(2 * PI * sec * 0.5).toFloat() }
            "blink" -> { val f = sec % 1f; if (f < 0.12f) st.white = 0.5f * (1 - f / 0.12f) }
        }
        fun apply(k: String, q0: Float, incoming: Boolean) {
            val q = q0.coerceIn(0f, 1f); val e = ease(q); val dir = if (incoming) 1f else -1f
            when (k) {
                "fade", "black" -> st.dark = maxOf(st.dark, 1 - q)
                "white", "flash" -> st.white = maxOf(st.white, 1 - q)
                "slide" -> st.tx += dir * 2f * (1 - e)
                "slideup" -> st.ty += -dir * 2f * (1 - e)
                "zoom" -> { st.s *= 1 + 0.6f * (1 - q); st.dark = maxOf(st.dark, (1 - q) * 0.5f) }
                "whip" -> { st.tx += dir * 2f * (1 - e); st.white = maxOf(st.white, (1 - q) * 0.25f) }
                "wipe", "circle", "doors" -> { st.mask = k; st.vis = q; st.maskIn = incoming }
            }
        }
        val wi = win(inT); if (wi > 0 && t < wi) apply(inT, t.toFloat() / wi, true)
        val wo = win(outT); if (wo > 0 && dur - t < wo) apply(outT, (dur - t).toFloat() / wo, false)
        return st
    }
    /* black parts of a wipe / circle / doors switch */
    fun drawMask(c: Canvas, st: St, W: Float, H: Float) {
        if (st.mask.isEmpty() || st.vis >= 1f) return
        val p = Paint(); p.color = Color.BLACK; val v = st.vis
        when (st.mask) {
            "wipe" -> if (st.maskIn) c.drawRect(v * W, 0f, W, H, p) else c.drawRect(0f, 0f, (1 - v) * W, H, p)
            "doors" -> { val w = (1 - v) * W / 2; c.drawRect(0f, 0f, w, H, p); c.drawRect(W - w, 0f, W, H, p) }
            "circle" -> { val path = android.graphics.Path(); path.fillType = android.graphics.Path.FillType.EVEN_ODD
                path.addRect(0f, 0f, W, H, android.graphics.Path.Direction.CW); path.addCircle(W / 2, H / 2, v * kotlin.math.hypot(W / 2, H / 2), android.graphics.Path.Direction.CW); c.drawPath(path, p) }
        }
    }
}
