package com.promulticam.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/* The program picture, made on the GPU: chosen angle(s) + colour style + switch effect + brand.
   The same picture goes to the screen and (while recording) to the hardware encoder. */
class Program(@Volatile var W: Int, @Volatile var H: Int) {
    private val th = HandlerThread("pmc-program").apply { start() }
    private val h = Handler(th.looper)
    private var egl0: EglBase? = null
    private var pv: EglBase? = null; private var pvW = 0; private var pvH = 0
    private var encEgl: EglBase? = null; private var enc: Encoder? = null
    private val look = LookDrawer()
    private val fade = LookDrawer()
    private val plain = GlRectDrawer()
    private val fd1 = VideoFrameDrawer(); private val fd2 = VideoFrameDrawer()
    private val mat = Matrix()
    private val fbo = IntArray(2); private val tex = IntArray(2); private var cur = 0; private var fbW = 0; private var fbH = 0
    private var hasFrozen = false
    private var ovTex = 0
    @Volatile private var ovPending: Bitmap? = null
    @Volatile var layout = "full"
    @Volatile var trans = "fade"          // same list as the editor (Fx.TRANS); "none"/"cut" = straight cut
    @Volatile var vRect: FloatArray? = null     // polaroid / panel: the video uses only part of the picture (x, y, w, h from the top, kind)
    @Volatile var zoomMain = 1f; @Volatile var zoomSec = 1f   // digital zoom per view (works for any camera)
    private var transAt = 0L
    private var second: VideoFrame? = null
    private var lastTs = -1L; private var outNs = 0L
    private val inFlight = AtomicInteger(0)
    @Volatile var fps = 0; private var fpsN = 0; private var fpsT = 0L

    val mainSink = VideoSink { f -> onMain(f) }
    val secSink = VideoSink { f -> f.retain(); h.post { second?.release(); second = f } }

    init {
        h.post {
            egl0 = EglBase.create(Rtc.egl.eglBaseContext, EglBase.CONFIG_PIXEL_BUFFER).also { it.createDummyPbufferSurface(); it.makeCurrent() }
            makeFbos()
        }
    }

    fun setLook(f: String) { h.post { lookTo = null; lookF = f; look.setLook(f) } }
    /* change the style slowly (about 1.5 s) instead of in one jump */
    fun setLookSmooth(f: String) { h.post { if (f == lookF) return@post; lookFrom = lookF; lookTo = f; lookAt = SystemClock.uptimeMillis() } }
    private var lookF = ""; private var lookFrom = ""; private var lookTo: String? = null; private var lookAt = 0L
    /* a tiny copy of the camera picture (before any style) for Auto to judge the scene */
    @Volatile private var statCb: ((ByteArray) -> Unit)? = null
    fun sample(cb: (ByteArray) -> Unit) { statCb = cb }
    private var stFbo = 0; private var stTex = 0
    private fun stat(f: VideoFrame) {
        val cb = statCb ?: return; statCb = null
        if (stFbo == 0) { val a = IntArray(1); GLES20.glGenTextures(1, a, 0); stTex = a[0]; GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 32, 32, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glGenFramebuffers(1, a, 0); stFbo = a[0]; GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, stFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, stTex, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0) }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, stFbo)
        try { fd2Stat.drawFrame(f, plain, null, 0, 0, 32, 32) } catch (e: Exception) {}
        val bb = java.nio.ByteBuffer.allocateDirect(32 * 32 * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, 32, 32, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        val out = ByteArray(32 * 32 * 4); bb.rewind(); bb.get(out); Thread { cb(out) }.start()
    }
    private val fd2Stat = VideoFrameDrawer()
    fun setOverlay(b: Bitmap?) { ovShown = b; ovPending = b ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
    @Volatile var ovShown: Bitmap? = null
    /* the next finished picture (look applied, no brand) as a bitmap, upright */
    @Volatile private var snapCb: ((Bitmap?) -> Unit)? = null
    fun snap(cb: (Bitmap?) -> Unit) { snapCb = cb }
    fun clearSecond() { h.post { second?.release(); second = null } }
    fun resize(w: Int, h0: Int) { W = w; H = h0; h.post { makeFbos() } }

    /* a switch happened: keep the last picture to fade from */
    /* the effect for this switch: "auto" picks a different one each time */
    private var eff = "fade"; private val AUTO = listOf("fade", "black", "white", "flash", "slide", "slideup", "zoom", "whip", "wipe", "circle", "doors", "leak", "bands", "reveal", "tilt")
    private fun pickEff() { eff = if (trans == "auto") AUTO.filter { it != eff }.random() else trans }
    fun switched() { h.post { if (fbW > 0) { cur = 1 - cur; hasFrozen = true; transAt = SystemClock.uptimeMillis(); pickEff() } } }

    /* continue / resume: keep the last recorded picture, then play the switch effect from it into the new part */
    fun holdLast() { h.post { if (fbW > 0) { cur = 1 - cur; hasFrozen = false } } }
    fun fadeIn() { h.post { if (fbW > 0) { hasFrozen = true; transAt = SystemClock.uptimeMillis(); pickEff() } } }
    fun fromStill(b: Bitmap) {
        h.post {
            val e = egl0 ?: return@post; e.makeCurrent(); if (fbW != W || fbH != H) makeFbos()
            val a = IntArray(1); GLES20.glGenTextures(1, a, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            cur = 1 - cur
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[1 - cur]); GLES20.glViewport(0, 0, W, H)
            plain.drawRgb(a[0], FLIP, W, H, 0, 0, W, H)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glDeleteTextures(1, a, 0)
            hasFrozen = false
        }
    }

    fun setPreview(s: Surface?, w: Int, hh: Int) {
        val l = CountDownLatch(1)
        h.post {
            pv?.release(); pv = null
            if (s != null) try { pv = EglBase.create(egl0!!.eglBaseContext, EglBase.CONFIG_PLAIN).also { it.createSurface(s)
                // do not wait for the screen refresh when showing the picture: the recording must never wait for the screen
                try { it.makeCurrent(); android.opengl.EGL14.eglSwapInterval(android.opengl.EGL14.eglGetCurrentDisplay(), 0); egl0?.makeCurrent() } catch (e: Exception) {} }; pvW = w; pvH = hh } catch (e: Exception) { pv = null }
            l.countDown()
        }
        l.await(1500, TimeUnit.MILLISECONDS)
    }

    fun attachEncoder(e: Encoder) { h.post { lastTs = -1; outNs = 0; try { encEgl = EglBase.create(egl0!!.eglBaseContext, EglBase.CONFIG_RECORDABLE).also { it.createSurface(e.surface) }; enc = e } catch (x: Exception) { enc = null } } }
    fun detachEncoder(done: () -> Unit) { h.post { encEgl?.release(); encEgl = null; enc = null; done() } }

    fun release() {
        h.post {
            second?.release(); second = null
            pv?.release(); encEgl?.release()
            egl0?.makeCurrent()
            look.release(); fade.release(); plain.release(); fd1.release(); fd2.release()
            GLES20.glDeleteFramebuffers(2, fbo, 0); GLES20.glDeleteTextures(2, tex, 0)
            if (ovTex != 0) GLES20.glDeleteTextures(1, intArrayOf(ovTex), 0)
            egl0?.release(); egl0 = null
            th.quitSafely()
        }
    }

    private fun onMain(f: VideoFrame) {
        if (inFlight.get() > 1) return          // GPU busy: skip this picture rather than fall behind
        f.retain(); inFlight.incrementAndGet()
        h.post { try { render(f) } catch (e: Exception) {}; f.release(); inFlight.decrementAndGet() }
    }

    private fun makeFbos() {
        val e = egl0 ?: return; e.makeCurrent()
        if (fbW > 0) { GLES20.glDeleteFramebuffers(2, fbo, 0); GLES20.glDeleteTextures(2, tex, 0) }
        GLES20.glGenTextures(2, tex, 0); GLES20.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, W, H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            for (p in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, p, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        fbW = W; fbH = H; hasFrozen = false
    }

    /* draw a camera picture filling (and cropping to) a box */
    private fun fill(fd: VideoFrameDrawer, f: VideoFrame, x: Int, y: Int, w: Int, hh: Int, z: Float = 1f) {
        val fa = f.rotatedWidth.toFloat() / f.rotatedHeight; val oa = w.toFloat() / hh
        mat.reset(); mat.preTranslate(0.5f, 0.5f)
        if (z > 1.001f) mat.preScale(1f / z, 1f / z)
        if (fa > oa) mat.preScale(oa / fa, 1f) else mat.preScale(1f, fa / oa)
        mat.preTranslate(-0.5f, -0.5f)
        fd.drawFrame(f, look, mat, x, y, w, hh)
    }
    private fun box(x: Int, y: Int, w: Int, hh: Int, r: Float, g: Float, b: Float) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST); GLES20.glScissor(x, y, w, hh)
        GLES20.glClearColor(r, g, b, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun render(f: VideoFrame) {
        val e = egl0 ?: return
        e.makeCurrent()
        if (fbW != W || fbH != H) makeFbos()
        ovPending?.let { b -> uploadOverlay(b); ovPending = null }
        if (statCb != null) stat(f)
        lookTo?.let { to -> val p = ((SystemClock.uptimeMillis() - lookAt) / 1500f).coerceIn(0f, 1f); look.blend(lookFrom, to, p * p * (3 - 2 * p)); if (p >= 1f) { lookF = to; lookTo = null } }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glViewport(0, 0, W, H); GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        look.alpha = 1f
        val s = second
        val G = floatArrayOf(0.784f, 0.643f, 0.361f)
        when {
            layout == "split" && s != null -> {
                fill(fd1, f, 0, H / 2, W, H - H / 2, zoomMain); fill(fd2, s, 0, 0, W, H / 2, zoomSec)
                box(0, H / 2 - 2, W, 4, G[0], G[1], G[2])
            }
            layout == "pip" && s != null -> {
                fill(fd1, f, 0, 0, W, H, zoomMain)
                val pw = (minOf(W, H) * 0.36f).toInt(); val ph = pw * 4 / 3; val px = W - pw - (W * 0.04f).toInt(); val py = (H * 0.12f).toInt()
                box(px - 4, py - 4, pw + 8, ph + 8, G[0], G[1], G[2]); fill(fd2, s, px, py, pw, ph, zoomSec)
            }
            else -> { val r = vRect
                if (r == null) fill(fd1, f, 0, 0, W, H, zoomMain)
                else { val bg = Overlay.bgColor(r[4]); box(0, 0, W, H, android.graphics.Color.red(bg) / 255f, android.graphics.Color.green(bg) / 255f, android.graphics.Color.blue(bg) / 255f)
                    fill(fd1, f, r[0].toInt(), (H - r[1] - r[3]).toInt(), r[2].toInt(), r[3].toInt(), zoomMain) } }
        }
        // switch effect: fade (or dip to black) from the last picture of the previous angle
        val p = (SystemClock.uptimeMillis() - transAt) / 450f
        if (hasFrozen && p < 1f && trans != "cut" && trans != "none") {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            transDraw(p)
            GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glViewport(0, 0, W, H)
        } else if (p >= 1f) hasFrozen = false
        snapCb?.let { cb -> snapCb = null
            val bb = java.nio.ByteBuffer.allocateDirect(W * H * 4).order(java.nio.ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
            val w = W; val hh = H; Thread { cb(try { Photo.fromGl(bb, w, hh) } catch (e: Throwable) { null }) }.start() }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glFlush()

        pv?.let { v ->
            try {
                v.makeCurrent()
                var w = pvW; var hh = w * H / W; if (hh > pvH) { hh = pvH; w = hh * W / H }
                GLES20.glViewport(0, 0, pvW, pvH); GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                show((pvW - w) / 2, (pvH - hh) / 2, w, hh); v.swapBuffers()
            } catch (x: Exception) {}
        }
        val en = enc; val ee = encEgl
        if (en != null && ee != null && !en.paused) {
            // timing from the camera's own clock (steady), kept in line with the sound clock
            val target = en.nowUs() * 1000; val ts = f.timestampNs; val d = ts - lastTs
            outNs = if (lastTs < 0 || d < 5_000_000 || d > 250_000_000) target else outNs + d
            if (Math.abs(outNs - target) > 120_000_000) outNs = target
            lastTs = ts
            try { ee.makeCurrent(); GLES20.glViewport(0, 0, W, H); show(0, 0, W, H); ee.swapBuffers(outNs) } catch (x: Exception) {}
        } else lastTs = -1
        fpsN++; val now = SystemClock.uptimeMillis(); if (now - fpsT >= 1000) { fps = fpsN; fpsN = 0; fpsT = now }
    }

    /* the same switch effects as the editor, drawn from the last picture of the previous angle (old) over the new one */
    private fun old(x: Int, y: Int, w: Int, hh: Int, a: Float = 1f) { fade.alpha = a; fade.drawRgb(tex[1 - cur], LookDrawer.ID, W, H, x, y, w, hh) }
    private fun solid(white: Boolean, a: Float) { if (a <= 0.003f) return; fade.cm = ZERO; fade.co = if (white) floatArrayOf(1f, 1f, 1f, 0f) else floatArrayOf(0f, 0f, 0f, 0f)
        fade.alpha = a.coerceAtMost(1f); fade.drawRgb(tex[cur], LookDrawer.ID, W, H, 0, 0, W, H); fade.cm = LookDrawer.ID.copyOf(); fade.co = floatArrayOf(0f, 0f, 0f, 0f) }
    private fun tint(r: Float, g: Float, b: Float, a: Float) { if (a <= 0.003f) return; GLES20.glDisable(GLES20.GL_SCISSOR_TEST); fade.cm = ZERO; fade.co = floatArrayOf(r, g, b, 0f)
        fade.alpha = a.coerceAtMost(1f); fade.drawRgb(tex[cur], LookDrawer.ID, W, H, 0, 0, W, H); fade.cm = LookDrawer.ID.copyOf(); fade.co = floatArrayOf(0f, 0f, 0f, 0f) }
    private fun clip(x: Int, y: Int, w: Int, hh: Int) { GLES20.glEnable(GLES20.GL_SCISSOR_TEST); GLES20.glScissor(x, y, maxOf(0, w), maxOf(0, hh)) }
    private fun transDraw(p: Float) {
        val e = p * p * (3 - 2 * p)   // smooth start and end
        when (eff) {
            "black", "white" -> { if (p < 0.5f) old(0, 0, W, H); solid(eff == "white", if (p < 0.5f) p * 2 else (1 - p) * 2) }
            "flash" -> { if (p < 0.15f) old(0, 0, W, H); solid(true, if (p < 0.15f) p / 0.15f else (1 - p) / 0.85f) }
            "slide" -> old((-e * W).toInt(), 0, W, H)
            "slideup" -> old(0, (e * H).toInt(), W, H)
            "zoom" -> { val s = 1f + e * 0.6f; val w = (W * s).toInt(); val hh = (H * s).toInt(); old((W - w) / 2, (H - hh) / 2, w, hh, 1f - e) }
            "whip" -> { val q = e * e; old((-q * W * 1.2f).toInt(), 0, W, H, 1f - q * 0.5f) }
            "wipe" -> { clip((e * W).toInt(), 0, W, H); old(0, 0, W, H) }
            "circle" -> {   // the new angle opens from the middle
                val rw = (e * W).toInt(); val rh = (e * H).toInt(); val x0 = (W - rw) / 2; val y0 = (H - rh) / 2
                clip(0, 0, x0, H); old(0, 0, W, H); clip(x0 + rw, 0, W - x0 - rw, H); old(0, 0, W, H)
                clip(x0, 0, rw, y0); old(0, 0, W, H); clip(x0, y0 + rh, rw, H - y0 - rh); old(0, 0, W, H)
            }
            "doors" -> { val d = (e * W / 2).toInt()
                clip(0, 0, W / 2 - d, H); old(-d, 0, W, H); clip(W / 2 + d, 0, W - W / 2 - d, H); old(d, 0, W, H) }
            "leak" -> { old(0, 0, W, H, 1f - e); tint(1f, 0.62f, 0.25f, (1f - kotlin.math.abs(2f * p - 1f)) * 0.75f) }   // warm light washes over
            "bands" -> { val bh = H / 3; for (i in 0..2) { val q = ((e * 1.6f) - i * 0.3f).coerceIn(0f, 1f); val y = H - (i + 1) * bh
                val off = ((if (i % 2 == 0) -1 else 1) * q * W).toInt(); clip(0, y, W, bh); old(off, 0, W, H) } }
            "reveal" -> { val hh = ((1f - e) * H / 2).toInt(); clip(0, H - hh, W, hh); old(0, 0, W, H); clip(0, 0, W, hh); old(0, 0, W, H) }
            "tilt" -> { val s = 1f + e * 0.35f; val w = (W * s).toInt(); val hh = (H * s).toInt(); old((W - w) / 2 - (e * W * 0.25f).toInt(), (H - hh) / 2 + (e * H * 0.12f).toInt(), w, hh, 1f - e) }
            else -> old(0, 0, W, H, 1f - p)   // fade
        }
    }
    private fun show(x: Int, y: Int, w: Int, hh: Int) {
        plain.drawRgb(tex[cur], LookDrawer.ID, W, H, x, y, w, hh)
        if (ovTex != 0) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            plain.drawRgb(ovTex, FLIP, W, H, x, y, w, hh)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        val dm = dim
        if (dm > 0.002f) {   // fade of the whole picture to black (start / end of a recording)
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            fade.cm = ZERO; fade.alpha = dm.coerceAtMost(1f); fade.drawRgb(tex[cur], LookDrawer.ID, W, H, x, y, w, hh); fade.cm = LookDrawer.ID.copyOf()
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }
    @Volatile var dim = 0f

    private fun uploadOverlay(b: Bitmap) {
        if (ovTex == 0) { val a = IntArray(1); GLES20.glGenTextures(1, a, 0); ovTex = a[0] }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    companion object {
        val FLIP = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        val ZERO = FloatArray(16).also { it[15] = 1f }
    }
}
