package com.promulticam.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.promulticam.app.Ui.t
import java.io.File

/* Preview inside the app (like the web ▶ Preview), with Save to Phone right there */
class PlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val f = File(intent.getStringExtra("path") ?: run { finish(); return })
        val d = Ui.px(this, 12)
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Color.BLACK); root.setPadding(d, d * 2, d, d * 2)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.iconBtn(this, R.drawable.ic_back) { finish() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 46)))
        top.addView(Ui.label(this, f.name, 14f, Ui.TEXT, true).apply { setPadding(d, 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        val box = FrameLayout(this)
        val vv = VideoView(this); val mc = MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc)
        vv.setVideoPath(f.absolutePath); vv.setOnPreparedListener { it.isLooping = false; vv.start() }
        box.addView(vv, Ui.fl(-1, -1, Gravity.CENTER).apply { width = -2; height = -2 })
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d })
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        row.addView(Ui.btn(this, t("Save to Phone", "Simpan ke Telefon"), true) {
            Thread { val ok = Store.saveToPhone(this, f); runOnUiThread { Toast.makeText(this, if (ok) t("Saved to gallery: Movies/ProMultiCam", "Disimpan ke galeri: Movies/ProMultiCam") else t("Could not save", "Gagal simpan"), Toast.LENGTH_SHORT).show() } }.start()
        }, LinearLayout.LayoutParams(0, Ui.px(this, 54), 1f))
        row.addView(Ui.btn(this, t("Share", "Kongsi")) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Store.uri(this, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("Share", "Kongsi")))
        }, LinearLayout.LayoutParams(0, Ui.px(this, 54), 1f).apply { leftMargin = d })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d })
        setContentView(root)
    }
}
