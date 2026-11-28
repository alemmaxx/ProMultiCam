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
        val cat get() = j.optString("cat", "life"); val music get() = j.optString("music")
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
        fun add(id: String, j: JSONObject, cat: String = "life") { if (!j.has("cat")) j.put("cat", cat); L.add(Item("b_$id", j.getString("name"), j, "built")) }
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
        // older ones: put them in groups
        val cats = mapOf("food" to "food", "menu" to "food", "flash" to "sale", "review" to "sale", "beforeafter" to "life", "open" to "sale", "steps" to "food", "greet" to "greet",
            "slides" to "life", "energy" to "music", "new" to "sale", "cine" to "music", "tips" to "life", "count" to "life", "thanks" to "greet", "music" to "music")
        L.forEach { it.j.put("cat", cats[it.id.removePrefix("b_")] ?: "life") }
        L.addAll(withMusic())
        return L
    }

    /* templates with our own music: each clip lasts a set number of beats, so every change lands on the beat */
    private class St(val id: String, val cat: String, val en: String, val ms: String, val song: String, val n: Int, val beats: Int, val fxs: List<String>, val trs: List<String>, val look: String,
                     val title: Pair<String, String>?, val sub: Pair<String, String>? = null, val each: ((Int) -> Pair<String, String>)? = null, val tStyle: String = "boxword", val tCol: String = "yellow", val tFont: String = "bricolage")
    private fun withMusic(): List<Item> {
        val S = listOf(
            St("quickrecipe", "food", "Quick recipe", "Resepi pantas", "dapur", 6, 4, listOf("zoomin", "panl", "panr"), listOf("slide", "whip"), "warm", "EASY RECIPE" to "RESEPI MUDAH", each = { ("Step ${it + 1}") to ("Langkah ${it + 1}") }, tCol = "gold"),
            St("yum", "food", "So yummy", "Sedapnya!", "ceria", 8, 2, listOf("pulse", "zoomin"), listOf("flash", "zoom"), "pop", "SO YUMMY!" to "SEDAPNYA!", tStyle = "bubble"),
            St("weekmenu", "food", "This week's menu", "Menu minggu ini", "santai", 5, 4, listOf("zoomin", "zoomout"), listOf("fade"), "warm", "THIS WEEK" to "MINGGU INI",
                each = { listOf("Monday" to "Isnin", "Tuesday" to "Selasa", "Wednesday" to "Rabu", "Thursday" to "Khamis", "Friday" to "Jumaat")[it] }, tStyle = "darkbox", tCol = "white"),
            St("special", "food", "Chef's special", "Hidangan istimewa", "epik", 4, 4, listOf("zoomin", "panl"), listOf("black"), "cine", "Chef's Special" to "Hidangan Istimewa", tStyle = "glow", tCol = "gold", tFont = "lobster"),
            St("coffee", "food", "Morning coffee", "Kopi pagi", "santai", 4, 4, listOf("zoomin", "sway"), listOf("fade"), "soft", "Morning coffee ☕" to "Kopi pagi ☕", tStyle = "shadow", tCol = "white", tFont = "lobster"),
            St("dessert", "food", "Cakes & desserts", "Kek & pencuci mulut", "lembut", 5, 4, listOf("zoomin", "zoomout"), listOf("fade", "circle"), "soft", "Sweet treats" to "Manis-manis", tStyle = "glow", tCol = "pink", tFont = "lobster"),
            St("nightmarket", "food", "Night market", "Pasar malam", "tenaga", 8, 2, listOf("shake", "pulse"), listOf("whip", "flash"), "pop", "NIGHT MARKET" to "PASAR MALAM", tStyle = "neon", tCol = "orange"),
            St("grand", "sale", "Grand opening", "Pembukaan besar", "tenaga", 8, 2, listOf("zoomin", "pulse"), listOf("flash", "zoom"), "pop", "GRAND OPENING" to "PEMBUKAAN BESAR", "Come and visit!" to "Jemput datang!", tStyle = "d3"),
            St("discount", "sale", "Big discount", "Diskaun besar", "tenaga", 6, 2, listOf("shake", "pulse"), listOf("flash"), "pop", "50% OFF" to "DISKAUN 50%", "Limited time" to "Masa terhad", tCol = "red"),
            St("b1f1", "sale", "Buy 1 free 1", "Beli 1 percuma 1", "ceria", 6, 2, listOf("pulse", "zoomin"), listOf("zoom", "slide"), "pop", "BUY 1 FREE 1" to "BELI 1 PERCUMA 1", tStyle = "bubble"),
            St("launch", "sale", "New launch", "Produk baru", "epik", 5, 4, listOf("zoomin", "sway"), listOf("zoom", "circle"), "cine", "NEW!" to "BARU!", "Get yours now" to "Dapatkan sekarang", tStyle = "d3"),
            St("testi", "sale", "Happy customers", "Pelanggan gembira", "santai", 4, 4, listOf("zoomin"), listOf("fade"), "soft", "Happy customers" to "Pelanggan gembira", tStyle = "shadow", tCol = "white", tFont = "lobster"),
            St("order", "sale", "Order now", "Order sekarang", "dapur", 5, 2, listOf("pulse", "zoomin"), listOf("slide", "whip"), "warm", "ORDER NOW" to "ORDER SEKARANG", "Message us today" to "Mesej kami hari ini", tCol = "gold"),
            St("monthend", "sale", "Month-end sale", "Promosi hujung bulan", "tenaga", 8, 2, listOf("shake", "zoomin"), listOf("flash", "whip"), "pop", "MONTH-END SALE" to "PROMOSI HUJUNG BULAN", tStyle = "neon", tCol = "yellow"),
            St("bday", "greet", "Happy birthday", "Hari jadi", "ceria", 6, 4, listOf("zoomin", "sway"), listOf("fade", "zoom"), "soft", "Happy Birthday!" to "Selamat Hari Jadi!", tStyle = "glow", tCol = "pink", tFont = "lobster"),
            St("festive", "greet", "Festive wishes", "Ucapan perayaan", "lembut", 5, 4, listOf("zoomin", "zoomout"), listOf("fade"), "soft", "Happy Holidays" to "Selamat Hari Raya", "From all of us" to "Daripada kami semua", tStyle = "glow", tCol = "gold", tFont = "lobster"),
            St("thankyou", "greet", "Thank you", "Terima kasih", "lembut", 4, 4, listOf("zoomin"), listOf("fade"), "soft", "Thank you!" to "Terima kasih!", tStyle = "shadow", tCol = "white", tFont = "lobster"),
            St("celebrate", "greet", "Celebrate", "Sambutan", "epik", 6, 4, listOf("zoomin", "panl"), listOf("black", "fade"), "cine", "Let's celebrate" to "Mari meraikan", tStyle = "retro", tCol = "gold"),
            St("vlog", "life", "Daily vlog", "Vlog harian", "santai", 6, 4, listOf("zoomin", "panr"), listOf("fade", "slide"), "soft", "My day" to "Hari saya", tStyle = "highlight", tCol = "yellow"),
            St("trip", "life", "Trip", "Jalan-jalan", "ceria", 8, 4, listOf("panl", "panr", "zoomin"), listOf("slide", "slideup"), "pop", "Holiday time!" to "Masa bercuti!", tStyle = "bubble", tCol = "cyan"),
            St("ba2", "life", "Before & after", "Sebelum & selepas", "epik", 2, 8, listOf("zoomin"), listOf("wipe"), "std", null, each = { listOf("BEFORE" to "SEBELUM", "AFTER" to "SELEPAS")[it] }, tCol = "white"),
            St("memories", "life", "Memories", "Kenangan", "lembut", 8, 4, listOf("zoomin", "zoomout"), listOf("fade"), "soft", "Memories" to "Kenangan", tStyle = "glow", tCol = "white", tFont = "lobster"),
            St("howto", "life", "3-step how-to", "Tutorial 3 langkah", "dapur", 3, 8, listOf("zoomin"), listOf("slide"), "std", null, each = { ("Step ${it + 1}") to ("Langkah ${it + 1}") }, tCol = "gold"),
            St("top5", "life", "Top 5", "5 terbaik", "ceria", 5, 4, listOf("zoomin", "pulse"), listOf("slideup"), "pop", null, each = { ("#${5 - it}") to ("#${5 - it}") }, tStyle = "d3"),
            St("weekend", "life", "Weekend", "Hujung minggu", "ceria", 6, 4, listOf("sway", "zoomin"), listOf("slide", "fade"), "pop", "Weekend!" to "Hujung minggu!", tStyle = "bubble", tCol = "orange"),
            St("story", "life", "Business story", "Kisah usahawan", "epik", 6, 4, listOf("zoomin", "panl"), listOf("black"), "cine", "From a small kitchen…" to "Dari dapur kecil…", "…to many happy customers" to "…kepada ramai pelanggan", tStyle = "retro", tCol = "gold", tFont = "lobster"),
            St("beat16", "music", "Fast beat", "Rentak laju", "tenaga", 16, 1, listOf("pulse", "shake"), listOf("flash", "whip"), "pop", null),
            St("cinematic", "music", "Cinematic", "Sinematik", "epik", 6, 4, listOf("zoomin", "panl", "panr"), listOf("black"), "cine", null),
            St("lofi", "music", "Lo-fi chill", "Lo-fi santai", "santai", 8, 4, listOf("zoomin", "sway"), listOf("fade"), "soft", null),
            St("quick", "music", "Quick cuts", "Potongan pantas", "ceria", 12, 2, listOf("zoomin", "pulse"), listOf("whip", "zoom", "flash"), "pop", null),
            St("doors", "music", "Doors & circles", "Pintu & bulatan", "ceria", 8, 4, listOf("zoomin"), listOf("doors", "circle", "wipe"), "pop", null),
            St("piano", "music", "Soft piano", "Piano lembut", "lembut", 6, 4, listOf("zoomin", "zoomout"), listOf("fade", "white"), "soft", null))
        return showcase() + stories() + S.map { st ->
            val bpm = MusicLib.ALL.first { it.id == st.song }.bpm; val beat = 60f / bpm; val d = st.beats * beat
            val slots = List(st.n) { k -> slot(d, st.fxs[k % st.fxs.size], if (k == 0) "none" else st.trs[k % st.trs.size], st.look, if (k == 0) 0.3f else 0f, if (k == st.n - 1) 0.8f else 0f) }
            val lays = ArrayList<JSONObject>(); val total = d * st.n
            st.title?.let { lays.add(tx(t(it.first, it.second), 0.1f, minOf(total, d * 2), st.tStyle, st.tCol, "pop", 0.2f, st.tFont, 1.3f)) }
            st.each?.let { f -> for (k in 0 until st.n) { val p = f(k); lays.add(tx(t(p.first, p.second), k * d + 0.1f, (k + 1) * d - 0.05f, st.tStyle, st.tCol, "slide", if (st.title != null && k < 2) 0.75f else 0.18f, st.tFont, 1.1f)) } }
            st.sub?.let { lays.add(tx(t(it.first, it.second), maxOf(0f, total - d * 2), total, "darkbox", "white", "slide", 0.8f)) }
            Item("b_" + st.id, t(st.en, st.ms), tpl(t(st.en, st.ms), slots, lays).put("cat", st.cat).put("music", st.song), "built")
        }
    }
    /* "Super": templates that use every tool of the editor at once */
    private fun showcase(): List<Item> {
        val out = ArrayList<Item>()
        // 1) everything: 16 boxes, every switch, every moving effect, many colour looks, text styles, stickers, decorations, keyframes, sound effects and music
        run {
            val beat = 60f / 112; val d = beat * 4; val n = 16; val total = d * n
            val trs = Fx.TRANS.filter { it != "none" }; val fxs = Fx.EFFECTS.filter { it != "none" }
            val looks = listOf("supermassive", "super", "fantastic", "superior", "cine", "warm", "pop", "vhs", "drama", "soft", "old", "bw")
            val slots = List(n) { k -> slot(d, fxs[k % fxs.size], if (k == 0) "none" else trs[(k - 1) % trs.size], looks[k % looks.size], if (k == 0) 0.5f else 0f, if (k == n - 1) 1.2f else 0f).also { o ->
                o.put("dn", true)
                if (k == 2) o.put("blur", true)                                   // blurred sides
                if (k == 3) o.put("kf", "0,1.0000,0.0000,0.0000;1500000,1.6000,0.1000,-0.0800")   // keyframe zoom & move
                if (k == 6) o.put("speed", 0.5)                                    // slow motion
                if (k == 9) o.put("speed", 2.0)                                    // fast
                if (k == 11) o.put("sat", 0.3).put("con", 0.15) } }
            val L = ArrayList<JSONObject>()
            // title flies down with keyframes
            L.add(tx(t("EVERYTHING", "SEMUA FUNGSI"), 0f, d * 2, "d3", "gold", "pop", 0.2f, "anton", 1.5f).put("kf", "0,1.5000,0.0000,-0.3000;1600000,1.5000,0.0000,-0.1200;3200000,1.2000,0.0000,-0.1500"))
            L.add(tx(t("ProMultiCam editor", "Editor ProMultiCam"), 0.4f, d * 2, "plain", "yellow", "fade", 0.36f, "dancing", 1.1f))
            // a different text style / font / colour / animation on each box
            val styles = listOf("boxword", "outline", "plain", "shadow", "glow", "neon", "gradient", "d3", "bubble", "darkbox", "highlight", "retro")
            val fonts = listOf("bricolage", "poppins", "montserrat", "bebas", "anton", "playfair", "lobster", "pacifico", "dancing", "marker")
            val cols = listOf("white", "yellow", "gold", "orange", "red", "pink", "cyan", "white")
            val anims = listOf("pop", "slide", "fade", "type", "shake")
            for (k in 2 until n - 1) { val st = styles[k % styles.size]
                L.add(tx(Fx.transName(trs[(k - 1) % trs.size]) + " · " + Fx.fxName(fxs[k % fxs.size]), k * d + 0.1f, (k + 1) * d - 0.05f, st, cols[k % cols.size], anims[k % anims.size], 0.8f, fonts[k % fonts.size], 0.9f)) }
            L.add(tx(t("Thank you!", "Terima kasih!"), (n - 1) * d, total, "glow", "gold", "type", 0.45f, "lobster", 1.4f))
            // stickers
            L.add(tx("🔥", d * 4, d * 6, kind = "sticker", y = 0.3f, anim = "pop", scale = 1.2f).put("x", 0.78))
            L.add(tx("😋", d * 8, d * 10, kind = "sticker", y = 0.3f, anim = "shake", scale = 1.2f).put("x", 0.22))
            L.add(tx("💯", d * 12, d * 14, kind = "sticker", y = 0.28f, anim = "pop", scale = 1.3f).put("x", 0.75))
            // decorations, one after another (all 12)
            Deco.ALL.forEachIndexed { i, k -> val s0 = i * total / Deco.ALL.size; L.add(tx(k, s0, s0 + total / Deco.ALL.size + d * 0.6f, kind = "deco", anim = "none", scale = 1f)) }
            val snd = listOf(fx("whoosh", d - 0.1f), fx("pop", d * 4), fx("ding", d * 6), fx("drum", d * 8), fx("clap", d * 12), fx("cash", d * 14))
            out.add(Item("b_superall", t("Everything ✨", "Semua fungsi ✨"), tpl(t("Everything ✨", "Semua fungsi ✨"), slots, L, snd).put("cat", "super").put("music", "ceria"), "built"))
        }
        // 1b) "Weekend story" like the video the user sent: 13 quick clips, one big title all through (pulses on the strips),
        //     tilted shots, warm / black & white looks, pink-orange light leaks, white flashes, 3 strips and reveal
        run {
            val d = 4 * 60f / 112; val n = 13; val total = d * n
            val plan = listOf(Triple("dutch", "none", "cine"), Triple("zoomin", "leak", "warm"), Triple("sway", "white", "bw"), Triple("panr", "leak", "warm"),
                Triple("dutch", "tilt", "cine"), Triple("zoomin", "none", "cine"), Triple("dutch", "tilt", "cine"), Triple("zoomout", "none", "std"),
                Triple("zoomin", "bands", "cine"), Triple("zoomin", "reveal", "cine"), Triple("pulse", "bands", "cine"), Triple("zoomin", "leak", "warm"), Triple("dutch", "white", "cine"))
            val slots = plan.mapIndexed { k, (fx, tr, lk) -> slot(d, fx, tr, lk, if (k == 0) 0.3f else 0f, if (k == n - 1) 1f else 0f) }
            val t0 = (10 * d * 1e6).toLong(); val t1 = t0 + 350_000; val t2 = (11 * d * 1e6).toLong()
            fun f(v: Float) = String.format(java.util.Locale.US, "%.4f", v)
            val pulse = "0,${f(1.6f)},0.0000,${f(-0.08f)};$t0,${f(1.6f)},0.0000,${f(-0.08f)};$t1,${f(2.2f)},0.0000,${f(-0.08f)};$t2,${f(1.6f)},0.0000,${f(-0.08f)}"
            val L = listOf(tx("WEEKEND", 0f, total, "shadow", "white", "fade", 0.42f, "playfair", 1.6f).put("kf", pulse),
                tx("Story", 0.2f, total, "plain", "yellow", "fade", 0.47f, "dancing", 1.3f))
            out.add(Item("b_superweekend", "Weekend story ✨", tpl("Weekend story ✨", slots, L).put("cat", "super").put("music", "ceria"), "built"))
        }
        // 2) cinematic: light leaks, strips, reveal, tilt, story title, soft lights, film look
        run {
            val beat = 60f / 90; val d = beat * 4; val n = 10; val total = d * n
            val trs = listOf("leak", "bands", "reveal", "tilt", "circle", "doors", "leak", "wipe", "zoom")
            val slots = List(n) { k -> slot(d, listOf("zoomin", "dutch", "panl", "panr", "zoomout")[k % 5], if (k == 0) "none" else trs[(k - 1) % trs.size], "cine", if (k == 0) 0.8f else 0f, if (k == n - 1) 1.5f else 0f).put("blur", k % 4 == 1) }
            val L = listOf(tx(t("THE STORY", "KISAH KAMI"), 0f, total, "shadow", "white", "fade", 0.42f, "playfair", 1.5f),
                tx(t("begins", "bermula"), 0.3f, total, "plain", "gold", "fade", 0.47f, "dancing", 1.2f),
                tx("bokeh", 0f, total, kind = "deco", anim = "none", scale = 0.7f), tx("sparkle", d * 6, total, kind = "deco", anim = "none", scale = 0.8f))
            out.add(Item("b_supercine", t("Super cinematic", "Super sinematik"), tpl(t("Super cinematic", "Super sinematik"), slots, L).put("cat", "super").put("music", "epik"), "built"))
        }
        // 3) party / promo: fast beat, flashes, confetti, hearts, stickers, big sale text
        run {
            val beat = 60f / 128; val d = beat * 2; val n = 14; val total = d * n
            val trs = listOf("flash", "whip", "zoom", "slide", "bands", "slideup", "white", "tilt")
            val slots = List(n) { k -> slot(d, listOf("pulse", "shake", "zoomin", "blink")[k % 4], if (k == 0) "none" else trs[(k - 1) % trs.size], "pop").put("speed", if (k % 5 == 4) 1.5 else 1.0) }
            val L = listOf(tx(t("BIG SALE", "JUALAN HEBAT"), 0f, d * 4, "neon", "yellow", "shake", 0.3f, "anton", 1.6f),
                tx(t("50% OFF", "DISKAUN 50%"), d * 4, d * 9, "d3", "red", "pop", 0.4f, "bebas", 1.7f).put("kf", "0,1.0000,0.0000,0.0000;600000,1.4000,0.0000,0.0000;1200000,1.0000,0.0000,0.0000"),
                tx(t("ORDER NOW", "ORDER SEKARANG"), d * 9, total, "bubble", "gold", "slide", 0.8f, "poppins", 1.2f),
                tx("🛒", d * 9, total, kind = "sticker", y = 0.65f, anim = "shake", scale = 1.3f),
                tx("confetti", 0f, d * 5, kind = "deco", anim = "none", scale = 1.2f), tx("hearts", d * 5, d * 10, kind = "deco", anim = "none"), tx("stars", d * 10, total, kind = "deco", anim = "none"))
            val snd = listOf(fx("drum", 0.05f), fx("cash", d * 4), fx("clap", d * 9))
            out.add(Item("b_superparty", t("Super promo", "Super promosi"), tpl(t("Super promo", "Super promosi"), slots, L, snd).put("cat", "super").put("music", "tenaga"), "built"))
        }
        // 4) cooking: steam, warm look, keyframe zoom on the food, recipe steps, clean sound
        run {
            val beat = 60f / 100; val d = beat * 4; val n = 8; val total = d * n
            val slots = List(n) { k -> slot(d, listOf("zoomin", "panr", "zoomin", "sway")[k % 4], if (k == 0) "none" else listOf("leak", "slide", "circle", "whip")[k % 4], "warm", if (k == 0) 0.4f else 0f, if (k == n - 1) 1f else 0f)
                .put("dn", true).put("kf", if (k % 3 == 2) "0,1.0000,0.0000,0.0000;2000000,1.5000,0.0000,0.0500" else "") }
            val L = ArrayList<JSONObject>()
            L.add(tx(t("TODAY'S RECIPE", "RESEPI HARI INI"), 0f, d * 2, "boxword", "gold", "pop", 0.2f, "anton", 1.3f))
            for (k in 1 until n - 1) L.add(tx(t("Step $k", "Langkah $k"), k * d + 0.1f, (k + 1) * d - 0.05f, "darkbox", "white", "slide", 0.82f, "poppins", 1f))
            L.add(tx(t("Enjoy!", "Selamat menjamu selera!"), (n - 1) * d, total, "glow", "yellow", "type", 0.45f, "lobster", 1.2f))
            L.add(tx("steam", 0f, total, kind = "deco", anim = "none", scale = 0.9f)); L.add(tx("😋", (n - 1) * d, total, kind = "sticker", y = 0.3f, anim = "pop", scale = 1.3f))
            out.add(Item("b_supercook", t("Super cooking", "Super masakan"), tpl(t("Super cooking", "Super masakan"), slots, L, listOf(fx("ding", (n - 1) * d))).put("cat", "super").put("music", "dapur"), "built"))
        }
        return out
    }
    /* "story" look: one big title stays on the whole video, quick cuts on the beat, light leaks, tilts and strips */
    private fun stories(): List<Item> {
        data class Sy(val id: String, val cat: String, val en: String, val ms: String, val big: String, val small: String, val song: String, val n: Int, val beats: Int)
        val L = listOf(Sy("wkstory", "life", "Weekend story", "Cerita hujung minggu", "WEEKEND", "Story", "santai", 16, 2),
            Sy("foodstory", "food", "Food diary", "Diari makan", "FOOD", "Diary", "santai", 14, 2),
            Sy("tripstory", "life", "Travel diary", "Diari kembara", "TRAVEL", "Diary", "ceria", 16, 4),
            Sy("shopstory", "sale", "Our shop", "Kedai kami", "OUR SHOP", "Story", "dapur", 12, 4))
        val trs = listOf("leak", "tilt", "bands", "flash", "reveal", "slide", "leak", "white", "tilt", "zoom")
        val fxs = listOf("zoomin", "dutch", "panr", "zoomout", "panl", "zoomin")
        return L.map { y ->
            val bpm = MusicLib.ALL.first { it.id == y.song }.bpm; val d = y.beats * 60f / bpm; val total = d * y.n
            val slots = List(y.n) { k -> slot(d, fxs[k % fxs.size], if (k == 0) "none" else trs[k % trs.size], "cine", if (k == 0) 0.4f else 0f, if (k == y.n - 1) 0.9f else 0f) }
            val lays = listOf(tx(y.big, 0f, total, "shadow", "white", "fade", 0.42f, "playfair", 1.6f), tx(y.small, 0.15f, total, "plain", "yellow", "fade", 0.47f, "dancing", 1.3f))
            Item("b_" + y.id, t(y.en, y.ms), tpl(t(y.en, y.ms), slots, lays).put("cat", y.cat).put("music", y.song), "built")
        }
    }
    val CATS = listOf("all", "super", "food", "sale", "greet", "life", "music")
    fun catName(k: String) = when (k) { "all" -> t("All", "Semua"); "super" -> "Super ✨"; "food" -> t("Food", "Makanan"); "sale" -> t("Sales", "Jualan"); "greet" -> t("Wishes", "Ucapan"); "life" -> t("Life", "Gaya hidup"); else -> t("Music", "Muzik") }

    /* ---- saved on the phone ---- */
    private fun dir(c: Context) = File(c.filesDir, "templates").apply { mkdirs() }
    fun mine(c: Context): List<Item> = dir(c).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { f -> try { val j = JSONObject(f.readText()); Item("m_" + f.nameWithoutExtension, j.optString("name"), j, "mine") } catch (e: Exception) { null } } ?: emptyList()
    fun saveMine(c: Context, j: JSONObject) { File(dir(c), "t_" + System.currentTimeMillis() + ".json").writeText(j.toString()) }
    fun deleteMine(c: Context, id: String) { File(dir(c), id.removePrefix("m_") + ".json").delete(); CloudSync.dropTemplate(c, id.removePrefix("m_") + ".json") }
    /* ---- on the server (published by the super admin) ---- */
    fun server(): List<Item> = try { Fs.list("templates", "at", limit = 200).mapNotNull { d -> try { Item("s_" + d.id, d.s("name"), JSONObject(d.s("json")), "server") } catch (e: Exception) { null } } } catch (e: Exception) { emptyList() }
    fun publish(j: JSONObject): Boolean = Fs.write("templates/" + Fs.newId(), mapOf("name" to j.optString("name"), "json" to j.toString()), now = listOf("at"), merge = false)
    fun unpublish(id: String): Boolean = Fs.delete("templates/" + id.removePrefix("s_"))

    /* the edit in the editor → a template (videos/photos become empty slots; text, stickers, brand and sound effects are kept) */
    fun fromProject(name: String, W: Int, H: Int, clips: List<Clip>, layers: List<Layer>, sounds: List<Sound>): JSONObject {
        val slots = clips.map { c -> slot(c.dur / 1e6f, c.fx, c.trans, c.look, c.fi, c.fo).put("speed", c.speed.toDouble()).put("bri", c.bri.toDouble()).put("con", c.con.toDouble()).put("sat", c.sat.toDouble())
            .put("blur", c.blur).put("kf", c.kf).put("dn", c.dn) }
        val ls = layers.filter { it.kind in listOf("text", "sticker", "brand", "deco") }.map { l -> JSONObject().put("kind", l.kind).put("text", l.text).put("s", l.s).put("e", l.e).put("style", l.style).put("col", l.col).put("anim", l.anim)
            .put("x", l.x.toDouble()).put("y", l.y.toDouble()).put("font", l.font).put("scale", l.scale.toDouble()).put("kf", l.kf) }
        val ss = sounds.filter { it.kind == "sfx" }.map { s -> JSONObject().put("kind", "sfx").put("sfx", s.sfx).put("s", s.s).put("e", s.e).put("vol", s.vol.toDouble()) }
        return tpl(name, slots, ls, ss, W, H)
    }
    fun layers(j: JSONObject): List<Layer> { val a = j.optJSONArray("layers") ?: return emptyList(); return (0 until a.length()).map { i -> val o = a.getJSONObject(i)
        Layer(o.optString("kind", "text"), o.optLong("s"), o.optLong("e")).also { l -> l.text = o.optString("text"); l.style = o.optString("style", "boxword"); l.col = o.optString("col", "white"); l.anim = o.optString("anim", "pop")
            l.x = o.optDouble("x", 0.5).toFloat(); l.y = o.optDouble("y", 0.78).toFloat(); l.font = o.optString("font", "bricolage"); l.scale = o.optDouble("scale", 1.0).toFloat(); l.kf = o.optString("kf") } } }
    fun sounds(j: JSONObject): List<Sound> { val a = j.optJSONArray("sounds") ?: return emptyList(); return (0 until a.length()).map { i -> val o = a.getJSONObject(i)
        Sound("sfx", o.optLong("s"), o.optLong("e")).also { s -> s.sfx = o.optString("sfx", "ding"); s.vol = o.optDouble("vol", 1.0).toFloat() } } }
}
