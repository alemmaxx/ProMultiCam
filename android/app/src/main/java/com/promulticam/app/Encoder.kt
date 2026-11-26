package com.promulticam.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.view.Surface
import java.io.FileDescriptor

/* Hardware video encoder (fed by the GPU through a Surface) + microphone AAC encoder -> one MP4 file */
class Encoder(private val W: Int, private val H: Int, fd: FileDescriptor, private val withAudio: Boolean) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()
    private var vTrack = -1; private var aTrack = -1; private var started = false
    private lateinit var venc: MediaCodec
    lateinit var surface: Surface; private set
    @Volatile var running = false; private set
    @Volatile var paused = false; private set
    private var t0 = 0L
    @Volatile private var pausedNs = 0L
    private var pauseAt = 0L
    private var audioThread: Thread? = null
    private var vDrain: Thread? = null
    @Volatile private var eosSent = false

    fun start() {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, if (W * H >= 1900000) 10_000_000 else 6_000_000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        venc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = venc.createInputSurface()
        venc.start()
        t0 = System.nanoTime(); running = true
        vDrain = Thread { drainVideo() }.apply { start() }
        if (withAudio) audioThread = Thread { audioLoop() }.apply { start() } else aTrack = -2
    }

    /* presentation time, with paused time taken out */
    fun nowUs() = (System.nanoTime() - t0 - pausedNs) / 1000
    fun pause() { if (!paused) { paused = true; pauseAt = System.nanoTime() } }
    fun resume() { if (paused) { pausedNs += System.nanoTime() - pauseAt; paused = false } }

    private fun startMuxerIfReady() {
        if (!started && vTrack >= 0 && (aTrack >= 0 || aTrack == -2)) { muxer.start(); started = true }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo(); var idle = 0
        while (true) {
            val i = try { venc.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) synchronized(lock) { vTrack = muxer.addTrack(venc.outputFormat); startMuxerIfReady() }
            else if (i >= 0) {
                val buf = venc.getOutputBuffer(i)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) synchronized(lock) { if (started) muxer.writeSampleData(vTrack, buf, info) }
                venc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (eosSent && ++idle > 150) break
        }
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        val sr = 48000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2) } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { synchronized(lock) { aTrack = -2; startMuxerIfReady() }; return }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1)
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); aenc.start()
        rec.startRecording()
        val pcm = ByteArray(4096); val info = MediaCodec.BufferInfo()
        var samples = 0L; var baseUs = -1L; var eos = false; var idle = 0
        while (true) {
            if (!eos) {
                val n = rec.read(pcm, 0, pcm.size)
                val stop = !running
                if (stop) {
                    val ii = aenc.dequeueInputBuffer(10_000)
                    if (ii >= 0) { aenc.queueInputBuffer(ii, 0, 0, maxOf(0L, baseUs) + samples * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                } else if (n > 0 && !paused) {
                    if (baseUs < 0) baseUs = nowUs()
                    gain = agc(pcm, n, gain)
                    val vv = vol; if (vv < 0.999f) for (i in 0 until n / 2) { val v = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8); val o = (v.toShort() * vv).toInt(); pcm[2 * i] = o.toByte(); pcm[2 * i + 1] = (o shr 8).toByte() }
                    // feed ALL the sound read: the encoder's buffers can be smaller than one read
                    var off = 0; var tries = 0
                    while (off < n && tries < 50) {
                        val ii = aenc.dequeueInputBuffer(10_000)
                        if (ii < 0) { tries++; drainAudio(aenc, info); continue }
                        val ib = aenc.getInputBuffer(ii)!!; ib.clear(); val len = minOf(n - off, ib.remaining()); ib.put(pcm, off, len)
                        aenc.queueInputBuffer(ii, 0, len, baseUs + samples * 1_000_000L / sr, 0)
                        samples += len / 2; off += len
                    }
                }
            }
            val o = aenc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) synchronized(lock) { aTrack = muxer.addTrack(aenc.outputFormat); startMuxerIfReady() }
            else if (o >= 0) {
                val b = aenc.getOutputBuffer(o)
                if (b != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) synchronized(lock) { if (started) muxer.writeSampleData(aTrack, b, info) }
                aenc.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (eos && ++idle > 200) break
        }
        try { rec.stop() } catch (e: Exception) {}; rec.release()
        try { aenc.stop() } catch (e: Exception) {}; aenc.release()
    }

    /* write out whatever the sound encoder has ready (keeps its buffers free) */
    private fun drainAudio(aenc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val o = aenc.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) synchronized(lock) { aTrack = muxer.addTrack(aenc.outputFormat); startMuxerIfReady() }
            else if (o >= 0) {
                val b = aenc.getOutputBuffer(o)
                if (b != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) synchronized(lock) { if (started) muxer.writeSampleData(aTrack, b, info) }
                aenc.releaseOutputBuffer(o, false)
            } else break
        }
    }
    private var gain = 2f
    /* automatic level: lifts a quiet voice (up to 8x), never clips, leaves silence alone */
    @Volatile var vol = 1f   // sound level for the fade in / out of a recording
    private fun agc(b: ByteArray, n: Int, g0: Float): Float {
        val cnt = n / 2; var sum = 0.0; var peak = 1
        for (i in 0 until cnt) { val v = (b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8); val a = Math.abs(v.toShort().toInt()); sum += a.toDouble() * a; if (a > peak) peak = a }
        val rms = Math.sqrt(sum / maxOf(1, cnt)).toFloat()
        var want = if (rms > 60f) (3300f / rms).coerceIn(1f, 8f) else g0
        want = minOf(want, 30000f / peak)
        val g = (g0 + (want - g0) * (if (want < g0) 0.5f else 0.08f)).coerceIn(1f, 8f).let { minOf(it, 32000f / peak) }
        for (i in 0 until cnt) {
            val v = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort().toInt()
            val o = (v * g).toInt().coerceIn(-32767, 32767)
            b[2 * i] = (o and 0xff).toByte(); b[2 * i + 1] = ((o shr 8) and 0xff).toByte()
        }
        return g
    }

    /* finish the file in the background; done(ok) is called from that thread */
    fun stop(done: (Boolean) -> Unit) {
        if (!running) return
        running = false
        Thread {
            try { audioThread?.join(4000) } catch (e: Exception) {}
            synchronized(lock) { if (aTrack < 0) { aTrack = -2; startMuxerIfReady() } }
            try { venc.signalEndOfInputStream() } catch (e: Exception) {}
            eosSent = true
            try { vDrain?.join(5000) } catch (e: Exception) {}
            var ok = false
            synchronized(lock) { try { if (started) { muxer.stop(); ok = true } } catch (e: Exception) {}; try { muxer.release() } catch (e: Exception) {} }
            try { venc.stop() } catch (e: Exception) {}; try { venc.release() } catch (e: Exception) {}
            try { surface.release() } catch (e: Exception) {}
            done(ok)
        }.start()
    }
}
