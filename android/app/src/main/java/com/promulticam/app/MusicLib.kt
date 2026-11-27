package com.promulticam.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/* built-in music: our own short songs, made on the phone (free to use, no copyright problem). Each one loops smoothly. */
object MusicLib {
    class Song(val id: String, val en: String, val ms: String, val bpm: Int)
    val ALL = listOf(
        Song("ceria", "Happy", "Ceria", 112), Song("santai", "Chill", "Santai", 80), Song("dapur", "Kitchen groove", "Rentak dapur", 100),
        Song("epik", "Epic", "Epik", 90), Song("tenaga", "Energy", "Tenaga", 128), Song("lembut", "Soft piano", "Piano lembut", 70))
    private const val R = Snd.RATE

    /* the song as a WAV file (made once, then kept) */
    fun file(c: Context, id: String): File {
        val d = File(c.filesDir, "music").apply { mkdirs() }; val f = File(d, "lib_$id.wav")
        if (f.exists() && f.length() > 1000) return f
        val a = make(id); write(a, File(d, "tmp_$id.wav")); File(d, "tmp_$id.wav").renameTo(f); return f
    }

    private fun midi(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

    fun make(id: String): FloatArray {
        val s = ALL.first { it.id == id }
        val beat = 60.0 / s.bpm; val bar = beat * 4; val bars = 16
        val n = (bar * bars * R).toInt(); val out = FloatArray(n); val rnd = java.util.Random(id.hashCode().toLong())
        // chords: list of note numbers for each bar (repeats every 4 bars)
        val prog: List<IntArray> = when (id) {
            "ceria" -> listOf(intArrayOf(60, 64, 67), intArrayOf(55, 59, 62), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60))
            "santai" -> listOf(intArrayOf(53, 57, 60, 64), intArrayOf(52, 55, 59, 62), intArrayOf(50, 53, 57, 60), intArrayOf(48, 52, 55, 59))
            "dapur" -> listOf(intArrayOf(55, 59, 62), intArrayOf(60, 64, 67), intArrayOf(62, 66, 69), intArrayOf(60, 64, 67))
            "lembut" -> listOf(intArrayOf(60, 64, 67), intArrayOf(55, 59, 62), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60))
            else -> listOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(48, 52, 55), intArrayOf(55, 59, 62))
        }
        fun at(t: Double) = (t * R).toInt()
        fun add(start: Double, len: Double, gain: Float, f: (Double) -> Double) {
            val a = at(start); val b = a + at(len)
            for (i in a until b) { val v = (f((i - a).toDouble() / R) * gain).toFloat(); out[((i % n) + n) % n] += v }   // wraps: the loop has no gap
        }
        // instruments
        fun kick(t: Double, g: Float) { var ph = 0.0; add(t, 0.35, g) { x -> ph += 2 * PI * (45 + 120 * exp(-x * 30)) / R; sin(ph) * exp(-x * 9) } }
        fun snare(t: Double, g: Float) { add(t, 0.22, g) { x -> (rnd.nextDouble() * 2 - 1) * exp(-x * 22) * 0.7 + sin(2 * PI * 190 * x) * exp(-x * 30) * 0.4 } }
        fun clap(t: Double, g: Float) { add(t, 0.25, g) { x -> var e = 0.0; for (o in doubleArrayOf(0.0, 0.01, 0.022)) if (x >= o) e = maxOf(e, exp(-(x - o) * (if (o > 0.02) 20.0 else 80.0))); (rnd.nextDouble() * 2 - 1) * e } }
        fun hat(t: Double, g: Float, len: Double = 0.05) { var prev = 0.0; add(t, len, g) { x -> val w = rnd.nextDouble() * 2 - 1; val h = w - prev; prev = w; h * exp(-x * 60) } }
        fun bass(t: Double, len: Double, m: Int, g: Float) { val fr = midi(m); add(t, len, g) { x -> val e = minOf(1.0, x * 200) * minOf(1.0, (len - x) * 30); (sin(2 * PI * fr * x) + 0.25 * sin(4 * PI * fr * x)) * e } }
        fun pad(t: Double, len: Double, ch: IntArray, g: Float) { ch.forEach { m -> val fr = midi(m); add(t, len, g / ch.size) { x -> val e = minOf(1.0, x / 0.4) * minOf(1.0, (len - x) / 0.4)
            (sin(2 * PI * fr * x) + 0.5 * sin(2 * PI * fr * 1.003 * x) + 0.3 * sin(4 * PI * fr * x)) * e * 0.6 } } }
        fun keys(t: Double, len: Double, m: Int, g: Float) { val fr = midi(m); add(t, len, g) { x -> val e = minOf(1.0, x * 300) * exp(-x * 2.2) * minOf(1.0, (len - x) * 20)
            (sin(2 * PI * fr * x) + 0.4 * sin(4 * PI * fr * x) * exp(-x * 3) + 0.15 * sin(6 * PI * fr * x) * exp(-x * 5)) * e * (1 + 0.08 * sin(2 * PI * 5 * x)) } }
        fun pluck(t: Double, m: Int, g: Float, len: Double = 0.6) {   // plucked string
            val p = maxOf(2, (R / midi(m)).toInt()); val buf = DoubleArray(p) { rnd.nextDouble() * 2 - 1 }; var k = 0
            add(t, len, g) { x -> val i = k % p; val j = (k + 1) % p; val v = buf[i]; buf[i] = 0.5 * (buf[i] + buf[j]) * 0.996; k++; v * minOf(1.0, (len - x) * 20) } }
        fun saw(t: Double, len: Double, m: Int, g: Float) { val fr = midi(m); var lp = 0.0; add(t, len, g) { x -> val raw = 2 * ((x * fr) % 1.0) - 1; lp += 0.18 * (raw - lp); lp * minOf(1.0, x * 400) * exp(-x * 4) } }

        for (b in 0 until bars) {
            val t0 = b * bar; val ch = prog[b % 4]; val root = ch[0] - 24; val late = b >= 8   // second half a bit fuller
            when (id) {
                "ceria" -> {
                    for (q in 0 until 4) { kick(t0 + q * beat, 0.55f); hat(t0 + q * beat + beat / 2, 0.18f) }
                    clap(t0 + beat, 0.3f); clap(t0 + 3 * beat, 0.3f)
                    for (e in 0 until 8) pluck(t0 + e * beat / 2, ch[e % ch.size] + 12, 0.32f)
                    bass(t0, beat * 1.9, root, 0.4f); bass(t0 + 2 * beat, beat * 1.9, root, 0.4f)
                    if (late) pad(t0, bar, ch, 0.18f)
                }
                "santai" -> {
                    kick(t0, 0.45f); kick(t0 + 2.5 * beat, 0.4f); snare(t0 + beat, 0.18f); snare(t0 + 3 * beat, 0.18f)
                    for (e in 0 until 8) hat(t0 + e * beat / 2 + (if (e % 2 == 1) beat * 0.08 else 0.0), 0.08f)
                    ch.forEachIndexed { i, m -> keys(t0 + i * 0.03, bar * 0.95, m, 0.16f) }
                    bass(t0, beat * 2.8, root, 0.35f); bass(t0 + 3 * beat, beat * 0.9, root + 7, 0.3f)
                    add(t0, bar, 0.012f) { _ -> if (rnd.nextDouble() < 0.002) rnd.nextDouble() * 6 - 3 else (rnd.nextDouble() * 2 - 1) * 0.3 }   // soft record noise
                }
                "dapur" -> {
                    kick(t0, 0.5f); kick(t0 + 2 * beat, 0.5f); kick(t0 + 2.75 * beat, 0.3f); snare(t0 + beat, 0.25f); snare(t0 + 3 * beat, 0.25f)
                    for (e in 0 until 16) hat(t0 + e * beat / 4, if (e % 2 == 0) 0.07f else 0.12f, 0.03)
                    for (e in 0 until 4) ch.forEachIndexed { i, m -> pluck(t0 + e * beat + beat / 2 + i * 0.012, m, 0.2f, 0.3) }
                    bass(t0, beat * 0.9, root, 0.4f); bass(t0 + 1.5 * beat, beat * 0.4, root + 7, 0.35f); bass(t0 + 2 * beat, beat * 0.9, root, 0.4f); bass(t0 + 3.5 * beat, beat * 0.4, root + 12, 0.3f)
                }
                "epik" -> {
                    pad(t0, bar, ch, 0.42f); pad(t0, bar, intArrayOf(ch[0] - 12), 0.25f)
                    kick(t0, 0.7f); kick(t0 + 2 * beat, 0.5f); if (late) { kick(t0 + 2.5 * beat, 0.4f); snare(t0 + 3 * beat, 0.4f) }
                    for (q in 0 until 4) { var ph = 0.0; val g = if (q % 2 == 0) 0.25f else 0.15f; add(t0 + q * beat, 0.5, g) { x -> ph += 2 * PI * (90 + 60 * exp(-x * 12)) / R; sin(ph) * exp(-x * 6) } }   // toms
                    bass(t0, bar * 0.98, root, 0.35f)
                    if (late) for (e in 0 until 8) pluck(t0 + e * beat / 2, ch[(e * 2) % ch.size] + 24, 0.14f, 0.4)
                }
                "tenaga" -> {
                    for (q in 0 until 4) { kick(t0 + q * beat, 0.7f); hat(t0 + q * beat + beat / 2, 0.22f, 0.08); bass(t0 + q * beat + beat / 2, beat * 0.45, root + 12, 0.42f) }
                    clap(t0 + beat, 0.32f); clap(t0 + 3 * beat, 0.32f)
                    for (e in 0 until 16) saw(t0 + e * beat / 4, beat / 4 * 0.9, ch[e % ch.size] + 12 + (if (e % 8 >= 4) 12 else 0), if (late) 0.2f else 0.12f)
                    pad(t0, bar, ch, 0.15f)
                }
                else -> {   // soft piano
                    keys(t0, bar, ch[0] - 12, 0.3f)
                    for (e in 0 until 8) keys(t0 + e * beat / 2, beat * 1.2, ch[e % ch.size] + (if (e >= 4) 12 else 0), 0.17f)
                    if (late) pad(t0, bar, ch, 0.14f)
                }
            }
        }
        // gentle limit to a nice level
        val pk = out.maxOfOrNull { abs(it) } ?: 1f; val g = if (pk > 0) 0.8f / pk else 1f
        for (i in out.indices) out[i] *= g
        return out
    }

    private fun write(a: FloatArray, f: File) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0); val data = a.size * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + data); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(R); h.putInt(R * 2); h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(data); raf.write(h.array())
            val b = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN); a.forEach { b.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }; raf.write(b.array())
        }
    }
}
