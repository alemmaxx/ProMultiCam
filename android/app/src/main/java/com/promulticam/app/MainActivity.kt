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

        col.addView(Ui.btn(this, t("🎬  Director — record", "🎬  Pengarah — rakam"), true) {
            startActivity(Intent(this, RecordActivity::class.java))
        }, lp(36))
        val cam = Ui.btn(this, t("📷  Camera (coming soon)", "📷  Kamera (akan datang)")) {}
        cam.isEnabled = false; cam.alpha = 0.5f
        col.addView(cam, lp(12))
        col.addView(Ui.btn(this, t("🌐  Open the web version", "🌐  Buka versi web")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://promulticam.web.app")))
        }, lp(12))
        col.addView(Ui.label(this, t(
            "This test version records one camera with your logo and text, straight from the phone camera. Compare it with the web version for smoothness.",
            "Versi ujian ini rakam satu kamera dengan logo dan teks anda, terus dari kamera telefon. Bandingkan kelancarannya dengan versi web."), 13f, Ui.SUB), lp(24))

        val sv = ScrollView(this); sv.setBackgroundColor(Ui.BG); sv.addView(col)
        setContentView(sv)
    }

    private fun lp(topDp: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = Ui.dp(this@MainActivity, topDp.toFloat()).toInt() }
}
