package com.promulticam.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.promulticam.app.Ui.t
import java.io.File
import java.util.Locale

/* Saved Recordings, like the web: kept in the app; Preview, Save to Phone, Share, Continue, Edit, Delete; Import, Join */
class VideosActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private val picked = LinkedHashSet<File>()
    private val main = Handler(Looper.getMainLooper())
    private val importer = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { l -> if (l.isNotEmpty()) importVideos(l) }

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
        r1.addView(btn(t("Import Video", "Import Video"), true) { importer.launch("video/*") }, LinearLayout.LayoutParams(0, Ui.px(this, 52), 1f).apply { rightMargin = d / 2 })
        r1.addView(btn(t("Join Video", "Join Video")) { joinPicked() }.apply { setTextColor(Ui.GOLD) }, LinearLayout.LayoutParams(0, Ui.px(this, 52), 1f))
        root.addView(r1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d })
        root.addView(btn(t("Video & Photos", "Video & Gambar")) { Nav.open(this, "media") }.apply { setTextColor(Ui.GOLD) }, LinearLayout.LayoutParams(-1, Ui.px(this, 52)).apply { topMargin = d / 2 })
        root.addView(Ui.hint(this, t("Tick 2 or more, then Join Video.", "Tanda 2 atau lebih, kemudian Join Video.")))
        val sv = ScrollView(this); list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL; sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d })
        setContentView(root)
    }
    override fun onResume() { super.onResume(); load() }

    /* all buttons the same height, one line */
    private fun btn(label: String, gold: Boolean = false, f: () -> Unit): Button { val b = Ui.btn(this, label, gold, f); b.textSize = 14f; b.maxLines = 1; b.setPadding(Ui.px(this, 6), Ui.px(this, 6), Ui.px(this, 6), Ui.px(this, 10)); return b }

    private fun thumb(f: File): Bitmap? = try { MediaMetadataRetriever().run { setDataSource(f.absolutePath); val b = getFrameAtTime(500_000); release(); b } } catch (e: Exception) { null }
    private fun durSec(f: File): Long = try { MediaMetadataRetriever().run { setDataSource(f.absolutePath); val v = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0; release(); v / 1000 } } catch (e: Exception) { 0 }

    private fun load() {
        list.removeAllViews(); picked.clear()
        val vs = Store.list(this)
        val d = Ui.px(this, 10)
        if (vs.isEmpty()) list.addView(Ui.label(this, t("No videos yet.", "Belum ada video."), 15f, Ui.SUB))
        for (f in vs) {
            val card = Ui.card(this)
            val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL; head.gravity = Gravity.CENTER_VERTICAL
            val cb = CheckBox(this); cb.buttonTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); cb.setOnCheckedChangeListener { _, on -> if (on) picked.add(f) else picked.remove(f) }
            head.addView(cb)
            val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.background = Ui.round(Color.BLACK, Ui.dp(this, 8f)); iv.clipToOutline = true
            head.addView(iv, LinearLayout.LayoutParams(Ui.px(this, 54), Ui.px(this, 54)).apply { rightMargin = d })
            Thread { val b = thumb(f); main.post { iv.setImageBitmap(b) } }.start()
            val txt = LinearLayout(this); txt.orientation = LinearLayout.VERTICAL
            txt.addView(Ui.label(this, f.name, 14f, Ui.TEXT, true))
            val info = Ui.label(this, String.format(Locale.US, "%.1f MB", f.length() / 1048576f), 12f, Ui.SUB); txt.addView(info)
            Thread { val s = durSec(f); main.post { info.text = String.format(Locale.US, "%.1f MB · %d:%02d", f.length() / 1048576f, s / 60, s % 60) } }.start()
            head.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(head)
            val items = listOf(
                btn("▶ " + t("Preview", "Tonton")) { startActivity(Intent(this, PlayerActivity::class.java).putExtra("path", f.absolutePath)) },
                btn(t("Save to Phone", "Simpan ke Telefon"), true) { saveToPhone(f) },
                btn(t("Share", "Kongsi")) { share(f) },
                btn(t("Continue", "Sambung")) { startActivity(Intent(this, DirectorActivity::class.java).putExtra("resume", f.absolutePath)) },
                btn("Edit") { Nav.open(this, "edit", f.name) },
                Ui.dangerBtn(this, t("Delete", "Padam")) { del(f) }.apply { textSize = 14f; maxLines = 1 })
            val g = android.widget.GridLayout(this); g.columnCount = 3
            items.forEachIndexed { i, x ->
                val p = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / 3), android.widget.GridLayout.spec(i % 3, 1f))
                p.width = 0; p.height = Ui.px(this, 50); p.setMargins(4, 6, 4, 6); g.addView(x, p)
            }
            card.addView(g, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d / 2 })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d })
        }
    }

    private fun saveToPhone(f: File) { toast(t("Saving…", "Menyimpan…")); Thread { val ok = Store.saveToPhone(this, f); main.post { toast(if (ok) t("Saved to gallery: Movies/ProMultiCam", "Disimpan ke galeri: Movies/ProMultiCam") else t("Could not save", "Gagal simpan")) } }.start() }
    private fun share(f: File) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Store.uri(this, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("Share", "Kongsi"))) }
    private fun del(f: File) {
        AlertDialog.Builder(this).setTitle(t("Delete this video?", "Padam video ini?")).setMessage(f.name + "\n" + t("Copies on your phone stay.", "Salinan di telefon kekal."))
            .setPositiveButton(t("Delete", "Padam")) { _, _ -> f.delete(); load() }.setNegativeButton(t("Cancel", "Batal"), null).show()
    }

    private fun importVideos(l: List<Uri>) {
        toast(t("Importing…", "Mengimport…"))
        Thread {
            var n = 0
            for (u in l) try { val out = Store.newFile(this, "Import"); contentResolver.openInputStream(u)?.use { i -> out.outputStream().use { i.copyTo(it) } }; n++ } catch (e: Exception) {}
            main.post { toast(t("$n video(s) imported", "$n video diimport")); load() }
        }.start()
    }

    private fun joinPicked() {
        if (picked.size < 2) { toast(t("Tick 2 or more videos", "Tanda 2 video atau lebih")); return }
        val parts = picked.toList().sortedBy { it.lastModified() }
        toast(t("Joining…", "Menggabung…"))
        Thread {
            val out = Store.newFile(this, "Join")
            val ok = try { android.os.ParcelFileDescriptor.open(out, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_TRUNCATE).use { Joiner.join(this, parts.map { Uri.fromFile(it) }, it.fileDescriptor) } } catch (e: Exception) { false }
            if (!ok) out.delete()
            main.post { toast(if (ok) t("Joined — new video saved", "Siap digabung — video baru disimpan") else t("Could not join (different size or settings)", "Gagal gabung (saiz atau tetapan berbeza)")); load() }
        }.start()
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    @Suppress("unused") private val sdk = Build.VERSION.SDK_INT
}
