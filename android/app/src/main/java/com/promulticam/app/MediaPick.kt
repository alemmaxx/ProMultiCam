package com.promulticam.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.Executors

/* our own gallery: recent photos and videos in a grid, albums at the top (Recents, Camera, Videos, Screenshots, Downloads…) */
class PickMedia : ActivityResultContract<PickMedia.Req, List<Uri>>() {
    class Req(val multi: Boolean, val kind: String = "all")   // kind: all / video / image
    override fun createIntent(context: Context, input: Req) = Intent(context, MediaPickActivity::class.java).putExtra("multi", input.multi).putExtra("kind", input.kind)
    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        if (resultCode != Activity.RESULT_OK || intent == null) emptyList() else (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra("uris", Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra("uris")) ?: emptyList()
}

class MediaPickActivity : AppCompatActivity() {
    private class M(val id: Long, val video: Boolean, val bucket: String, val date: Long, val dur: Long) { fun uri(): Uri = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) }
    private class Album(val name: String, val items: List<M>)
    private val main = Handler(Looper.getMainLooper()); private val pool = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: Long, v: Bitmap) = v.byteCount }
    private var multi = false; private var kind = "all"
    private var all = listOf<M>(); private var albums = listOf<Album>(); private var cur = listOf<M>()
    private val chosen = ArrayList<M>()
    private lateinit var grid: GridView; private lateinit var title: TextView; private lateinit var done: android.widget.Button; private lateinit var empty: TextView
    private val adapter = object : BaseAdapter() {
        override fun getCount() = cur.size; override fun getItem(p: Int) = cur[p]; override fun getItemId(p: Int) = cur[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View = cell(cur[p], cv as? FrameLayout)
    }

    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { load() }
    private val system = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { l -> finishWith(l) }
    private val systemOne = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> finishWith(listOfNotNull(u)) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Ui.init(this)
        multi = intent.getBooleanExtra("multi", false); kind = intent.getStringExtra("kind") ?: "all"
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(0xFF0E0D0B.toInt())
        val d = Ui.px(this, 8)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(d, d * 3, d, d)
        top.addView(Ui.btn(this, "✕") { finishWith(emptyList()) }, LinearLayout.LayoutParams(Ui.px(this, 52), Ui.px(this, 52)))
        title = Ui.label(this, "", 20f, Ui.TEXT, true); title.gravity = Gravity.CENTER; title.setPadding(d, 0, d, 0)
        title.setOnClickListener { albumSheet() }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        done = Ui.btn(this, "", true) { finishWith(chosen.map { it.uri() }) }; done.textSize = 14f; done.visibility = View.INVISIBLE
        top.addView(done, LinearLayout.LayoutParams(-2, Ui.px(this, 48)))
        root.addView(top)
        grid = GridView(this); grid.numColumns = 4; grid.horizontalSpacing = Ui.px(this, 2); grid.verticalSpacing = Ui.px(this, 2); grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH; grid.adapter = adapter
        grid.setOnItemClickListener { _, _, p, _ -> pick(cur[p]) }
        val fr = FrameLayout(this); fr.addView(grid, FrameLayout.LayoutParams(-1, -1))
        empty = Ui.center(Ui.label(this, Ui.t("Loading…", "Memuatkan…"), 15f, Ui.SUB)); fr.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(fr, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        paintTitle(Ui.t("Recents", "Terkini"))
        val need = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) + (if (Build.VERSION.SDK_INT >= 34) arrayOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") else emptyArray())
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (granted()) load() else perm.launch(need)
    }
    private fun granted(): Boolean {
        fun ok(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) (ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_VIDEO) || (Build.VERSION.SDK_INT >= 34 && ok("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")))
            else ok(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private fun finishWith(l: List<Uri>) { setResult(if (l.isEmpty()) RESULT_CANCELED else RESULT_OK, Intent().putParcelableArrayListExtra("uris", ArrayList(l))); finish() }
    private fun paintTitle(n: String) { title.text = "$n ▾" }

    /* read the phone's photos and videos (newest first) */
    private fun load() {
        if (!granted()) {   // no permission: use the phone's own picker instead
            val type = when (kind) { "video" -> ActivityResultContracts.PickVisualMedia.VideoOnly; "image" -> ActivityResultContracts.PickVisualMedia.ImageOnly; else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo }
            val r = androidx.activity.result.PickVisualMediaRequest.Builder().setMediaType(type).build()
            if (multi) system.launch(r) else systemOne.launch(r); return }
        Thread {
            val out = ArrayList<M>()
            fun q(video: Boolean) {
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val cols = if (video) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Video.VideoColumns.DURATION)
                    else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED)
                try { contentResolver.query(base, cols, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
                    while (c.moveToNext()) out.add(M(c.getLong(0), video, c.getString(1) ?: "", c.getLong(2), if (video) c.getLong(3) else 0L)) } } catch (e: Exception) {}
            }
            if (kind != "image") q(true); if (kind != "video") q(false)
            out.sortByDescending { it.date }
            val al = ArrayList<Album>(); al.add(Album(Ui.t("Recents", "Terkini"), out))
            if (kind == "all") { val v = out.filter { it.video }; if (v.isNotEmpty()) al.add(Album(Ui.t("Videos", "Video"), v)) }
            out.groupBy { it.bucket }.filter { it.key.isNotEmpty() }.entries.sortedWith(compareByDescending<Map.Entry<String, List<M>>> { order(it.key) }.thenByDescending { it.value.size })
                .forEach { (k, v) -> al.add(Album(k, v)) }
            main.post { all = out; albums = al; cur = out; adapter.notifyDataSetChanged()
                empty.visibility = if (out.isEmpty()) View.VISIBLE else View.GONE; empty.text = Ui.t("No photos or videos", "Tiada gambar atau video") }
        }.start()
    }
    private fun order(b: String) = when (b.lowercase(Locale.ROOT)) { "camera" -> 5; "screenshots" -> 4; "download", "downloads" -> 3; else -> if (b.contains("whatsapp", true)) 2 else 0 }

    private fun pick(m: M) {
        if (!multi) { finishWith(listOf(m.uri())); return }
        if (chosen.contains(m)) chosen.remove(m) else chosen.add(m)
        done.visibility = if (chosen.isEmpty()) View.INVISIBLE else View.VISIBLE; done.text = Ui.t("Done", "Selesai") + " (" + chosen.size + ")"
        adapter.notifyDataSetChanged()
    }

    private fun cell(m: M, old: FrameLayout?): FrameLayout {
        val w = (resources.displayMetrics.widthPixels - Ui.px(this, 6)) / 4
        val f = old ?: FrameLayout(this).also { f ->
            f.layoutParams = android.widget.AbsListView.LayoutParams(-1, w)
            val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.setBackgroundColor(0xFF22201C.toInt()); f.addView(iv, FrameLayout.LayoutParams(-1, -1))
            val du = Ui.label(this, "", 12f, Color.WHITE, true); du.setShadowLayer(4f, 0f, 1f, Color.BLACK); du.setPadding(0, 0, Ui.px(this, 6), Ui.px(this, 4))
            f.addView(du, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            val sel = Ui.center(Ui.label(this, "", 13f, Color.BLACK, true)); f.addView(sel, FrameLayout.LayoutParams(Ui.px(this, 26), Ui.px(this, 26), Gravity.TOP or Gravity.END).apply { topMargin = Ui.px(this@MediaPickActivity, 6); rightMargin = Ui.px(this@MediaPickActivity, 6) })
        }
        val iv = f.getChildAt(0) as ImageView; val du = f.getChildAt(1) as TextView; val sel = f.getChildAt(2) as TextView
        du.text = if (m.video) { val s = m.dur / 1000; String.format(Locale.US, "%d:%02d", s / 60, s % 60) } else ""
        val k = chosen.indexOf(m)
        if (multi) { sel.visibility = View.VISIBLE
            sel.background = if (k >= 0) Ui.round(Ui.GOLD, Ui.dp(this, 99f), Color.WHITE, Ui.px(this, 2)) else Ui.round(0x33000000, Ui.dp(this, 99f), Color.WHITE, Ui.px(this, 2))
            sel.text = if (k >= 0) (k + 1).toString() else ""; iv.alpha = if (k >= 0) 0.75f else 1f } else sel.visibility = View.GONE
        thumb(m, iv)
        return f
    }
    private fun thumb(m: M, iv: ImageView) {
        iv.tag = m.id; val c = cache.get(m.id); if (c != null) { iv.setImageBitmap(c); return }; iv.setImageDrawable(null)
        pool.execute { if (iv.tag != m.id) return@execute
            val b = try { if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(m.uri(), Size(256, 256), null)
                else if (m.video) @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(contentResolver, m.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(contentResolver, m.id, MediaStore.Images.Thumbnails.MINI_KIND, null) } catch (e: Exception) { null }
            if (b != null) { cache.put(m.id, b); main.post { if (iv.tag == m.id) iv.setImageBitmap(b) } } }
    }

    /* the album list (tap the title) */
    private fun albumSheet() {
        if (albums.isEmpty()) return
        val s = Ui.sheet(this, Ui.t("Albums", "Album"))
        albums.forEach { a -> val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(0, Ui.px(this, 6), 0, Ui.px(this, 6))
            val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.background = Ui.round(0xFF22201C.toInt(), Ui.dp(this, 10f)); iv.clipToOutline = true
            r.addView(iv, LinearLayout.LayoutParams(Ui.px(this, 60), Ui.px(this, 60))); a.items.firstOrNull()?.let { thumb(it, iv) }
            val tx = LinearLayout(this); tx.orientation = LinearLayout.VERTICAL; tx.setPadding(Ui.px(this, 14), 0, 0, 0)
            tx.addView(Ui.label(this, a.name, 17f, Ui.TEXT, true)); tx.addView(Ui.label(this, String.format(Locale.US, "%,d", a.items.size) + " " + Ui.t("items", "item"), 13f, Ui.SUB))
            r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            if (a.items === cur) r.addView(Ui.label(this, "✓", 20f, Ui.GOLD, true))
            r.setOnClickListener { cur = a.items; paintTitle(a.name); adapter.notifyDataSetChanged(); grid.setSelection(0); s.dialog.dismiss() }
            s.body.addView(r) }
        s.dialog.show()
    }
    override fun onDestroy() { super.onDestroy(); pool.shutdownNow() }
}
