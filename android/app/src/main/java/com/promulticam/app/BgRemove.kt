package com.promulticam.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/* reads the pictures of a video one after another (forward), quickly, already turned upright */
class FrameReader(private val f: File, private val wantPortrait: Boolean) {
    private val r = MediaMetadataRetriever().apply { setDataSource(f.absolutePath) }
    val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) * 1000
    private val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
    private val count = if (Build.VERSION.SDK_INT >= 28) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0 else 0
    private var bStart = -1; private var batch: List<Bitmap> = emptyList()
    private val prm = if (Build.VERSION.SDK_INT >= 28) MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 } else null
    private fun upright(b: Bitmap): Bitmap = if (rot != 0 && (b.width > b.height) == wantPortrait) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else b
    /* the picture at time t (loops if the video is shorter); do not keep or recycle it */
    fun at(t0: Long): Bitmap? {
        if (durUs <= 0) return null
        val t = t0 % durUs
        if (count > 0 && prm != null) {
            val idx = ((t.toDouble() / durUs) * count).toInt().coerceIn(0, count - 1)
            if (bStart < 0 || idx < bStart || idx >= bStart + batch.size) {
                batch.forEach { it.recycle() }; batch = emptyList(); bStart = idx
                batch = try { r.getFramesAtIndex(idx, minOf(10, count - idx), prm) } catch (e: Exception) { emptyList() }
            }
            batch.getOrNull(idx - bStart)?.let { return upright(it).also { u -> if (u !== it) { /* turned copy */ } } }
        }
        return try { r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)?.let { upright(it) } } catch (e: Exception) { null }
    }
    fun release() { batch.forEach { it.recycle() }; batch = emptyList(); try { r.release() } catch (e: Exception) {} }
}

/* takes the person out of the video and puts a new background behind (blur, colour, photo or another video). Runs on the phone with Google's free model. */
object BgRemove {
    class Bg(val mode: String, val color: Int = Color.WHITE, val img: Bitmap? = null, val video: File? = null)   // mode: blur / color / photo / video

    fun make(src: File, fromUs: Long, toUs: Long, W0: Int, H0: Int, bg: Bg, out: File, prog: (Int) -> Unit) {
        var W = W0; var H = H0; val big = maxOf(W, H); if (big > 1280) { W = W * 1280 / big; H = H * 1280 / big }
        W = W / 2 * 2; H = H / 2 * 2; val portrait = H >= W
        val fps = 30.0; val seg = (toUs - fromUs).coerceAtLeast(100_000); val n = maxOf(1, (seg * fps / 1e6).toInt())
        val reader = FrameReader(src, portrait); val bgReader = bg.video?.let { FrameReader(it, portrait) }
        val segm = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build())

        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) }
        val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = vc.createInputSurface(); vc.start()
        val vs = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>(); var vFmt: MediaFormat? = null; var k = 0; val info = MediaCodec.BufferInfo()
        fun drainV(end: Boolean) {
            val until = System.currentTimeMillis() + 5000
            while (true) { val i = vc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end || System.currentTimeMillis() > until) return else continue }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { vFmt = vc.outputFormat; continue }; if (i < 0) continue
                val bb = vc.getOutputBuffer(i)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) { val a = ByteArray(info.size); bb.position(info.offset); bb.get(a)
                    val bi = MediaCodec.BufferInfo(); bi.set(0, info.size, (k * 1e6 / fps).toLong(), info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME); k++; vs.add(a to bi) }
                vc.releaseOutputBuffer(i, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return }
        }
        // working pictures
        val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val fc = Canvas(frame)
        val person = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val pc = Canvas(person)
        val SW = 256; val SH = maxOf(16, SW * H / W); val small = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888); val sc = Canvas(small)
        val mask = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888); var prev: FloatArray? = null; val px = IntArray(SW * SH)
        val blurS = Bitmap.createBitmap(maxOf(8, W / 24), maxOf(8, H / 24), Bitmap.Config.ARGB_8888); val bc = Canvas(blurS)
        val pf = Paint(Paint.FILTER_BITMAP_FLAG); val din = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        fun cover(cv: Canvas, b: Bitmap, w: Int, h: Int) { val s = maxOf(w.toFloat() / b.width, h.toFloat() / b.height); val dw = b.width * s; val dh = b.height * s
            cv.drawBitmap(b, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), pf) }

        for (q in 0 until n) {
            val t = fromUs + (q * 1e6 / fps).toLong()
            val f0 = reader.at(t)
            fc.drawColor(Color.BLACK); pc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (f0 != null) {
                cover(pc, f0, W, H)
                // where the person is: the model looks at a small copy
                sc.drawColor(Color.BLACK); cover(sc, f0, SW, SH)
                val sm = try { Tasks.await(segm.process(InputImage.fromBitmap(small, 0))) } catch (e: Exception) { null }
                if (sm != null) {
                    val buf = sm.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer(); val mw = sm.width; val mh = sm.height
                    val cur = FloatArray(SW * SH)
                    for (y in 0 until SH) for (x in 0 until SW) { val mx = x * mw / SW; val my = y * mh / SH; cur[y * SW + x] = buf.get(my * mw + mx) }
                    // steady between pictures (less flicker), then firm edges
                    prev?.let { p -> for (i in cur.indices) cur[i] = cur[i] * 0.65f + p[i] * 0.35f }; prev = cur
                    for (i in cur.indices) { val c = ((cur[i] - 0.35f) / 0.3f).coerceIn(0f, 1f); val a = c * c * (3 - 2 * c); px[i] = (Math.round(a * 255) shl 24) or 0xFFFFFF }
                    mask.setPixels(px, 0, SW, 0, 0, SW, SH)
                    pc.drawBitmap(mask, null, RectF(0f, 0f, W.toFloat(), H.toFloat()), din)   // keep only the person (soft edge from the stretched small mask)
                }
                // the new background
                when (bg.mode) {
                    "color" -> fc.drawColor(bg.color)
                    "photo" -> bg.img?.let { cover(fc, it, W, H) }
                    "video" -> bgReader?.at(t - fromUs)?.let { cover(fc, it, W, H) }
                    else -> { bc.drawColor(Color.BLACK); cover(bc, f0, blurS.width, blurS.height); fc.drawBitmap(blurS, null, RectF(0f, 0f, W.toFloat(), H.toFloat()), pf); fc.drawColor(0x33000000) }
                }
                fc.drawBitmap(person, 0f, 0f, null)
            }
            val c = surf.lockHardwareCanvas(); c.drawBitmap(frame, 0f, 0f, null); surf.unlockCanvasAndPost(c); drainV(false)
            prog((q + 1) * 92 / n)
        }
        reader.release(); bgReader?.release(); segm.close()
        vc.signalEndOfInputStream(); drainV(true); vc.stop(); vc.release(); surf.release()

        // the sound of the same piece
        val (pcm, rate, ch) = try { Reverse.decode(src, fromUs, toUs) } catch (e: Exception) { Triple(ShortArray(0), 48000, 1) }
        val want = (k * 1e6 / fps * rate / 1e6).toLong() * ch; val full = if (pcm.size >= want) pcm.copyOf(want.toInt()) else pcm.copyOf(want.toInt())
        val asamp = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>(); var aFmt: MediaFormat? = null
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply { setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 128000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384) }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); ac.start()
        var fed = 0; var inEnd = false; var outEnd = false; val until = System.currentTimeMillis() + 30000
        while (!outEnd && System.currentTimeMillis() < until) {
            if (!inEnd) { val i = ac.dequeueInputBuffer(5000); if (i >= 0) { val bb = ac.getInputBuffer(i)!!; bb.clear(); bb.order(ByteOrder.LITTLE_ENDIAN)
                val pts = (fed / ch).toLong() * 1_000_000 / rate
                if (fed >= full.size) { ac.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true }
                else { val ns = minOf(1024 * ch, full.size - fed, bb.capacity() / 2); bb.asShortBuffer().put(full, fed, ns); ac.queueInputBuffer(i, 0, ns * 2, pts, 0); fed += ns } } }
            val o = ac.dequeueOutputBuffer(info, 5000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) aFmt = ac.outputFormat
            else if (o >= 0) { val bb = ac.getOutputBuffer(o)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) { val a = ByteArray(info.size); bb.position(info.offset); bb.get(a); val bi = MediaCodec.BufferInfo(); bi.set(0, info.size, info.presentationTimeUs, 0); asamp.add(a to bi) }
                ac.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEnd = true }
        }
        ac.stop(); ac.release(); prog(100)
        val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vt = mx.addTrack(vFmt ?: throw Exception("no video")); val at = aFmt?.let { mx.addTrack(it) } ?: -1
        mx.start(); vs.forEach { (a, bi) -> mx.writeSampleData(vt, ByteBuffer.wrap(a), bi) }; if (at >= 0) asamp.forEach { (a, bi) -> mx.writeSampleData(at, ByteBuffer.wrap(a), bi) }
        mx.stop(); mx.release()
        listOf(frame, person, small, mask, blurS).forEach { it.recycle() }
    }
}
