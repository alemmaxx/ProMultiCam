package com.promulticam.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/* a photo becomes a short video clip (with silent sound), so it sits on the timeline like any video */
object StillClip {
    fun make(src: Bitmap, W0: Int, H0: Int, durUs: Long, out: File) {
        var W = W0; var H = H0; val big = maxOf(W, H); if (big > 1920) { W = W * 1920 / big; H = H * 1920 / big }
        W = W / 2 * 2; H = H / 2 * 2
        val fps = 30; val n = maxOf(1, (durUs * fps / 1_000_000).toInt())
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) }
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
                    val bi = MediaCodec.BufferInfo(); bi.set(0, info.size, k * 1_000_000L / fps, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME); k++; vs.add(a to bi)
                }
                vc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        val sc = minOf(W.toFloat() / src.width, H.toFloat() / src.height); val dw = src.width * sc; val dh = src.height * sc
        val dst = RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2); val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (f in 0 until n) { val c = surf.lockHardwareCanvas(); c.drawColor(Color.BLACK); c.drawBitmap(src, null, dst, p); surf.unlockCanvasAndPost(c); drainV(false) }
        vc.signalEndOfInputStream(); drainV(true); vc.stop(); vc.release(); surf.release()

        // silent sound of the same length
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply { setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 64000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192) }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); ac.start()
        val asamp = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>(); var aFmt: MediaFormat? = null
        val total = durUs * 48000 / 1_000_000; var fed = 0L; var inEnd = false; var outEnd = false
        val until = System.currentTimeMillis() + 8000
        while (!outEnd && System.currentTimeMillis() < until) {
            if (!inEnd) { val i = ac.dequeueInputBuffer(5000); if (i >= 0) { val bb = ac.getInputBuffer(i)!!; bb.clear()
                if (fed >= total) { ac.queueInputBuffer(i, 0, 0, fed * 1_000_000 / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true }
                else { val ns = minOf(1024L, total - fed).toInt(); bb.put(ByteArray(ns * 2)); ac.queueInputBuffer(i, 0, ns * 2, fed * 1_000_000 / 48000, 0); fed += ns } } }
            val o = ac.dequeueOutputBuffer(info, 5000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) aFmt = ac.outputFormat
            else if (o >= 0) { val bb = ac.getOutputBuffer(o)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) { val a = ByteArray(info.size); bb.position(info.offset); bb.get(a); val bi = MediaCodec.BufferInfo(); bi.set(0, info.size, info.presentationTimeUs, 0); asamp.add(a to bi) }
                ac.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEnd = true }
        }
        ac.stop(); ac.release()
        val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vt = mx.addTrack(vFmt ?: throw Exception("no video")); val at = aFmt?.let { mx.addTrack(it) } ?: -1
        mx.start()
        vs.forEach { (a, bi) -> mx.writeSampleData(vt, java.nio.ByteBuffer.wrap(a), bi) }
        if (at >= 0) asamp.forEach { (a, bi) -> mx.writeSampleData(at, java.nio.ByteBuffer.wrap(a), bi) }
        mx.stop(); mx.release()
    }
}

