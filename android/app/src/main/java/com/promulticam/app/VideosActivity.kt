package com.promulticam.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.promulticam.app.Ui.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* The videos this app made (gallery folder Movies/ProMultiCam): watch, share, delete, join */
class VideosActivity : AppCompatActivity() {
    private class V(val uri: Uri, val name: String, val mb: Float, val sec: Long)
    private lateinit var list: LinearLayout
    private val picked = LinkedHashSet<Uri>()
    private val main = Handler(Looper.getMainLooper())

    private val importer = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { list -> if (list.isNotEmpty()) importVideos(list) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val d = Ui.px(this, 12)
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Ui.BG); root.setPadding(d, d * 2, d, d)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.iconBtn(this, R.drawable.ic_back) { finish() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 46)))
        top.addView(Ui.label(this, t("Saved Recordings", "Rakaman Tersimpan"), 22f, Ui.TEXT, true).apply { setPadding(d, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        val r1 = LinearLayout(this); r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(Ui.btn(this, t("Import Video", "Import Video"), true) { importer.launch("video/*") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = d / 2 })
        r1.addView(Ui.btn(this, t("Join Video", "Join Video")) { joinPicked() }.apply { setTextColor(Ui.GOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(r1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d })
        root.addView(Ui.btn(this, t("Video & Photos", "Video & Gambar")) { WebActivity.open(this, "media") }.apply { setTextColor(Ui.GOLD) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d / 2 })
        root.addView(Ui.hint(this, t("Tick 2 or more, then Join Video.", "Tanda 2 atau lebih, kemudian Join Video.")))
        val sv = ScrollView(this); list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL; sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d })
        setContentView(root)
    }

    /* copy chosen gallery videos into the app's folder so they can be joined / continued */
    private fun importVideos(list: List<Uri>) {
        toast(t("Importing…", "Mengimport…"))
        Thread {
            var n = 0
            for (u in list) try {
                val name = "Import_" + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + "_" + n + ".mp4"
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProMultiCam"); put(MediaStore.Video.Media.IS_PENDING, 1) } }
                val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: continue
                contentResolver.openInputStream(u)?.use { i -> contentResolver.openOutputStream(out)?.use { o -> i.copyTo(o) } }
                if (Build.VERSION.SDK_INT >= 29) contentResolver.update(out, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                n++
            } catch (e: Exception) {}
            main.post { toast(t("$n video(s) imported", "$n video diimport")); load() }
        }.start()
    }

    override fun onResume() { super.onResume(); load() }

    private fun query(): List<V> {
        val out = ArrayList<V>()
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION)
        val (sel, args) = if (Build.VERSION.SDK_INT >= 29) Pair("${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/ProMultiCam%"))
            else Pair("${MediaStore.Video.Media.DATA} LIKE ?", arrayOf("%/Movies/ProMultiCam/%"))
        contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(V(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), c.getString(1) ?: "", c.getLong(2) / 1048576f, c.getLong(3) / 1000))
            }
        }
        return out
    }

    private fun load() {
        list.removeAllViews(); picked.clear()
        val vs = try { query() } catch (e: Exception) { emptyList() }
        val d = Ui.dp(this, 10f).toInt()
        if (vs.isEmpty()) list.addView(Ui.label(this, t("No videos yet.", "Belum ada video."), 15f, Ui.SUB))
        for (v in vs) {
            val card = Ui.card(this)
            val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL; head.gravity = Gravity.CENTER_VERTICAL
            val cb = CheckBox(this); cb.buttonTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); cb.setOnCheckedChangeListener { _, on -> if (on) picked.add(v.uri) else picked.remove(v.uri) }
            head.addView(cb)
            head.addView(ImageView(this).apply {
                try { setImageBitmap(if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(v.uri, android.util.Size(160, 160), null) else null) } catch (e: Exception) {}
                scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(Color.BLACK, Ui.dp(this@VideosActivity, 8f))
            }, LinearLayout.LayoutParams(Ui.dp(this, 54f).toInt(), Ui.dp(this, 54f).toInt()).apply { rightMargin = d })
            val txt = LinearLayout(this); txt.orientation = LinearLayout.VERTICAL
            txt.addView(Ui.label(this, v.name, 14f, Ui.TEXT, true))
            txt.addView(Ui.label(this, String.format(Locale.US, "%.1f MB · %d:%02d", v.mb, v.sec / 60, v.sec % 60), 12f, Ui.SUB))
            head.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(head)
            val g = android.widget.GridLayout(this); g.columnCount = 3
            val items = ArrayList<android.view.View>()
            items.add(Ui.btn(this, "▶ " + t("Preview", "Tonton")) { play(v.uri) })
            items.add(Ui.btn(this, t("Share", "Kongsi"), true) { share(v.uri) })
            if (Build.VERSION.SDK_INT >= 29) items.add(Ui.btn(this, "⏺ " + t("Continue", "Sambung")) { startActivity(Intent(this, DirectorActivity::class.java).putExtra("resume", v.uri.toString())) })
            items.add(Ui.btn(this, "Edit") { WebActivity.open(this, "videos"); toast(t("In the web app: Import Video, then Edit", "Dalam web: Import Video, kemudian Edit")) })
            items.add(Ui.dangerBtn(this, t("Delete", "Padam")) { del(v) })
            items.forEachIndexed { i, x -> val p = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / 3), android.widget.GridLayout.spec(i % 3, 1f)); p.width = 0; p.setMargins(4, 6, 4, 6); g.addView(x, p) }
            card.addView(g, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d / 2 })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d })
        }
    }

    private fun play(u: Uri) { try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { toast(t("No video player", "Tiada pemain video")) } }
    private fun share(u: Uri) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("Share", "Kongsi"))) }
    private fun del(v: V) {
        AlertDialog.Builder(this).setTitle(t("Delete this video?", "Padam video ini?")).setMessage(v.name)
            .setPositiveButton(t("Delete", "Padam")) { _, _ -> try { contentResolver.delete(v.uri, null, null) } catch (e: Exception) { toast(t("Could not delete", "Gagal padam")) }; load() }
            .setNegativeButton(t("Cancel", "Batal"), null).show()
    }

    private fun joinPicked() {
        if (picked.size < 2) { toast(t("Tick 2 or more videos", "Tanda 2 video atau lebih")); return }
        val parts = picked.toList().reversed()   // oldest first
        toast(t("Joining…", "Menggabung…"))
        Thread {
            var ok = false
            try {
                val name = "ProMultiCam_join_" + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".mp4"
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProMultiCam"); put(MediaStore.Video.Media.IS_PENDING, 1) }
                    val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openFileDescriptor(u, "rw")!!.use { ok = Joiner.join(this, parts, it.fileDescriptor) }
                    if (ok) contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) else contentResolver.delete(u, null, null)
                } else {
                    val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "ProMultiCam"); dir.mkdirs()
                    val f = java.io.File(dir, name)
                    android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { ok = Joiner.join(this, parts, it.fileDescriptor) }
                    if (ok) android.media.MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null) else f.delete()
                }
            } catch (e: Exception) { ok = false }
            main.post { toast(if (ok) t("Joined — new video saved", "Siap digabung — video baru disimpan") else t("Could not join (different size or settings)", "Gagal gabung (saiz atau tetapan berbeza)")); main.postDelayed({ load() }, 800) }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
