package com.promulticam.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileDescriptor
import java.nio.ByteBuffer

/* Joins MP4 videos into one file by copying (no re-encoding): fast, same quality.
   Works when the videos were made with the same size and settings (e.g. all from this app). */
object Joiner {
    fun join(ctx: Context, parts: List<Uri>, out: FileDescriptor): Boolean {
        val mux = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var vOut = -1; var aOut = -1; var started = false
        var offset = 0L
        val buf = ByteBuffer.allocate(4 * 1024 * 1024); val info = MediaCodec.BufferInfo()
        try {
            for (u in parts) {
                val ex = MediaExtractor(); ex.setDataSource(ctx, u, null)
                var vIn = -1; var aIn = -1
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && vIn < 0) { vIn = i; if (!started) vOut = mux.addTrack(f) }
                    else if (mime.startsWith("audio/") && aIn < 0) { aIn = i; if (!started) aOut = mux.addTrack(f) }
                }
                if (!started) { mux.start(); started = true }
                // one time base per part (keeps sound and picture together), and the sound is cut where the picture ends,
                // so the next part follows straight on (no frozen last picture at the join)
                fun span(tr: Int): Pair<Long, Long> { if (tr < 0) return -1L to -1L; ex.selectTrack(tr); ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var f = -1L; var l = -1L; while (ex.sampleTime >= 0) { val t = ex.sampleTime; if (f < 0) f = t; if (t > l) l = t; ex.advance() }; ex.unselectTrack(tr); return f to l }
                val (vf, vl) = span(vIn); val (af, _) = span(aIn)
                val base = listOf(vf, af).filter { it >= 0 }.minOrNull() ?: 0L
                val cut = if (vl >= 0) vl + 34_000 else Long.MAX_VALUE
                var end = offset
                for ((inT, outT) in listOf(vIn to vOut, aIn to aOut)) {
                    if (inT < 0 || outT < 0) continue
                    ex.selectTrack(inT); ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (true) {
                        val n = ex.readSampleData(buf, 0); if (n < 0) break
                        val t = ex.sampleTime; if (t > cut) { ex.advance(); continue }
                        info.offset = 0; info.size = n; info.presentationTimeUs = offset + (t - base)
                        info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        mux.writeSampleData(outT, buf, info)
                        if (inT == vIn || vIn < 0) end = maxOf(end, info.presentationTimeUs + 33_000)
                        ex.advance()
                    }
                    ex.unselectTrack(inT)
                }
                ex.release(); offset = end
            }
            mux.stop(); mux.release(); return true
        } catch (e: Exception) { try { mux.release() } catch (x: Exception) {}; return false }
    }
}
