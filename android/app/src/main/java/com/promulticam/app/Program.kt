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
    @Volatile var trans = "fade"          // fade, black, cut
    private var transAt = 0L
    private var second: VideoFrame? = null
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

    fun setLook(f: String) { h.post { look.setLook(f) } }
    fun setOverlay(b: Bitmap?) { ovPending = b ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
    fun clearSecond() { h.post { second?.release(); second = null } }
    fun resize(w: Int, h0: Int) { W = w; H = h0; h.post { makeFbos() } }

    /* a switch happened: keep the last picture to fade from */
    fun switched() { h.post { if (fbW > 0) { cur = 1 - cur; hasFrozen = true; transAt = SystemClock.uptimeMillis() } } }

    fun setPreview(s: Surface?, w: Int, hh: Int) {
        val l = CountDownLatch(1)
        h.post {
            pv?.release(); pv = null
            if (s != null) try { pv = EglBase.create(egl0!!.eglBaseContext, EglBase.CONFIG_PLAIN).also { it.createSurface(s) }; pvW = w; pvH = hh } catch (e: Exception) { pv = null }
            l.countDown()
        }
        l.await(1500, TimeUnit.MILLISECONDS)
    }

    fun attachEncoder(e: Encoder) { h.post { try { encEgl = EglBase.create(egl0!!.eglBaseContext, EglBase.CONFIG_RECORDABLE).also { it.createSurface(e.surface) }; enc = e } catch (x: Exception) { enc = null } } }
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
    private fun fill(fd: VideoFrameDrawer, f: VideoFrame, x: Int, y: Int, w: Int, hh: Int) {
        val fa = f.rotatedWidth.toFloat() / f.rotatedHeight; val oa = w.toFloat() / hh
        mat.reset(); mat.preTranslate(0.5f, 0.5f)
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
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glViewport(0, 0, W, H); GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        look.alpha = 1f
        val s = second
        val G = floatArrayOf(0.784f, 0.643f, 0.361f)
        when {
            layout == "split" && s != null -> {
                fill(fd1, f, 0, H / 2, W, H - H / 2); fill(fd2, s, 0, 0, W, H / 2)
                box(0, H / 2 - 2, W, 4, G[0], G[1], G[2])
            }
            layout == "pip" && s != null -> {
                fill(fd1, f, 0, 0, W, H)
                val pw = (minOf(W, H) * 0.36f).toInt(); val ph = pw * 4 / 3; val px = W - pw - (W * 0.04f).toInt(); val py = (H * 0.12f).toInt()
                box(px - 4, py - 4, pw + 8, ph + 8, G[0], G[1], G[2]); fill(fd2, s, px, py, pw, ph)
            }
            else -> fill(fd1, f, 0, 0, W, H)
        }
        // switch effect: fade (or dip to black) from the last picture of the previous angle
        val p = (SystemClock.uptimeMillis() - transAt) / 450f
        if (hasFrozen && p < 1f && trans != "cut") {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            if (trans == "black") { if (p < 0.5f) { fade.alpha = 1f; fade.drawRgb(tex[1 - cur], LookDrawer.ID, W, H, 0, 0, W, H) }
                val a = if (p < 0.5f) p * 2 else (1 - p) * 2; fade.cm = ZERO; fade.alpha = a; fade.drawRgb(tex[1 - cur], LookDrawer.ID, W, H, 0, 0, W, H); fade.cm = LookDrawer.ID.copyOf() }
            else { fade.alpha = 1f - p; fade.drawRgb(tex[1 - cur], LookDrawer.ID, W, H, 0, 0, W, H) }
            GLES20.glDisable(GLES20.GL_BLEND)
        } else if (p >= 1f) hasFrozen = false
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
            try { ee.makeCurrent(); GLES20.glViewport(0, 0, W, H); show(0, 0, W, H); ee.swapBuffers(en.nowUs() * 1000) } catch (x: Exception) {}
        }
        fpsN++; val now = SystemClock.uptimeMillis(); if (now - fpsT >= 1000) { fps = fpsN; fpsN = 0; fpsT = now }
    }

    private fun show(x: Int, y: Int, w: Int, hh: Int) {
        plain.drawRgb(tex[cur], LookDrawer.ID, W, H, x, y, w, hh)
        if (ovTex != 0) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            plain.drawRgb(ovTex, FLIP, W, H, x, y, w, hh)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

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
