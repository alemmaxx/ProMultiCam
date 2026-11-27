package com.promulticam.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/* Small Firestore (REST) helper for the admin page and notifications. Call off the main thread. */
object Fs {
    private const val BASE = "projects/kukusanmy/databases/promulticam/documents"
    private const val URL = "https://firestore.googleapis.com/v1/$BASE"
    private val http = OkHttpClient(); private val js = "application/json".toMediaType()
    class Doc(val id: String, val f: Map<String, Any?>) {
        fun s(k: String) = f[k] as? String ?: ""; fun n(k: String) = (f[k] as? Number)?.toDouble() ?: 0.0
        fun b(k: String) = f[k] as? Boolean ?: false; fun t(k: String) = (f[k] as? Long) ?: 0L
    }
    fun value(v: JSONObject?): Any? = when {
        v == null -> null
        v.has("stringValue") -> v.getString("stringValue")
        v.has("integerValue") -> v.getString("integerValue").toLongOrNull()?.toDouble()
        v.has("doubleValue") -> v.getDouble("doubleValue")
        v.has("booleanValue") -> v.getBoolean("booleanValue")
        v.has("timestampValue") -> try { Instant.parse(v.getString("timestampValue")).toEpochMilli() } catch (e: Exception) { 0L }
        v.has("mapValue") -> fields(v.getJSONObject("mapValue").optJSONObject("fields"))
        else -> null
    }
    private fun fields(o: JSONObject?): Map<String, Any?> { val m = HashMap<String, Any?>(); o?.keys()?.forEach { k -> m[k] = value(o.getJSONObject(k)) }; return m }
    private fun doc(o: JSONObject) = Doc(o.getString("name").substringAfterLast('/'), fields(o.optJSONObject("fields")))
    fun enc(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is String -> JSONObject().put("stringValue", v)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int, is Long -> JSONObject().put("integerValue", v.toString())
        is Number -> JSONObject().put("doubleValue", v.toDouble())
        else -> JSONObject().put("stringValue", v.toString())
    }
    private fun req(path: String) = Request.Builder().url(path).also { b -> Auth.tokenSync()?.let { b.header("Authorization", "Bearer $it") } }
    fun get(path: String): Doc? = http.newCall(req("$URL/$path").build()).execute().use { r -> if (!r.isSuccessful) null else doc(JSONObject(r.body?.string() ?: "{}")) }
    /* a collection (or sub-collection under parent), optionally filtered by one field */
    fun list(col: String, order: String? = null, desc: Boolean = true, limit: Int = 500, parent: String = "", whereField: String? = null, whereVal: Any? = null): List<Doc> {
        val q = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", col)))
        if (order != null) q.put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", order)).put("direction", if (desc) "DESCENDING" else "ASCENDING")))
        if (whereField != null) q.put("where", JSONObject().put("fieldFilter", JSONObject().put("field", JSONObject().put("fieldPath", whereField)).put("op", "EQUAL").put("value", enc(whereVal))))
        q.put("limit", limit)
        val url = if (parent.isEmpty()) "$URL:runQuery" else "$URL/$parent:runQuery"
        return http.newCall(req(url).post(JSONObject().put("structuredQuery", q).toString().toRequestBody(js)).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP " + r.code)
            val a = JSONArray(r.body?.string() ?: "[]"); (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("document")?.let { d -> doc(d) } } }
    }
    /* write fields (merge), with some fields set to the server time; create = must not exist yet */
    fun write(path: String, data: Map<String, Any?>, now: List<String> = emptyList(), merge: Boolean = true, create: Boolean = false): Boolean =
        commit(listOf(JSONObject().also { w ->
            val fl = JSONObject(); data.forEach { (k, v) -> fl.put(k, enc(v)) }
            w.put("update", JSONObject().put("name", "$BASE/$path").put("fields", fl))
            if (merge) w.put("updateMask", JSONObject().put("fieldPaths", JSONArray(data.keys.toList())))
            if (now.isNotEmpty()) w.put("updateTransforms", JSONArray(now.map { JSONObject().put("fieldPath", it).put("setToServerValue", "REQUEST_TIME") }))
            if (create) w.put("currentDocument", JSONObject().put("exists", false))
        }))
    fun delete(path: String): Boolean = commit(listOf(JSONObject().put("delete", "$BASE/$path")))
    fun commit(writes: List<JSONObject>): Boolean =
        http.newCall(req("$URL:commit").post(JSONObject().put("writes", JSONArray(writes)).toString().toRequestBody(js)).build()).execute().use { it.isSuccessful }
    fun docName(p: String) = "$BASE/$p"
    fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
}
