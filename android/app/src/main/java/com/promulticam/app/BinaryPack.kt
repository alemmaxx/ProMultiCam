package com.promulticam.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/* The message format the web app uses between phones (PeerJS "binary"): a small MessagePack-like encoding */
object BinaryPack {
    fun pack(v: Any?): ByteArray { val o = ByteArrayOutputStream(); w(o, v); return o.toByteArray() }

    private fun u8(o: ByteArrayOutputStream, b: Int) = o.write(b and 0xff)
    private fun u16(o: ByteArrayOutputStream, v: Int) { u8(o, v shr 8); u8(o, v) }
    private fun u32(o: ByteArrayOutputStream, v: Long) { u8(o, (v shr 24).toInt()); u8(o, (v shr 16).toInt()); u8(o, (v shr 8).toInt()); u8(o, v.toInt()) }

    private fun w(o: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> u8(o, 0xc0)
            is Boolean -> u8(o, if (v) 0xc3 else 0xc2)
            is Int -> int(o, v.toLong())
            is Long -> int(o, v)
            is Float -> dbl(o, v.toDouble())
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 9e15) int(o, v.toLong()) else dbl(o, v)
            is String -> { val b = v.toByteArray(Charsets.UTF_8); val n = b.size
                if (n <= 0x0f) u8(o, 0xb0 + n) else if (n <= 0xffff) { u8(o, 0xd8); u16(o, n) } else { u8(o, 0xd9); u32(o, n.toLong()) }
                o.write(b) }
            is ByteArray -> { val n = v.size
                if (n <= 0x0f) u8(o, 0xa0 + n) else if (n <= 0xffff) { u8(o, 0xda); u16(o, n) } else { u8(o, 0xdb); u32(o, n.toLong()) }
                o.write(v) }
            is Map<*, *> -> { val n = v.size
                if (n <= 0x0f) u8(o, 0x80 + n) else if (n <= 0xffff) { u8(o, 0xde); u16(o, n) } else { u8(o, 0xdf); u32(o, n.toLong()) }
                for ((k, x) in v) { w(o, k.toString()); w(o, x) } }
            is List<*> -> { val n = v.size
                if (n <= 0x0f) u8(o, 0x90 + n) else if (n <= 0xffff) { u8(o, 0xdc); u16(o, n) } else { u8(o, 0xdd); u32(o, n.toLong()) }
                for (x in v) w(o, x) }
            is Number -> dbl(o, v.toDouble())
            else -> w(o, v.toString())
        }
    }
    private fun int(o: ByteArrayOutputStream, n: Long) {
        when {
            n in -0x20..0x7f -> u8(o, n.toInt())
            n in 0..0xff -> { u8(o, 0xcc); u8(o, n.toInt()) }
            n in -0x80..0x7f -> { u8(o, 0xd0); u8(o, n.toInt()) }
            n in 0..0xffff -> { u8(o, 0xcd); u16(o, n.toInt()) }
            n in -0x8000..0x7fff -> { u8(o, 0xd1); u16(o, n.toInt()) }
            n in 0..0xffffffffL -> { u8(o, 0xce); u32(o, n) }
            n in Int.MIN_VALUE..Int.MAX_VALUE -> { u8(o, 0xd2); u32(o, n) }
            else -> { u8(o, 0xd3); u32(o, n shr 32); u32(o, n) }
        }
    }
    private fun dbl(o: ByteArrayOutputStream, d: Double) { u8(o, 0xcb); val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(d).array(); o.write(b) }

    fun unpack(b: ByteArray): Any? = R(ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)).v()

    private class R(val b: ByteBuffer) {
        fun u8() = b.get().toInt() and 0xff
        fun u16() = b.short.toInt() and 0xffff
        fun u32() = b.int.toLong() and 0xffffffffL
        fun raw(n: Int): ByteArray { val a = ByteArray(n); b.get(a); return a }
        fun str(n: Int) = String(raw(n), Charsets.UTF_8)
        fun arr(n: Int): List<Any?> = List(n) { v() }
        fun map(n: Int): Map<String, Any?> { val m = LinkedHashMap<String, Any?>(); repeat(n) { val k = v(); m[k.toString()] = v() }; return m }
        fun v(): Any? {
            val t = u8()
            if (t < 0x80) return t
            if ((t xor 0xe0) < 0x20) return (t xor 0xe0) - 0x20
            if ((t xor 0xa0) <= 0x0f) return raw(t xor 0xa0)
            if ((t xor 0xb0) <= 0x0f) return str(t xor 0xb0)
            if ((t xor 0x90) <= 0x0f) return arr(t xor 0x90)
            if ((t xor 0x80) <= 0x0f) return map(t xor 0x80)
            return when (t) {
                0xc0, 0xc1 -> null
                0xc2 -> false
                0xc3 -> true
                0xca -> b.float.toDouble()
                0xcb -> b.double
                0xcc -> u8(); 0xcd -> u16(); 0xce -> u32(); 0xcf -> b.long
                0xd0 -> b.get().toInt(); 0xd1 -> b.short.toInt(); 0xd2 -> b.int; 0xd3 -> b.long
                0xd8 -> str(u16()); 0xd9 -> str(u32().toInt())
                0xda -> raw(u16()); 0xdb -> raw(u32().toInt())
                0xdc -> arr(u16()); 0xdd -> arr(u32().toInt())
                0xde -> map(u16()); 0xdf -> map(u32().toInt())
                else -> null
            }
        }
    }
}
