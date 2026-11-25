package com.promulticam.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.promulticam.app.Ui.t

/* Home: choose a role. Stage 1 of the native app: the Director records with one camera. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 22f).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad * 2, pad, pad)
        col.setBackgroundColor(Ui.BG)

        val logo = ImageView(this)
        logo.setImageResource(R.mipmap.ic_launcher)
        val ls = Ui.dp(this, 84f).toInt()
        col.addView(logo, LinearLayout.LayoutParams(ls, ls).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(Ui.center(Ui.label(this, "ProMultiCam", 30f, Ui.GOLD, true)), lp(8))
        col.addView(Ui.center(Ui.label(this, t("Native app · test version", "App native · versi ujian"), 14f, Ui.SUB)), lp(2))

        col.addView(Ui.btn(this, t("🎬  Director", "🎬  Pengarah"), true) {
            startActivity(Intent(this, DirectorActivity::class.java))
        }, lp(36))
        col.addView(Ui.label(this, t("Gathers the cameras, switches angles and records.", "Kumpul kamera, tukar sudut dan rakam."), 13f, Ui.SUB), lp(4))
        col.addView(Ui.btn(this, t("📷  Camera", "📷  Kamera")) {
            startActivity(Intent(this, CameraActivity::class.java))
        }, lp(14))
        col.addView(Ui.label(this, t("Joins a Director (this app or the web) with the Studio ID.", "Sertai Pengarah (app ini atau web) dengan ID Studio."), 13f, Ui.SUB), lp(4))
        col.addView(Ui.btn(this, t("🎥  Quick record (1 camera)", "🎥  Rakam pantas (1 kamera)")) {
            startActivity(Intent(this, RecordActivity::class.java))
        }, lp(14))
        col.addView(Ui.btn(this, t("🌐  Open the web version", "🌐  Buka versi web")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://promulticam.web.app")))
        }, lp(12))
        col.addView(Ui.label(this, t(
            "Test version. Videos are saved to the gallery: Movies/ProMultiCam.",
            "Versi ujian. Video disimpan ke galeri: Movies/ProMultiCam."), 13f, Ui.SUB), lp(24))

        val sv = ScrollView(this); sv.setBackgroundColor(Ui.BG); sv.addView(col)
        setContentView(sv)
    }

    private fun lp(topDp: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = Ui.dp(this@MainActivity, topDp.toFloat()).toInt() }
}
