package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/* ---------------- keyframes: zoom / move that change over time ---------------- */
/* one point: t = time from the start of the clip or layer (µs); s = size; x, y = move (part of the picture, right/down are +) */
class Kf(val t: Long, val s: Float, val x: Float, val y: Float)
object Keys {
    fun parse(s: String): List<Kf> = if (s.isBlank()) emptyList() else s.split(';').mapNotNull { p -> val a = p.split(','); if (a.size < 4) null else try { Kf(a[0].toLong(), a[1].toFloat(), a[2].toFloat(), a[3].toFloat()) } catch (e: Exception) { null } }.sortedBy { it.t }
    fun str(l: List<Kf>) = l.sortedBy { it.t }.joinToString(";") { String.format(Locale.US, "%d,%.4f,%.4f,%.4f", it.t, it.s, it.x, it.y) }
    /* the value at time t: smooth between points, held before the first and after the last */
    fun at(l: List<Kf>, t: Long): Kf? {
        if (l.isEmpty()) return null
        if (t <= l.first().t) return l.first(); if (t >= l.last().t) return l.last()
        for (i in 0 until l.size - 1) { val a = l[i]; val b = l[i + 1]
            if (t >= a.t && t <= b.t) { val p0 = (t - a.t).toFloat() / max(1L, b.t - a.t); val p = p0 * p0 * (3 - 2 * p0)
                return Kf(t, a.s + (b.s - a.s) * p, a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p) } }
        return l.last()
    }
    /* add a point, or change the one already near this time */
    fun put(s: String, k: Kf): String { val l = parse(s).filter { abs(it.t - k.t) > 120_000 }.toMutableList(); l.add(k); return str(l) }
    fun drop(s: String, t: Long): String = str(parse(s).filter { abs(it.t - t) > 120_000 })
}
/* a layer as it is at time t (its keyframes give x, y and size) */
fun Layer.at(t: Long): Layer { if (kf.isBlank()) return this; val k = Keys.at(Keys.parse(kf), t - s) ?: return this
    return copy().also { it.x = (0.5f + k.x).coerceIn(0.02f, 0.98f); it.y = (0.5f + k.y).coerceIn(0.02f, 0.98f); it.scale = k.s; it.kf = "" } }

/* ---------------- stabilise: find the shake, then move the picture against it ---------------- */
object Stab {
    const val ZOOM = 1.12f   // a little zoom so the moved edges are not seen
    private val cache = HashMap<String, FloatArray>()
    fun file(c: Context, src: File) = File(File(c.filesDir, "edit").apply { mkdirs() }, "stab_" + src.name + ".txt")
    fun has(c: Context, src: File) = file(c, src).exists()
    /* data: t(µs), dx, dy (part of the width / height) */
    fun load(c: Context, src: File): FloatArray? = cache[src.absolutePath] ?: try {
        val v = file(c, src).readText().split('\n').filter { it.isNotBlank() }.flatMap { it.split(',').map { x -> x.toFloat() } }.toFloatArray(); cache[src.absolutePath] = v; v } catch (e: Exception) { null }
    fun at(d: FloatArray?, us: Long): Pair<Float, Float> {
        if (d == null || d.size < 3) return 0f to 0f
        val n = d.size / 3; val t = us.toFloat()
        if (t <= d[0]) return d[1] to d[2]; if (t >= d[(n - 1) * 3]) return d[(n - 1) * 3 + 1] to d[(n - 1) * 3 + 2]
        var lo = 0; var hi = n - 1; while (hi - lo > 1) { val m = (lo + hi) / 2; if (d[m * 3] <= t) lo = m else hi = m }
        val p = (t - d[lo * 3]) / max(1f, d[hi * 3] - d[lo * 3])
        return (d[lo * 3 + 1] + (d[hi * 3 + 1] - d[lo * 3 + 1]) * p) to (d[lo * 3 + 2] + (d[hi * 3 + 2] - d[lo * 3 + 2]) * p)
    }
    /* look at the whole video (small grey pictures, 15 a second) and work out how to hold it steady */
    fun analyse(c: Context, src: File, wantW: Int, wantH: Int, prog: (Int) -> Unit): Boolean {
        val r = MediaMetadataRetriever(); r.setDataSource(src.absolutePath)
        val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) * 1000
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        val count = if (Build.VERSION.SDK_INT >= 28) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0 else 0
        if (durUs <= 0) { r.release(); return false }
        val step = 66_666L; val n = (durUs / step).toInt().coerceIn(2, 15 * 600)
        val GW = 128; val GH = max(8, GW * wantH / max(1, wantW))
        fun gray(b0: Bitmap?): IntArray? {
            if (b0 == null) return null
            val b1 = if (rot != 0 && (b0.width > b0.height) != (wantW > wantH)) Bitmap.createBitmap(b0, 0, 0, b0.width, b0.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else b0
            val s = Bitmap.createScaledBitmap(b1, GW, GH, true); val px = IntArray(GW * GH); s.getPixels(px, 0, GW, 0, 0, GW, GH)
            if (s !== b1) s.recycle(); if (b1 !== b0) b1.recycle()
            return IntArray(px.size) { val p = px[it]; ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10 }
        }
        val frames = arrayOfNulls<IntArray>(n)
        if (Build.VERSION.SDK_INT >= 28 && count > 0) {
            val prm = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
            val idx = IntArray(n) { ((it * step).toDouble() / durUs * count).toInt().coerceIn(0, count - 1) }
            var j = 0
            while (j < n) { var e = j; while (e + 1 < n && idx[e + 1] - idx[j] < 24) e++
                val got = try { r.getFramesAtIndex(idx[j], idx[e] - idx[j] + 1, prm) } catch (x: Exception) { null }
                for (q in j..e) { val b = got?.getOrNull(idx[q] - idx[j]) ?: try { r.getFrameAtTime(q * step, MediaMetadataRetriever.OPTION_CLOSEST) } catch (x: Exception) { null }; frames[q] = gray(b) }
                got?.forEach { it.recycle() }; j = e + 1; prog(j * 70 / n) }
        } else for (q in 0 until n) { val b = try { r.getFrameAtTime(q * step, MediaMetadataRetriever.OPTION_CLOSEST) } catch (x: Exception) { null }; frames[q] = gray(b); b?.recycle(); prog((q + 1) * 70 / n) }
        r.release()
        // shift between each picture and the next (best match within ±8 px)
        val R = 8; val tx = FloatArray(n); val ty = FloatArray(n)
        for (q in 1 until n) {
            val a = frames[q - 1]; val b = frames[q]
            var bx = 0; var by = 0
            if (a != null && b != null) { var best = Long.MAX_VALUE
                for (dy in -R..R) for (dx in -R..R) { var sum = 0L
                    var y = R; while (y < GH - R) { var x = R; val ro = y * GW; val rb = (y + dy) * GW
                        while (x < GW - R) { sum += abs(a[ro + x] - b[rb + x + dx]); x += 2 }; y += 2 }
                    if (sum < best) { best = sum; bx = dx; by = dy } } }
            tx[q] = tx[q - 1] + bx; ty[q] = ty[q - 1] + by
            if (q % 20 == 0) prog(70 + q * 28 / n)
        }
        // keep the slow, wanted movement; remove the quick shake
        val W = 8; val out = StringBuilder(); val lim = (ZOOM - 1f) / 2f * 0.92f
        for (q in 0 until n) { var sx = 0f; var sy = 0f; var k = 0
            for (o in -W..W) { val i = (q + o).coerceIn(0, n - 1); sx += tx[i]; sy += ty[i]; k++ }
            val cx = ((sx / k - tx[q]) / GW).coerceIn(-lim, lim); val cy = ((sy / k - ty[q]) / GH).coerceIn(-lim, lim)
            out.append(q * step).append(',').append(String.format(Locale.US, "%.5f,%.5f", cx, cy)).append('\n') }
        file(c, src).writeText(out.toString()); cache.remove(src.absolutePath); prog(100)
        return true
    }
}

/* zoom / move of a clip from its keyframes, and the steadying (time from the clip's first picture) */
class KfFx(private val kf: List<Kf>, private val stab: FloatArray?, private val inUs: Long, private val speed: Float) : MatrixTransformation {
    private var first = -1L
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (first < 0) first = presentationTimeUs
        val t = presentationTimeUs - first; val m = Matrix()
        if (stab != null) { val (cx, cy) = Stab.at(stab, inUs + (t * speed).toLong()); m.postTranslate(2 * cx, -2 * cy); m.postScale(Stab.ZOOM, Stab.ZOOM) }
        Keys.at(kf, t)?.let { k -> m.postScale(k.s, k.s); m.postTranslate(2 * k.x, -2 * k.y) }
        return m
    }
}

/* ---------------- picture effects drawn on the phone's graphics chip ---------------- */
private const val VS = "attribute vec4 aFramePosition; varying vec2 vTex; void main(){ gl_Position = aFramePosition; vTex = (aFramePosition.xy + 1.0) * 0.5; }"

/* green (or any colour) screen: that colour becomes see-through */
class ChromaKey(private val col: Int, private val tol: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Prog(col, tol, useHdr)
    private class Prog(col: Int, private val tol: Float, hdr: Boolean) : BaseGlShaderProgram(hdr, 1) {
        private val p = GlProgram(VS, """precision mediump float; uniform sampler2D uTex; uniform vec3 uKey; uniform float uTol; varying vec2 vTex;
vec2 cc(vec3 c){ return vec2(-0.169*c.r - 0.331*c.g + 0.5*c.b, 0.5*c.r - 0.419*c.g - 0.081*c.b); }
void main(){ vec4 c = texture2D(uTex, vTex); float d = distance(cc(c.rgb), cc(uKey));
  float a = smoothstep(uTol, uTol + 0.07, d);
  vec3 rgb = c.rgb; float l = dot(rgb, vec3(0.299, 0.587, 0.114));
  rgb = mix(vec3(l), rgb, clamp(a * 1.4, 0.0, 1.0));   // takes the coloured edge away
  gl_FragColor = vec4(rgb, a * c.a); }""")
        private val key = floatArrayOf((col shr 16 and 255) / 255f, (col shr 8 and 255) / 255f, (col and 255) / 255f)
        init { p.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE) }
        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            p.use(); p.setSamplerTexIdUniform("uTex", inputTexId, 0); p.setFloatsUniform("uKey", key); p.setFloatUniform("uTol", tol)
            p.bindAttributesAndUniforms(); GLES20.glClearColor(0f, 0f, 0f, 0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { super.release(); try { p.delete() } catch (e: Exception) {} }
    }
}

/* the whole picture in the middle, with a blurred bigger copy behind it instead of black bars */
class BlurFit(private val ow: Int, private val oh: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Prog(ow, oh, useHdr)
    private class Prog(private val ow: Int, private val oh: Int, hdr: Boolean) : BaseGlShaderProgram(hdr, 1) {
        private var iw = 1; private var ih = 1
        private val p = GlProgram(VS, """precision mediump float; uniform sampler2D uTex; uniform vec2 uIn; uniform vec2 uOut; varying vec2 vTex;
void main(){
  float k = min(uOut.x / uIn.x, uOut.y / uIn.y); vec2 fg = uIn * k / uOut; vec2 q = (vTex - 0.5) / fg + 0.5;
  if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) { gl_FragColor = texture2D(uTex, q); return; }
  float c = max(uOut.x / uIn.x, uOut.y / uIn.y); vec2 bs = uIn * c / uOut; vec2 u = (vTex - 0.5) / bs + 0.5;
  vec3 s = vec3(0.0); float w = 0.0;
  for (int i = -3; i <= 3; i++) for (int j = -3; j <= 3; j++) { float g = 1.0 / (1.0 + float(i*i + j*j) * 0.25);
    s += texture2D(uTex, clamp(u + vec2(float(i), float(j)) * 0.018, 0.0, 1.0)).rgb * g; w += g; }
  gl_FragColor = vec4(s / w * 0.62, 1.0); }""")
        init { p.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE) }
        override fun configure(inputWidth: Int, inputHeight: Int): Size { iw = inputWidth; ih = inputHeight; return Size(ow, oh) }
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            p.use(); p.setSamplerTexIdUniform("uTex", inputTexId, 0); p.setFloatsUniform("uIn", floatArrayOf(iw.toFloat(), ih.toFloat())); p.setFloatsUniform("uOut", floatArrayOf(ow.toFloat(), oh.toFloat()))
            p.bindAttributesAndUniforms(); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        override fun release() { super.release(); try { p.delete() } catch (e: Exception) {} }
    }
}

/* ---------------- clean sound: cuts the low rumble and lowers the hiss when no one is talking ---------------- */
class Denoise : BaseAudioProcessor() {
    private var ch = 1; private var hpX = FloatArray(1); private var hpY = FloatArray(1); private var a = 0.99f
    private var env = 0f; private var floor = 0.01f; private var g = 1f; private var att = 0f; private var rel = 0f; private var rise = 1f
    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        ch = f.channelCount; hpX = FloatArray(ch); hpY = FloatArray(ch)
        val dt = 1f / f.sampleRate; val rc = 1f / (2 * Math.PI.toFloat() * 90f); a = rc / (rc + dt)
        att = 1f - Math.exp(-1.0 / (0.005 * f.sampleRate)).toFloat(); rel = 1f - Math.exp(-1.0 / (0.12 * f.sampleRate)).toFloat()
        rise = 1f + (ln(2.0) / (2.5 * f.sampleRate)).toFloat(); return f
    }
    override fun queueInput(b: ByteBuffer) {
        val out = replaceOutputBuffer(b.remaining()); b.order(ByteOrder.nativeOrder())
        val fr = FloatArray(ch)
        while (b.remaining() >= 2 * ch) {
            var lv = 0f
            for (k in 0 until ch) { val x = b.short / 32768f; val y = a * (hpY[k] + x - hpX[k]); hpX[k] = x; hpY[k] = y; fr[k] = y; lv = max(lv, abs(y)) }
            env += (lv - env) * (if (lv > env) 0.01f else 0.0015f)
            floor = if (env < floor) env + (floor - env) * 0.999f else floor * rise; floor = floor.coerceIn(0.0005f, 0.2f)
            val want = ((env / floor - 2f) / 2.5f).coerceIn(0.12f, 1f)
            g += (want - g) * (if (want > g) att else rel)
            for (k in 0 until ch) out.putShort((fr[k] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }
    override fun onReset() { env = 0f; floor = 0.01f; g = 1f; hpX.fill(0f); hpY.fill(0f) }
}

/* ---------------- music beats ---------------- */
object Beats {
    class Res(val period: Double, val phase: Double) { val bpm get() = 60.0 / period }
    /* a = sound at Snd.RATE (one channel). Finds the steady beat: the time between beats and when the first one falls. */
    fun find(a: FloatArray, rate: Int = Snd.RATE): Res? {
        val hop = rate / 100; val n = min(a.size, rate * 40) / hop; if (n < 300) return null
        val e = FloatArray(n) { f -> var s = 0f; val o = f * hop; for (i in o until o + hop) s += a[i] * a[i]; ln(1e-6f + s).toFloat() }
        val on = FloatArray(n) { if (it == 0) 0f else max(0f, e[it] - e[it - 1]) }
        val mean = on.average().toFloat(); for (i in on.indices) on[i] = max(0f, on[i] - mean)
        var bestL = 50.0; var best = -1.0
        for (L in 33..86) { var s = 0.0; for (i in 0 until n - L) s += on[i] * on[i + L]
            val bpm = 6000.0 / L; val w = Math.exp(-Math.pow(ln(bpm / 118.0) / 0.5, 2.0) / 2); s *= w   // prefer about 120 beats a minute
            if (s > best) { best = s; bestL = L.toDouble() } }
        // finer: try a little either side
        var fine = bestL; var fb = -1.0; var x = bestL - 1.0
        while (x <= bestL + 1.0) { var s = 0.0; var k = 0; var p = 0.0; while (p + x < n) { val i = (p + x).toInt(); s += on[(p).toInt()] * on[i]; p += 1.0; k++ }; if (s > fb) { fb = s; fine = x }; x += 0.1 }
        var ph = 0; var pb = -1.0
        for (p in 0 until fine.toInt()) { var s = 0.0; var t = p.toDouble(); while (t < n) { s += on[t.toInt()]; t += fine }; if (s > pb) { pb = s; ph = p } }
        return Res(fine / 100.0, ph / 100.0)
    }
}
