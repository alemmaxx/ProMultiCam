package com.promulticam.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* Unfinished edits, kept on the phone until exported or thrown away */
object Drafts {
    fun dir(c: Context) = File(c.filesDir, "drafts").apply { mkdirs() }
    fun file(c: Context, id: String) = File(dir(c), "$id.json")
    fun exists(c: Context, id: String) = file(c, id).exists()
    fun delete(c: Context, id: String) { file(c, id).delete() }
    fun list(c: Context): List<Pair<String, JSONObject>> = dir(c).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { f -> try { f.nameWithoutExtension to JSONObject(f.readText()) } catch (e: Exception) { null } } ?: emptyList()
    /* every file any draft still needs (so the rest can be cleaned up) */
    fun used(c: Context): Set<String> { val s = HashSet<String>(); list(c).forEach { (_, j) -> collect(j, s) }; return s }
    private fun collect(j: JSONObject, s: MutableSet<String>) {
        j.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { s.add(it.optString("file")); s.add(it.optString("img")) } }
        j.optJSONArray("layers")?.let { a -> for (i in 0 until a.length()) s.add(a.getJSONObject(i).optString("file")) }
        j.optJSONArray("sounds")?.let { a -> for (i in 0 until a.length()) s.add(a.getJSONObject(i).optString("file")) }
    }
    fun save(c: Context, id: String, name: String, orig: String, W: Int, H: Int, ov: Float, clips: List<Clip>, layers: List<Layer>, sounds: List<Sound>) {
        val j = JSONObject().put("name", name).put("orig", orig).put("W", W).put("H", H).put("ov", ov.toDouble()).put("at", System.currentTimeMillis())
        j.put("clips", JSONArray(clips.map { k -> JSONObject().put("file", k.file.absolutePath).put("in", k.inUs).put("out", k.outUs).put("still", k.still).put("speed", k.speed.toDouble()).put("vol", k.vol.toDouble())
            .put("look", k.look).put("bri", k.bri.toDouble()).put("con", k.con.toDouble()).put("sat", k.sat.toDouble()).put("rot", k.rot).put("mirror", k.mirror).put("trans", k.trans)
            .put("fileDur", k.fileDur).put("img", k.img).put("fx", k.fx).put("fi", k.fi.toDouble()).put("fo", k.fo.toDouble()).put("fadeVid", k.fadeVid) }))
        j.put("layers", JSONArray(layers.map { l -> JSONObject().put("kind", l.kind).put("s", l.s).put("e", l.e).put("text", l.text).put("font", l.font).put("col", l.col).put("style", l.style)
            .put("anim", l.anim).put("x", l.x.toDouble()).put("y", l.y.toDouble()).put("scale", l.scale.toDouble()).put("file", l.file) }))
        j.put("sounds", JSONArray(sounds.map { s -> JSONObject().put("kind", s.kind).put("s", s.s).put("e", s.e).put("file", s.file).put("sfx", s.sfx).put("vol", s.vol.toDouble())
            .put("text", s.text).put("fade", s.fade).put("fi", s.fi.toDouble()).put("fo", s.fo.toDouble()) }))
        val f = file(c, id); val tmp = File(f.parentFile, "$id.tmp"); tmp.writeText(j.toString()); tmp.renameTo(f)
    }
    class Loaded(val name: String, val orig: String, val W: Int, val H: Int, val ov: Float, val clips: ArrayList<Clip>, val layers: ArrayList<Layer>, val sounds: ArrayList<Sound>)
    fun load(c: Context, id: String): Loaded? = try {
        val j = JSONObject(file(c, id).readText())
        val clips = ArrayList<Clip>(); val a = j.getJSONArray("clips")
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); val f = File(o.getString("file")); if (!f.exists()) continue
            clips.add(Clip(f, o.getLong("in"), o.getLong("out"), o.optBoolean("still")).also { k -> k.speed = o.optDouble("speed", 1.0).toFloat(); k.vol = o.optDouble("vol", 1.0).toFloat(); k.look = o.optString("look", "std")
                k.bri = o.optDouble("bri", 0.0).toFloat(); k.con = o.optDouble("con", 0.0).toFloat(); k.sat = o.optDouble("sat", 0.0).toFloat(); k.rot = o.optInt("rot"); k.mirror = o.optBoolean("mirror"); k.trans = o.optString("trans", "none")
                k.fileDur = o.optLong("fileDur", Long.MAX_VALUE); k.img = o.optString("img"); k.fx = o.optString("fx", "none"); k.fi = o.optDouble("fi", 0.0).toFloat(); k.fo = o.optDouble("fo", 0.0).toFloat(); k.fadeVid = o.optBoolean("fadeVid", true) }) }
        val layers = ArrayList<Layer>(); val b = j.optJSONArray("layers") ?: JSONArray()
        for (i in 0 until b.length()) { val o = b.getJSONObject(i); layers.add(Layer(o.getString("kind"), o.getLong("s"), o.getLong("e")).also { l -> l.text = o.optString("text"); l.font = o.optString("font", "bricolage"); l.col = o.optString("col", "white")
            l.style = o.optString("style", "boxword"); l.anim = o.optString("anim", "none"); l.x = o.optDouble("x", 0.5).toFloat(); l.y = o.optDouble("y", 0.78).toFloat(); l.scale = o.optDouble("scale", 1.0).toFloat(); l.file = o.optString("file") }) }
        val sounds = ArrayList<Sound>(); val d = j.optJSONArray("sounds") ?: JSONArray()
        for (i in 0 until d.length()) { val o = d.getJSONObject(i); val s = Sound(o.getString("kind"), o.getLong("s"), o.getLong("e")).also { s -> s.file = o.optString("file"); s.sfx = o.optString("sfx", "ding"); s.vol = o.optDouble("vol", 1.0).toFloat()
            s.text = o.optString("text"); s.fade = o.optBoolean("fade", true); s.fi = o.optDouble("fi", 0.5).toFloat(); s.fo = o.optDouble("fo", 0.5).toFloat() }
            if (s.kind == "sfx" || File(s.file).exists()) sounds.add(s) }
        if (clips.isEmpty()) null else Loaded(j.optString("name"), j.optString("orig"), j.optInt("W", 1080), j.optInt("H", 1920), j.optDouble("ov", 1.0).toFloat(), clips, layers, sounds)
    } catch (e: Exception) { null }
}
