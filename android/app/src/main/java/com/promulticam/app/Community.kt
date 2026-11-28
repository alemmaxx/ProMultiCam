package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/* the user's public name (shown on templates they share), separate from the email */
object Profile {
    private var cached: String? = null; private var cachedUid: String? = null
    private fun sp(c: Context) = c.getSharedPreferences("pmc", Context.MODE_PRIVATE)
    /* the name on this phone (fast); null if none */
    fun name(c: Context): String? { val u = Auth.uid ?: return null; if (cachedUid == u && cached != null) return cached
        return sp(c).getString("uname_$u", null)?.also { cached = it; cachedUid = u } }
    /* read it from the server (call off the main thread) */
    fun load(c: Context): String? { val u = Auth.uid ?: return null
        val n = try { Fs.get("profiles/$u")?.s("username")?.ifEmpty { null } } catch (e: Exception) { null }
        if (n != null) { sp(c).edit().putString("uname_$u", n).apply(); cached = n; cachedUid = u }
        return n ?: name(c) }
    fun valid(n: String) = n.isNotEmpty() && n.length <= 30 && !n.contains('/') && !Mod.bad(n)
    /* true = free (or already mine), false = someone has it, null = no internet */
    fun available(raw: String): Boolean? { val n = raw.trim().removePrefix("@").trim(); return try { val d = Fs.get("usernames/" + key(n)); d == null || d.s("uid") == Auth.uid } catch (e: Exception) { null } }
    fun key(n: String) = n.lowercase().replace(Regex("\\s+"), "").replace('/', '_').replace('.', '_').ifEmpty { "_" }
    /* take a name: null = done, otherwise the problem ("TAKEN", "BAD", "NETWORK") */
    fun claim(c: Context, raw: String): String? {
        val u = Auth.uid ?: return "NETWORK"; val n = raw.trim().removePrefix("@").trim()
        if (!valid(n)) return "BAD"
        val old = load(c); if (old == n) return null
        return try {
            if (!Fs.write("usernames/" + key(n), mapOf("uid" to u), merge = false, create = true)) {
                if (Fs.get("usernames/" + key(n))?.s("uid") == u) null else return "TAKEN" }
            if (!Fs.write("profiles/$u", mapOf("username" to n), now = listOf("at"))) return "NETWORK"
            if (old != null && key(old) != key(n)) Fs.delete("usernames/" + key(old))
            sp(c).edit().putString("uname_$u", n).apply(); cached = n; cachedUid = u; null
        } catch (e: Exception) { "NETWORK" }
    }
    fun errText(e: String) = when (e) { "TAKEN" -> Ui.t("That name is taken. Try another.", "Nama itu sudah diambil. Cuba yang lain.")
        "BAD" -> Ui.t("Type a name (up to 30)", "Taip nama (maksimum 30)"); else -> Ui.t("No internet connection", "Tiada sambungan internet") }
}

/* templates shared by users, with a cover picture and how many times they were used */
object Shared {
    class T(val id: String, val name: String, val user: String, val uid: String, val uses: Long, val cover: String, val j: JSONObject)
    fun list(): List<T> = try { Fs.list("shared", "uses", limit = 80).mapNotNull { d -> try { T(d.id, d.s("name"), d.s("user"), d.s("uid"), d.n("uses").toLong(), d.s("cover"), JSONObject(d.s("json"))) } catch (e: Exception) { null } } } catch (e: Exception) { emptyList() }
    fun share(name: String, user: String, cat: String, j: JSONObject, cover: Bitmap): Boolean {
        val uid = Auth.uid ?: return false
        j.put("name", name).put("cat", cat)
        return Fs.write("shared/" + Fs.newId(), mapOf("name" to name, "user" to user, "uid" to uid, "uses" to 0L, "cat" to cat, "cover" to jpeg(cover, 70), "json" to j.toString()), now = listOf("at"), merge = false)
    }
    fun used(id: String) { try { Fs.commit(listOf(JSONObject().put("update", JSONObject().put("name", Fs.docName("shared/$id")).put("fields", JSONObject()))
        .put("updateMask", JSONObject().put("fieldPaths", JSONArray())).put("currentDocument", JSONObject().put("exists", true))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "uses").put("increment", JSONObject().put("integerValue", "1")))))) } catch (e: Exception) {} }
    fun count(n: Long) = when { n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1e6); n >= 1000 -> String.format(java.util.Locale.US, "%.1fK", n / 1e3); else -> n.toString() }
    fun delete(id: String) = try { Fs.delete("shared/$id") } catch (e: Exception) { false }
    fun jpeg(b: Bitmap, q: Int): String { val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, o); return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP) }
    fun bitmap(s: String): Bitmap? = try { val a = Base64.decode(s, Base64.DEFAULT); BitmapFactory.decodeByteArray(a, 0, a.size) } catch (e: Exception) { null }

    /* a cover for our own templates: coloured background in the template's mood, with its title drawn in its own text style */
    fun builtCover(art: TextArt, it: Templates.Item, w: Int = 270, h: Int = 480): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val (c1, c2) = when (it.cat) { "food" -> 0xFFB5542A.toInt() to 0xFF3A1A0E.toInt(); "sale" -> 0xFFC8331E.toInt() to 0xFF2B0B12.toInt(); "greet" -> 0xFFD07A9A.toInt() to 0xFF35203A.toInt()
            "music" -> 0xFF2E3C8C.toInt() to 0xFF0C0F24.toInt(); else -> 0xFF2D7A78.toInt() to 0xFF0E2324.toInt() }
        // a cartoon example picture of the template's kind, a little darker so the title reads well
        try { cv.drawBitmap(DemoArt.draw(it.cat, 0, w, h), 0f, 0f, null) } catch (e: Exception) { val p = Paint(); p.shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), c1, c2, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p) }
        val sc = Paint(); sc.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x66000000, 0x22000000, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sc)
        // the boxes of the template, like little film frames
        val fp = Paint(Paint.ANTI_ALIAS_FLAG); fp.color = 0x22FFFFFF; val n = it.slots.coerceIn(1, 6)
        for (k in 0 until n) { val y = h * 0.62f + (k / 3) * h * 0.13f; val x = w * 0.08f + (k % 3) * w * 0.29f; cv.drawRoundRect(x, y, x + w * 0.26f, y + h * 0.11f, 10f, 10f, fp) }
        // the first words of the template, as they look in the video
        val layers = Templates.layers(it.j).filter { l -> l.kind == "text" }.take(2)
        if (layers.isNotEmpty()) {
            val W = 1080; val H = 1920; val t = layers[0].s + 200_000L
            val ov = art.render(layers.filter { l -> l.s <= t && t <= l.e }.map { l -> l.copy().also { c -> c.anim = "none"; c.y = if (c.y > 0.6f) 0.42f else c.y.coerceAtMost(0.42f) } }, t, W, H)
            cv.drawBitmap(Bitmap.createScaledBitmap(ov, w, h, true), 0f, 0f, null); ov.recycle()
        } else {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG); tp.color = Color.WHITE; tp.textSize = w * 0.11f; tp.isFakeBoldText = true; tp.textAlign = Paint.Align.CENTER
            cv.drawText(it.name.take(16), w / 2f, h * 0.4f, tp)
        }
        return b
    }
}
