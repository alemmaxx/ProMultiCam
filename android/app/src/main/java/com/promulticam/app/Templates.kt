package com.promulticam.app

import android.content.Context
import com.promulticam.app.Ui.t
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* Editor templates: a finished edit whose video/photo places are empty "slots".
   Built in (below), published by the super admin (server), or saved by the person (on the phone). */
object Templates {
    class Item(val id: String, val name: String, val j: JSONObject, val kind: String) {   // kind: built / server / mine
        val slots get() = j.optJSONArray("slots")?.length() ?: 0
        val secs: Float get() { var s = 0f; val a = j.optJSONArray("slots") ?: return 0f; for (i in 0 until a.length()) s += a.getJSONObject(i).optDouble("dur", 2.0).toFloat(); return s }
    }
    private fun slot(dur: Float, fx: String = "none", trans: String = "none", look: String = "std", fi: Float = 0f, fo: Float = 0f) =
        JSONObject().put("dur", dur.toDouble()).put("fx", fx).put("trans", trans).put("look", look).put("fi", fi.toDouble()).put("fo", fo.toDouble())
    private fun tx(text: String, s: Float, e: Float, style: String = "boxword", col: String = "white", anim: String = "pop", y: Float = 0.78f, font: String = "bricolage", scale: Float = 1f, kind: String = "text") =
        JSONObject().put("kind", kind).put("text", text).put("s", (s * 1e6).toLong()).put("e", (e * 1e6).toLong()).put("style", style).put("col", col).put("anim", anim).put("x", 0.5).put("y", y.toDouble()).put("font", font).put("scale", scale.toDouble())
    private fun fx(k: String, at: Float) = JSONObject().put("kind", "sfx").put("sfx", k).put("s", (at * 1e6).toLong()).put("e", (at * 1e6 + 900_000).toLong()).put("vol", 1.0)
    private fun tpl(name: String, slots: List<JSONObject>, layers: List<JSONObject>, sounds: List<JSONObject> = emptyList(), w: Int = 1080, h: Int = 1920) =
        JSONObject().put("name", name).put("W", w).put("H", h).put("slots", JSONArray(slots)).put("layers", JSONArray(layers)).put("sounds", JSONArray(sounds))
    private fun joins(durs: List<Float>): List<Float> { val o = ArrayList<Float>(); var a = 0f; for (i in 0 until durs.size - 1) { a += durs[i]; o.add(a) }; return o }

    fun builtin(): List<Item> {
        val L = ArrayList<Item>()
        fun add(id: String, j: JSONObject) { L.add(Item("b_$id", j.getString("name"), j, "built")) }
        run { val d = listOf(2f, 1.5f, 1.5f, 1.5f, 2.5f)
            add("food", tpl(t("Food promo", "Promosi Makanan"), listOf(slot(2f, "zoomin", look = "warm"), slot(1.5f, "panl", "slide", "warm"), slot(1.5f, "zoomout", "whip", "warm"), slot(1.5f, "panr", "slide", "warm"), slot(2.5f, "zoomin", "zoom", "warm", fo = 0.6f)),
                listOf(tx(t("SO TASTY!", "SEDAP!"), 0.2f, 2f, "boxword", "yellow", "pop", 0.2f, scale = 1.4f), tx(t("Special price today", "Harga istimewa hari ini"), 6.6f, 9f, "darkbox", "yellow", "slide")),
                joins(d).map { fx("whoosh", it - 0.15f) })) }
        add("menu", tpl(t("Today's menu", "Menu Hari Ini"), List(4) { slot(2.5f, "zoomin", if (it == 0) "none" else "fade", "warm") },
            listOf(tx(t("TODAY'S MENU", "MENU HARI INI"), 0f, 10f, "highlight", "gold", "fade", 0.12f), tx(t("Dish 1", "Hidangan 1"), 0.3f, 2.5f, "darkbox"), tx(t("Dish 2", "Hidangan 2"), 2.6f, 5f, "darkbox"), tx(t("Dish 3", "Hidangan 3"), 5.1f, 7.5f, "darkbox"), tx(t("Dish 4", "Hidangan 4"), 7.6f, 10f, "darkbox"))))
        add("flash", tpl(t("Flash sale", "Jualan Kilat"), listOf(slot(2f, "pulse", look = "pop"), slot(2f, "shake", "flash", "pop"), slot(2.5f, "zoomin", "flash", "pop")),
            listOf(tx(t("FLASH SALE", "JUALAN KILAT"), 0.1f, 4f, "boxword", "red", "shake", 0.18f, scale = 1.3f), tx(t("Limited stock!", "Stok terhad!"), 4.1f, 6.5f, "boxword", "yellow", "pop", 0.75f)),
            listOf(fx("ding", 0.1f), fx("cash", 4.1f))))
        add("review", tpl(t("Testimonial", "Testimoni"), List(3) { slot(3f, "zoomin", if (it == 0) "none" else "fade", "soft") },
            listOf(tx(t("“Really good!”", "“Sedap sangat!”"), 0.3f, 3f, "shadow", "white", "type", 0.7f, "lobster", 1.2f), tx(t("“Will order again”", "“Mesti order lagi”"), 3.2f, 6f, "shadow", "white", "type", 0.7f, "lobster", 1.2f),
                tx(t("— Happy customers", "— Pelanggan kami"), 6.2f, 9f, "darkbox", "gold", "fade", 0.8f))))
        add("beforeafter", tpl(t("Before & after", "Sebelum & Selepas"), listOf(slot(3f, "zoomin"), slot(3.5f, "zoomin", "wipe")),
            listOf(tx(t("BEFORE", "SEBELUM"), 0.2f, 3f, "boxword", "white", "pop", 0.15f), tx(t("AFTER", "SELEPAS"), 3.2f, 6.5f, "boxword", "yellow", "pop", 0.15f)), listOf(fx("whoosh", 2.85f))))
        add("open", tpl(t("We're open", "Kami Dah Buka"), listOf(slot(2.5f, "zoomin", look = "cine", fi = 0.5f), slot(2.5f, "panl", "doors", "cine"), slot(3f, "zoomout", "fade", "cine", fo = 0.6f)),
            listOf(tx(t("WE'RE OPEN!", "KAMI DAH BUKA!"), 0.4f, 4f, "neon", "gold", "pop", 0.25f, scale = 1.3f), tx(t("Come and visit us", "Jemput datang"), 5.2f, 8f, "darkbox", "white", "slide")), listOf(fx("ding", 0.4f))))
        run { val n = 5
            add("steps", tpl(t("Recipe steps", "Resepi Langkah demi Langkah"), List(n) { slot(2.5f, "zoomin", if (it == 0) "none" else "slide") },
                List(n) { tx(t("Step ${it + 1}", "Langkah ${it + 1}"), it * 2.5f + 0.2f, it * 2.5f + 2.4f, "boxword", "gold", "slide", 0.16f) })) }
        add("greet", tpl(t("Festive greeting", "Ucapan Perayaan"), List(3) { slot(3f, "zoomin", if (it == 0) "none" else "fade", "soft", if (it == 0) 0.6f else 0f, if (it == 2) 0.8f else 0f) },
            listOf(tx(t("Season's Greetings", "Salam Perayaan"), 0.5f, 9f, "glow", "gold", "fade", 0.3f, "lobster", 1.4f), tx(t("From all of us", "Daripada kami semua"), 6f, 9f, "shadow", "white", "fade", 0.75f))))
        add("slides", tpl(t("Photo slideshow", "Slideshow Gambar"), List(6) { slot(2f, if (it % 2 == 0) "zoomin" else "zoomout", if (it == 0) "none" else "fade") }, listOf()))
        add("energy", tpl(t("Fast & lively", "Cepat & Bertenaga"), List(8) { slot(0.8f, "pulse", if (it == 0) "none" else if (it % 2 == 0) "flash" else "whip", "pop") },
            listOf(tx(t("LET'S GO!", "JOM!"), 0f, 1.6f, "bubble", "yellow", "pop", 0.4f, scale = 1.6f))))
        add("new", tpl(t("New product", "Produk Baru"), listOf(slot(2f, "zoomin"), slot(2f, "sway", "zoom"), slot(2f, "zoomout", "zoom"), slot(2.5f, "zoomin", "circle")),
            listOf(tx(t("NEW!", "BARU!"), 0.2f, 3f, "d3", "yellow", "pop", 0.2f, scale = 1.6f), tx(t("Get yours now", "Dapatkan sekarang"), 6.2f, 8.5f, "boxword", "white", "slide")), listOf(fx("pop", 0.2f))))
        add("cine", tpl(t("Cinematic story", "Kisah Sinematik"), List(3) { slot(3f, if (it == 1) "panl" else "zoomin", if (it == 0) "none" else "black", "cine", if (it == 0) 0.8f else 0f, if (it == 2) 1f else 0f) },
            listOf(tx(t("Our story", "Kisah Kami"), 0.6f, 3f, "retro", "gold", "fade", 0.5f, "lobster", 1.3f))))
        add("tips", tpl(t("Quick tips", "Tip Ringkas"), List(4) { slot(3f, "zoomin", if (it == 0) "none" else "slideup") },
            List(4) { tx("Tip #${it + 1}", it * 3f + 0.2f, it * 3f + 2.9f, "highlight", "yellow", "slide", 0.15f) }))
        add("count", tpl(t("Countdown", "Kiraan Detik"), List(4) { slot(1f, "pulse", if (it == 0) "none" else "flash") },
            listOf(tx("3", 0f, 1f, "outline", "white", "pop", 0.45f, scale = 3f), tx("2", 1f, 2f, "outline", "white", "pop", 0.45f, scale = 3f), tx("1", 2f, 3f, "outline", "white", "pop", 0.45f, scale = 3f),
                tx(t("START!", "MULA!"), 3f, 4f, "boxword", "yellow", "pop", 0.45f, scale = 1.6f)), listOf(fx("pop", 0f), fx("pop", 1f), fx("pop", 2f), fx("ding", 3f))))
        add("thanks", tpl(t("Thank you ending", "Penutup Terima Kasih"), listOf(slot(3f, "zoomin"), slot(3f, "zoomout", "fade", fo = 1f)),
            listOf(tx(t("Thank you for watching", "Terima kasih kerana menonton"), 0.3f, 3f, "shadow", "white", "fade", 0.5f, scale = 1.1f), tx(t("Follow us for more", "Ikuti kami untuk lagi"), 3.2f, 6f, "boxword", "gold", "pop", 0.75f))))
        add("music", tpl(t("Music video", "Video Muzik"), List(6) { slot(1.5f, if (it % 2 == 0) "sway" else "pulse", if (it == 0) "none" else "flash", "cine") }, listOf()))
        return L
    }

    /* ---- saved on the phone ---- */
    private fun dir(c: Context) = File(c.filesDir, "templates").apply { mkdirs() }
    fun mine(c: Context): List<Item> = dir(c).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { f -> try { val j = JSONObject(f.readText()); Item("m_" + f.nameWithoutExtension, j.optString("name"), j, "mine") } catch (e: Exception) { null } } ?: emptyList()
    fun saveMine(c: Context, j: JSONObject) { File(dir(c), "t_" + System.currentTimeMillis() + ".json").writeText(j.toString()) }
    fun deleteMine(c: Context, id: String) { File(dir(c), id.removePrefix("m_") + ".json").delete() }
    /* ---- on the server (published by the super admin) ---- */
    fun server(): List<Item> = try { Fs.list("templates", "at", limit = 200).mapNotNull { d -> try { Item("s_" + d.id, d.s("name"), JSONObject(d.s("json")), "server") } catch (e: Exception) { null } } } catch (e: Exception) { emptyList() }
    fun publish(j: JSONObject): Boolean = Fs.write("templates/" + Fs.newId(), mapOf("name" to j.optString("name"), "json" to j.toString()), now = listOf("at"), merge = false)
    fun unpublish(id: String): Boolean = Fs.delete("templates/" + id.removePrefix("s_"))

    /* the edit in the editor → a template (videos/photos become empty slots; text, stickers, brand and sound effects are kept) */
    fun fromProject(name: String, W: Int, H: Int, clips: List<Clip>, layers: List<Layer>, sounds: List<Sound>): JSONObject {
        val slots = clips.map { c -> slot(c.dur / 1e6f, c.fx, c.trans, c.look, c.fi, c.fo).put("speed", c.speed.toDouble()).put("bri", c.bri.toDouble()).put("con", c.con.toDouble()).put("sat", c.sat.toDouble()) }
        val ls = layers.filter { it.kind in listOf("text", "sticker", "brand") }.map { l -> JSONObject().put("kind", l.kind).put("text", l.text).put("s", l.s).put("e", l.e).put("style", l.style).put("col", l.col).put("anim", l.anim)
            .put("x", l.x.toDouble()).put("y", l.y.toDouble()).put("font", l.font).put("scale", l.scale.toDouble()) }
        val ss = sounds.filter { it.kind == "sfx" }.map { s -> JSONObject().put("kind", "sfx").put("sfx", s.sfx).put("s", s.s).put("e", s.e).put("vol", s.vol.toDouble()) }
        return tpl(name, slots, ls, ss, W, H)
    }
    fun layers(j: JSONObject): List<Layer> { val a = j.optJSONArray("layers") ?: return emptyList(); return (0 until a.length()).map { i -> val o = a.getJSONObject(i)
        Layer(o.optString("kind", "text"), o.optLong("s"), o.optLong("e")).also { l -> l.text = o.optString("text"); l.style = o.optString("style", "boxword"); l.col = o.optString("col", "white"); l.anim = o.optString("anim", "pop")
            l.x = o.optDouble("x", 0.5).toFloat(); l.y = o.optDouble("y", 0.78).toFloat(); l.font = o.optString("font", "bricolage"); l.scale = o.optDouble("scale", 1.0).toFloat() } } }
    fun sounds(j: JSONObject): List<Sound> { val a = j.optJSONArray("sounds") ?: return emptyList(); return (0 until a.length()).map { i -> val o = a.getJSONObject(i)
        Sound("sfx", o.optLong("s"), o.optLong("e")).also { s -> s.sfx = o.optString("sfx", "ding"); s.vol = o.optDouble("vol", 1.0).toFloat() } } }
}
