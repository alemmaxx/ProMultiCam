package com.promulticam.app

import kotlin.math.cos
import kotlin.math.sin

/* The same visual styles as the web app, turned into one colour matrix each (done on the GPU) */
object Looks {
    class Look(val id: String, val en: String, val ms: String, val f: String, val vig: Float = 0f, val bars: Boolean = false, val ev: Int = 0)   // ev: camera brightness step
    val ALL = listOf(
        Look("std", "Standard", "Standard", ""),
        // bright, sharp, clear: lifts dark rooms, sharpens detail, richer colour, camera a little brighter, more data when saved
        Look("supermassive", "Supermassive", "Supermassive", "brightness(1.08) contrast(1.1) saturate(1.22) sharp(0.75) lift(0.3)", 0.12f, false, 1),
        Look("super", "Super", "Super", "contrast(1.08) saturate(1.18) brightness(1.03)", 0.22f),
        Look("fantastic", "Fantastic", "Fantastic", "sepia(0.14) saturate(1.28) contrast(1.12) hue-rotate(-5)", 0.38f),
        Look("superior", "Superior", "Superior", "contrast(1.16) saturate(1.22) brightness(0.99) sepia(0.06)", 0.3f),
        Look("cine", "Cinematic", "Sinematik", "contrast(1.15) saturate(0.82) hue-rotate(-6)", 0.6f, true),
        Look("old", "Old School", "Old School", "sepia(0.75) contrast(1.1) brightness(0.92)", 0.6f),
        Look("warm", "Warm Kitchen", "Hangat Dapur", "sepia(0.25) saturate(1.3) brightness(1.05)"),
        Look("pop", "Food Pop", "Food Pop", "saturate(1.6) contrast(1.12)"),
        Look("bw", "Black & White", "Hitam Putih", "grayscale(1) contrast(1.25)", 0.6f),
        Look("vhs", "Retro 90s", "Retro 90an", "saturate(1.25) contrast(0.88) brightness(1.08) hue-rotate(-10)"),
        Look("drama", "Dramatic", "Dramatik", "contrast(1.45) brightness(0.82) saturate(1.1)", 0.6f),
        Look("soft", "Soft", "Lembut", "brightness(1.1) contrast(0.85) saturate(0.9)"))
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL[1]

    /* 3x3 colour part + offset, as a column-major mat4 and a vec4 for the shader */
    fun matrix(f: String): Pair<FloatArray, FloatArray> {
        var m = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        var o = floatArrayOf(0f, 0f, 0f)
        Regex("([a-z-]+)\\(([-0-9.]+)\\)").findAll(f).forEach { r ->
            val v = r.groupValues[2].toFloat()
            val (sm, so) = step(r.groupValues[1], v)
            // new = sm * (m*x + o) + so
            val nm = Array(3) { i -> FloatArray(3) { j -> (0..2).sumOf { k -> (sm[i][k] * m[k][j]).toDouble() }.toFloat() } }
            val no = FloatArray(3) { i -> (0..2).sumOf { k -> (sm[i][k] * o[k]).toDouble() }.toFloat() + so[i] }
            m = nm; o = no
        }
        val cm = FloatArray(16); cm[15] = 1f
        for (i in 0..2) for (j in 0..2) cm[j * 4 + i] = m[i][j]
        return Pair(cm, floatArrayOf(o[0], o[1], o[2], 0f))
    }

    private fun step(name: String, v: Float): Pair<Array<FloatArray>, FloatArray> {
        val z = floatArrayOf(0f, 0f, 0f)
        return when (name) {
            "brightness" -> Pair(diag(v), z)
            "contrast" -> Pair(diag(v), floatArrayOf(0.5f - 0.5f * v, 0.5f - 0.5f * v, 0.5f - 0.5f * v))
            "saturate" -> Pair(arrayOf(
                floatArrayOf(0.213f + 0.787f * v, 0.715f - 0.715f * v, 0.072f - 0.072f * v),
                floatArrayOf(0.213f - 0.213f * v, 0.715f + 0.285f * v, 0.072f - 0.072f * v),
                floatArrayOf(0.213f - 0.213f * v, 0.715f - 0.715f * v, 0.072f + 0.928f * v)), z)
            "grayscale" -> { val a = 1f - v.coerceIn(0f, 1f); Pair(arrayOf(
                floatArrayOf(0.2126f + 0.7874f * a, 0.7152f - 0.7152f * a, 0.0722f - 0.0722f * a),
                floatArrayOf(0.2126f - 0.2126f * a, 0.7152f + 0.2848f * a, 0.0722f - 0.0722f * a),
                floatArrayOf(0.2126f - 0.2126f * a, 0.7152f - 0.7152f * a, 0.0722f + 0.9278f * a)), z) }
            "sepia" -> { val a = 1f - v.coerceIn(0f, 1f); Pair(arrayOf(
                floatArrayOf(0.393f + 0.607f * a, 0.769f - 0.769f * a, 0.189f - 0.189f * a),
                floatArrayOf(0.349f - 0.349f * a, 0.686f + 0.314f * a, 0.168f - 0.168f * a),
                floatArrayOf(0.272f - 0.272f * a, 0.534f - 0.534f * a, 0.131f + 0.869f * a)), z) }
            "hue-rotate" -> { val r = Math.toRadians(v.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat(); Pair(arrayOf(
                floatArrayOf(0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f),
                floatArrayOf(0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f),
                floatArrayOf(0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f)), z) }
            else -> Pair(diag(1f), z)
        }
    }
    private fun diag(v: Float) = arrayOf(floatArrayOf(v, 0f, 0f), floatArrayOf(0f, v, 0f), floatArrayOf(0f, 0f, v))
}
