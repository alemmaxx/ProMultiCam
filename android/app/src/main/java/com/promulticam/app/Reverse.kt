package com.promulticam.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/* plays a piece of a video backwards: the pictures from the end to the start, and the sound backwards too */
object Reverse {
    /** src [fromUs, toUs) → out. W/H = picture size the clip shows (already turned). prog(0..100). */
    fun make(src: File, fromUs: Long, toUs: Long, W0: Int, H0: Int, out: File, prog: (Int) -> Unit) {
        var W = W0; var H = H0; val big = maxOf(W, H); if (big > 1280) { W = W * 1280 / big; H = H * 1280 / big }
        W = W / 2 * 2; H = H / 2 * 2
        val r = MediaMetadataRetriever(); r.setDataSource(src.absolutePath)
        val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) * 1000
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        val count = if (Build.VERSION.SDK_INT >= 28) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0 else 0
        val srcFps = if (count > 0 && durUs > 0) count * 1e6 / durUs else 30.0
        val stride = maxOf(1, Math.round(srcFps / 30.0).toInt()); val fps = (srcFps / stride).coerceIn(10.0, 60.0)
        val seg = (toUs - fromUs).coerceAtLeast(100_000); val n = maxOf(1, (seg * fps / 1e6).toInt())

        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(fps).toInt()); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) }
        val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = vc.createInputSurface(); vc.start()
        val vs = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>(); var vFmt: MediaFormat? = null; var k = 0
        val info = MediaCodec.BufferInfo()
        fun drainV(end: Boolean) {
            val until = System.currentTimeMillis() + 5000
            while (true) {
                val i = vc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end || System.currentTimeMillis() > until) return else continue }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { vFmt = vc.outputFormat; continue }
                if (i < 0) continue
                val bb = vc.getOutputBuffer(i)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val a = ByteArray(info.size); bb.position(info.offset); bb.get(a)
                    val bi = MediaCodec.BufferInfo(); bi.set(0, info.size, (k * 1e6 / fps).toLong(), info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME); k++; vs.add(a to bi)
                }
                vc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        fun draw(b0: Bitmap?) {
            val c = surf.lockHardwareCanvas(); c.drawColor(Color.BLACK)
            if (b0 != null) {
                // some phones give the frame not yet turned: turn it so it matches the clip
                val b = if (rot != 0 && (b0.width > b0.height) != (W0 > H0)) Bitmap.createBitmap(b0, 0, 0, b0.width, b0.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else b0
                val sc = minOf(W.toFloat() / b.width, H.toFloat() / b.height); val dw = b.width * sc; val dh = b.height * sc
                c.drawBitmap(b, null, RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2), p)
                if (b !== b0) b.recycle()
            }
            surf.unlockCanvasAndPost(c); drainV(false)
        }
        // pictures: from the last to the first. Fast path reads a few frames in a row (one seek for each group).
        val times = (0 until n).map { toUs - 1 - (it * 1e6 / fps).toLong() }.map { it.coerceAtLeast(fromUs) }
        if (Build.VERSION.SDK_INT >= 28 && count > 0) {
            val idx = times.map { ((it.toDouble() / durUs) * count).toInt().coerceIn(0, count - 1) }
            val prm = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
            var j = 0
            while (j < idx.size) {
                // group: the next frames we need, read forward in one go (at most 8)
                val hi = idx[j]; var e = j; while (e + 1 < idx.size && e - j < 7) e++
                val lo = idx[e]
                val got = try { r.getFramesAtIndex(lo, hi - lo + 1, prm) } catch (x: Exception) { null }
                for (q in j..e) {
                    val b = got?.getOrNull(idx[q] - lo) ?: try { r.getFrameAtTime(times[q], MediaMetadataRetriever.OPTION_CLOSEST) } catch (x: Exception) { null }
                    draw(b)
                }
                got?.forEach { it.recycle() }
                j = e + 1; prog(j * 90 / idx.size)
            }
        } else {
            times.forEachIndexed { q, t -> val b = try { r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) } catch (x: Exception) { null }; draw(b); b?.recycle(); prog((q + 1) * 90 / n) }
        }
        r.release()
        vc.signalEndOfInputStream(); drainV(true); vc.stop(); vc.release(); surf.release()

        // sound: decode the piece, turn the samples around, encode again
        val (pcm, rate, ch) = try { decode(src, fromUs, toUs) } catch (e: Exception) { Triple(ShortArray(0), 48000, 1) }
        val frames = pcm.size / ch; val rev = ShortArray(frames * ch)
        for (f in 0 until frames) for (cc in 0 until ch) rev[(frames - 1 - f) * ch + cc] = pcm[f * ch + cc]
        val want = (k * 1e6 / fps * rate / 1e6).toLong() * ch   // same length as the pictures (silence if no sound)
        val full = if (rev.size >= want) rev else rev.copyOf(want.toInt())
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
        mx.start()
        vs.forEach { (a, bi) -> mx.writeSampleData(vt, ByteBuffer.wrap(a), bi) }
        if (at >= 0) asamp.forEach { (a, bi) -> mx.writeSampleData(at, ByteBuffer.wrap(a), bi) }
        mx.stop(); mx.release()
    }

    /* the sound of [fromUs, toUs) as 16-bit samples */
    fun decode(src: File, fromUs: Long, toUs: Long): Triple<ShortArray, Int, Int> {
        val ex = MediaExtractor(); ex.setDataSource(src.absolutePath)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: run { ex.release(); throw Exception("no sound") }
        val fmt = ex.getTrackFormat(ti); ex.selectTrack(ti); ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val dc = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!); dc.configure(fmt, null, null, 0); dc.start()
        val outS = java.io.ByteArrayOutputStream(); val info = MediaCodec.BufferInfo(); var inEnd = false; var done = false
        val until = System.currentTimeMillis() + 60000
        while (!done && System.currentTimeMillis() < until) {
            if (!inEnd) { val i = dc.dequeueInputBuffer(5000); if (i >= 0) { val bb = dc.getInputBuffer(i)!!; val n = ex.readSampleData(bb, 0)
                if (n < 0 || ex.sampleTime > toUs + 100_000) { dc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true }
                else { dc.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() } } }
            val o = dc.dequeueOutputBuffer(info, 5000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { dc.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } }
            else if (o >= 0) {
                if (info.size > 0) {
                    val bb = dc.getOutputBuffer(o)!!; bb.position(info.offset); bb.limit(info.offset + info.size)
                    val a = ByteArray(info.size); bb.get(a)
                    // keep only the part inside the piece
                    val per = 1e6 / rate; val nFr = info.size / (2 * ch); val t0 = info.presentationTimeUs
                    val s = ((fromUs - t0) / per).toInt().coerceIn(0, nFr); val e = ((toUs - t0) / per).toInt().coerceIn(0, nFr)
                    if (e > s) outS.write(a, s * 2 * ch, (e - s) * 2 * ch)
                }
                dc.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            }
        }
        dc.stop(); dc.release(); ex.release()
        val b = outS.toByteArray(); val sh = ShortArray(b.size / 2); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
        return Triple(sh, rate, ch)
    }
}
