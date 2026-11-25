package com.promulticam.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicInteger

/* Records the program: each picture of the chosen angle is drawn on the GPU (with the brand on top)
   straight into the phone's hardware video encoder; the microphone goes to the AAC encoder. */
class Compositor(private val W: Int, private val H: Int, fd: FileDescriptor, private val withAudio: Boolean) : VideoSink {
    private val th = HandlerThread("pmc-rec").apply { start() }
    private val h = Handler(th.looper)
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()
    private var vTrack = -1; private var aTrack = -1; private var started = false
    private lateinit var venc: MediaCodec
    private var egl: EglBase? = null
    private val drawer = GlRectDrawer()
    private val fdraw = VideoFrameDrawer()
    private val mat = Matrix()
    private var ovTex = 0
    @Volatile private var ovPending: Bitmap? = null
    private val inFlight = AtomicInteger(0)
    @Volatile var running = false; private set
    @Volatile var paused = false
    private var t0 = 0L
    @Volatile private var pausedNs = 0L
    private var pauseAt = 0L
    private var audioThread: Thread? = null
    private var vDrain: Thread? = null
    @Volatile var frames = 0; private set

    fun setOverlay(b: Bitmap?) { ovPending = b ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }

    fun start() {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, if (W * H >= 1900000) 10_000_000 else 6_000_000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        venc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = venc.createInputSurface()
        venc.start()
        t0 = System.nanoTime(); running = true
        h.post {
            egl = EglBase.create(Rtc.egl.eglBaseContext, EglBase.CONFIG_RECORDABLE).also { it.createSurface(surf); it.makeCurrent() }
        }
        vDrain = Thread { drainVideo() }.apply { start() }
        if (withAudio) audioThread = Thread { audioLoop() }.apply { start() }
    }

    private fun nowUs() = (System.nanoTime() - t0 - pausedNs) / 1000

    fun pause() { if (!paused) { paused = true; pauseAt = System.nanoTime() } }
    fun resume() { if (paused) { pausedNs += System.nanoTime() - pauseAt; paused = false } }

    /* a picture from the chosen angle */
    override fun onFrame(frame: VideoFrame) {
        if (!running || paused) return
        if (inFlight.get() > 1) return          // phone busy: skip this picture rather than fall behind
        frame.retain(); inFlight.incrementAndGet()
        h.post {
            try { if (running) draw(frame) } catch (e: Exception) {}
            frame.release(); inFlight.decrementAndGet()
        }
    }

    private fun draw(frame: VideoFrame) {
        val e = egl ?: return
        e.makeCurrent()
        ovPending?.let { b -> uploadOverlay(b); ovPending = null }
        GLES20.glViewport(0, 0, W, H)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val fa = frame.rotatedWidth.toFloat() / frame.rotatedHeight; val oa = W.toFloat() / H
        mat.reset(); mat.preTranslate(0.5f, 0.5f)
        if (fa > oa) mat.preScale(oa / fa, 1f) else mat.preScale(1f, fa / oa)
        mat.preTranslate(-0.5f, -0.5f)
        fdraw.drawFrame(frame, drawer, mat, 0, 0, W, H)
        if (ovTex != 0) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawer.drawRgb(ovTex, FLIP, W, H, 0, 0, W, H)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        e.swapBuffers(nowUs() * 1000)
        frames++
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

    private fun startMuxerIfReady() {
        if (!started && vTrack >= 0 && (!withAudio || aTrack >= 0 || aTrack == -2)) { muxer.start(); started = true }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = try { venc.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { synchronized(lock) { vTrack = muxer.addTrack(venc.outputFormat); startMuxerIfReady() } }
            else if (i >= 0) {
                val buf = venc.getOutputBuffer(i)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    synchronized(lock) { if (started) muxer.writeSampleData(vTrack, buf, info) }
                }
                venc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (!running && i == MediaCodec.INFO_TRY_AGAIN_LATER && eosSent) { if (++idle > 100) break }
        }
    }
    @Volatile private var eosSent = false
    private var idle = 0

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        val sr = 48000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try { AudioRecord(MediaRecorder.AudioSource.CAMCORDER, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2) } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { synchronized(lock) { aTrack = -2; startMuxerIfReadyNoAudio() }; return }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1)
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); aenc.start()
        rec.startRecording()
        val pcm = ByteArray(4096); val info = MediaCodec.BufferInfo()
        var samples = 0L; var baseUs = -1L
        var eos = false
        while (true) {
            if (!eos) {
                val n = rec.read(pcm, 0, pcm.size)
                val stop = !running
                if (n > 0 && (!paused || stop)) {
                    if (baseUs < 0) baseUs = nowUs()
                    val ii = aenc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = aenc.getInputBuffer(ii)!!; ib.clear(); val len = minOf(n, ib.remaining()); ib.put(pcm, 0, len)
                        val pts = baseUs + samples * 1_000_000L / sr
                        aenc.queueInputBuffer(ii, 0, len, pts, if (stop) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        samples += len / 2
                        if (stop) eos = true
                    }
                } else if (stop) {
                    val ii = aenc.dequeueInputBuffer(10_000)
                    if (ii >= 0) { aenc.queueInputBuffer(ii, 0, 0, baseUs.coerceAtLeast(0) + samples * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                }
            }
            val o = aenc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) synchronized(lock) { aTrack = muxer.addTrack(aenc.outputFormat); startMuxerIfReady() }
            else if (o >= 0) {
                val b = aenc.getOutputBuffer(o)
                if (b != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) synchronized(lock) { if (started) muxer.writeSampleData(aTrack, b, info) }
                aenc.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (eos && o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (++aIdle > 200) break }
        }
        try { rec.stop() } catch (e: Exception) {}; rec.release()
        try { aenc.stop() } catch (e: Exception) {}; aenc.release()
    }
    private var aIdle = 0
    private fun startMuxerIfReadyNoAudio() { if (!started && vTrack >= 0) { muxer.start(); started = true } }

    /* finish the file; done() runs on the recording thread */
    fun stop(done: (Boolean) -> Unit) {
        if (!running) return
        running = false
        Thread {
            try { audioThread?.join(4000) } catch (e: Exception) {}
            synchronized(lock) { if (withAudio && aTrack == -2) startMuxerIfReadyNoAudio() }
            try { venc.signalEndOfInputStream() } catch (e: Exception) {}
            eosSent = true
            try { vDrain?.join(5000) } catch (e: Exception) {}
            var ok = false
            synchronized(lock) { try { if (started) { muxer.stop(); ok = true } } catch (e: Exception) {}; try { muxer.release() } catch (e: Exception) {} }
            try { venc.stop() } catch (e: Exception) {}; try { venc.release() } catch (e: Exception) {}
            h.post {
                try { if (ovTex != 0) GLES20.glDeleteTextures(1, intArrayOf(ovTex), 0) } catch (e: Exception) {}
                drawer.release(); fdraw.release(); egl?.release(); egl = null
                th.quitSafely(); done(ok)
            }
        }.start()
    }

    companion object {
        /* picture rows top-to-bottom -> GL bottom-to-top */
        val FLIP = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    }
}
