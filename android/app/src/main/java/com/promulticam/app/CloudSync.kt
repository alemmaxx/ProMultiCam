package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/* keeps a user's settings, logos and own templates in their account, so a new phone gets them after log in (videos stay on each phone) */
object CloudSync {
    // things that belong to this phone only
    private val SKIP = setOf("apkDl", "apkDlCode", "apkDlV", "apkReady", "nAdmin", "nApk", "nReady", "nUser", "lastCode", "id", "uid", "email", "expiry", "plan", "status", "seq", "take", "rt", "devName", "camLabel", "tpl")
    private fun keep(k: String) = k !in SKIP && !k.startsWith("uname_") && !k.startsWith("_")
    private val LOGOS = listOf("logo.png", "logo_orig.png") + (1..6).flatMap { listOf("logo_s$it.png", "logo_orig_s$it.png") }
    private fun state(c: Context) = c.getSharedPreferences("pmc_sync", Context.MODE_PRIVATE)
    @Volatile private var busy = false

    private fun prefsJson(c: Context): JSONObject { val o = JSONObject()
        c.getSharedPreferences("pmc", Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) -> if (keep(k)) o.put(k, JSONObject().put("t", when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }).put("v", v.toString())) }
        return o }
    private fun applyPrefs(c: Context, o: JSONObject) {
        val e = c.getSharedPreferences("pmc", Context.MODE_PRIVATE).edit()
        o.keys().forEach { k -> if (!keep(k)) return@forEach; val x = o.optJSONObject(k) ?: return@forEach; val v = x.optString("v")
            try { when (x.optString("t")) { "b" -> e.putBoolean(k, v.toBoolean()); "i" -> e.putInt(k, v.toInt()); "l" -> e.putLong(k, v.toLong()); "f" -> e.putFloat(k, v.toFloat()); else -> e.putString(k, v) } } catch (ex: Exception) {} }
        e.apply()
    }
    private fun stamp(f: File) = if (f.exists()) "${f.length()}:${f.lastModified()}" else ""
    private fun pack(f: File): String { var b = f.readBytes()
        if (b.size > 600_000) BitmapFactory.decodeByteArray(b, 0, b.size)?.let { bm -> val k = 800f / maxOf(bm.width, bm.height); val s = if (k < 1f) Bitmap.createScaledBitmap(bm, (bm.width * k).toInt(), (bm.height * k).toInt(), true) else bm
            val o = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.PNG, 100, o); b = o.toByteArray() }
        return Base64.encodeToString(b, Base64.NO_WRAP) }

    /* call off the main thread. Returns true when settings came from the account (screens may need a refresh). */
    fun sync(c: Context): Boolean {
        val uid = Auth.uid ?: return false; if (busy) return false; busy = true
        try {
            val st = state(c); if (st.getString("uid", "") != uid) st.edit().clear().putString("uid", uid).apply()
            val remote = Fs.get("userdata/$uid"); val rAt = remote?.t("at") ?: 0L; val rHash = remote?.s("hash") ?: ""
            var pulled = false
            if (remote != null && rAt > st.getLong("pulledAt", 0L) && rHash != st.getString("pushedHash", "")) {
                // the account is newer (e.g. this phone is new): take its settings, logos and templates
                try { applyPrefs(c, JSONObject(remote.s("prefs"))) } catch (e: Exception) {}
                Fs.list("userfiles", whereField = "uid", whereVal = uid).forEach { d -> val n = d.s("name"); if (n !in LOGOS) return@forEach; val f = File(c.filesDir, n)
                    val data = d.s("d"); if (data.isEmpty()) f.delete() else try { f.writeBytes(Base64.decode(data, Base64.DEFAULT)) } catch (e: Exception) {}
                    st.edit().putString("f_$n", stamp(f)).apply() }
                pullTemplates(c, uid)
                st.edit().putLong("pulledAt", rAt).putString("pushedHash", rHash).apply(); pulled = true
            }
            // this phone changed something: send it to the account
            val p = prefsJson(c).toString(); val h = p.hashCode().toString()
            if (h != st.getString("pushedHash", "")) {
                if (Fs.write("userdata/$uid", mapOf("prefs" to p, "hash" to h), now = listOf("at"), merge = false)) {
                    val at = Fs.get("userdata/$uid")?.t("at") ?: System.currentTimeMillis(); st.edit().putString("pushedHash", h).putLong("pulledAt", at).apply() } }
            LOGOS.forEach { n -> val f = File(c.filesDir, n); val s = stamp(f)
                if (s != st.getString("f_$n", "")) { val ok = Fs.write("userfiles/${uid}_$n", mapOf("uid" to uid, "name" to n, "d" to if (f.exists()) pack(f) else ""), now = listOf("at"), merge = false)
                    if (ok) st.edit().putString("f_$n", s).apply() } }
            pushTemplates(c, uid)
            return pulled
        } catch (e: Exception) { return false } finally { busy = false }
    }
    private fun tplDir(c: Context) = File(c.filesDir, "templates").apply { mkdirs() }
    private fun pullTemplates(c: Context, uid: String) {
        Fs.list("usertpl", whereField = "uid", whereVal = uid).forEach { d -> val f = File(tplDir(c), d.s("file")); if (d.s("file").endsWith(".json") && !f.exists()) try { f.writeText(d.s("json")) } catch (e: Exception) {} }
    }
    private fun pushTemplates(c: Context, uid: String) {
        val st = state(c); val sent = st.getStringSet("tpl", emptySet())!!.toMutableSet()
        tplDir(c).listFiles { f -> f.name.endsWith(".json") }?.forEach { f -> if (f.name !in sent) {
            if (Fs.write("usertpl/${uid}_${f.nameWithoutExtension}", mapOf("uid" to uid, "file" to f.name, "json" to f.readText()), now = listOf("at"), merge = false)) sent.add(f.name) } }
        st.edit().putStringSet("tpl", sent).apply()
    }
    /* a template deleted on this phone is deleted from the account too */
    fun dropTemplate(c: Context, file: String) { val uid = Auth.uid ?: return
        Thread { try { Fs.delete("usertpl/${uid}_${file.removeSuffix(".json")}"); val st = state(c); st.edit().putStringSet("tpl", st.getStringSet("tpl", emptySet())!!.toMutableSet().apply { remove(file) }).apply() } catch (e: Exception) {} }.start() }
}
