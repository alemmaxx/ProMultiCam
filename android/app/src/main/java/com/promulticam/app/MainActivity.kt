package com.promulticam.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.promulticam.app.Ui.t

/* Home: the same page as the web app — name, Director / Camera, tools, language, log in */
class MainActivity : AppCompatActivity() {
    private val sp by lazy { getSharedPreferences("pmc", Context.MODE_PRIVATE) }
    private lateinit var loginBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this); Auth.init(this)
        builtAdmin = Auth.isAdmin
        build()
        Notifier.schedule(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }
    private var builtAdmin = false
    override fun onResume() { super.onResume(); paintLogin(); Auth.refreshProfile { paintLogin(); if (Auth.isAdmin != builtAdmin) recreate() }; alive = true; checkLoop() }
    override fun onPause() { super.onPause(); alive = false }
    private var adminBadge: android.widget.TextView? = null; private var supBadge: android.widget.TextView? = null; private var alive = false
    private var apkBadge: android.widget.TextView? = null
    private fun paintBadges() {
        apkBadge?.let { it.text = "NEW"; it.visibility = if (Notifier.newApk.isNotEmpty()) View.VISIBLE else View.GONE }
        adminBadge?.let { it.text = if (Notifier.waiting > 9) "9+" else Notifier.waiting.toString(); it.visibility = if (Notifier.waiting > 0) View.VISIBLE else View.GONE }
        supBadge?.let { it.text = "1"; it.visibility = if (Notifier.userUnread && !Auth.isAdmin) View.VISIBLE else View.GONE }
    }
    private fun askExit() {
        androidx.appcompat.app.AlertDialog.Builder(this).setMessage(t("Close ProMultiCam?", "Tutup ProMultiCam?"))
            .setPositiveButton(t("Close", "Tutup")) { _, _ -> finishAffinity() }.setNegativeButton(t("Cancel", "Batal"), null).show()
    }
    @Deprecated("back") override fun onBackPressed() { askExit() }
    /* new support messages: notification + count on the Super Admin button, every 30 s while open */
    private fun checkLoop() {
        if (!alive) return
        Thread { Notifier.checkSync(this); runOnUiThread { paintBadges() } }.start()
        window.decorView.postDelayed({ checkLoop() }, 30_000)
    }

    private fun build() {
        val c = this
        val root = FrameLayout(c); root.setBackgroundColor(Ui.BG)
        val bg = ImageView(c); bg.setImageResource(R.drawable.home_bg); bg.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(bg, Ui.fl(-1, -1))
        val shade = View(c); shade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x9E080705.toInt(), 0x73080705, 0xC7080705.toInt()))
        root.addView(shade, Ui.fl(-1, -1))
        // one screen, no scrolling: the role cards take whatever room is left
        val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.px(c, 18), Ui.px(c, 14), Ui.px(c, 18), Ui.px(c, 14)); root.addView(col, Ui.fl(-1, -1))
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

        // top row: logo on the left; on the right, language + log in side by side, and Super Admin under them (same width, same height)
        val top = LinearLayout(c); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.TOP
        val bh = Ui.px(c, 48)
        val logo = ImageView(c); logo.setImageResource(R.drawable.ic_logo); logo.background = Ui.raised(c, 0xFF1C1B19.toInt(), 0xFF0E0D0B.toInt(), Ui.dp(c, 12f), Ui.LINE); logo.setPadding(Ui.px(c, 4), Ui.px(c, 2), Ui.px(c, 4), Ui.px(c, 6))
        top.addView(logo, LinearLayout.LayoutParams(bh, bh))
        top.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        val right = LinearLayout(c); right.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL
        val langB = Ui.btn(c, if (Ui.ms) "Bahasa Melayu ▾" else "English ▾") { Ui.setLang(c, if (Ui.ms) "en" else "ms"); recreate() }; langB.textSize = 13f
        // community chat
        val chatB = Ui.btn(c, "💬") { Nav.open(this, "lounge") }; chatB.textSize = 16f; chatB.minWidth = 0; chatB.minimumWidth = 0
        row.addView(chatB, LinearLayout.LayoutParams(bh, bh).apply { rightMargin = Ui.px(c, 8) })
        row.addView(langB, LinearLayout.LayoutParams(-2, bh))
        loginBtn = Ui.btn(c, "") { openAccount() }; loginBtn.textSize = 13f
        row.addView(loginBtn, LinearLayout.LayoutParams(0, bh, 1f).apply { leftMargin = Ui.px(c, 8) })
        right.addView(row)
        if (Auth.isAdmin) {
            val fr = FrameLayout(c); fr.clipChildren = false
            val sb = Ui.btn(c, "👑 Super Admin") { startActivity(Intent(c, AdminActivity::class.java)) }; sb.textSize = 13f; sb.setTextColor(Ui.GOLD)
            fr.addView(sb, Ui.fl(-1, bh))
            val bd = Ui.center(Ui.label(c, "", 11f, Color.WHITE, true)); bd.background = Ui.round(0xFFE5483B.toInt(), Ui.dp(c, 99f), Color.WHITE, Ui.px(c, 2))
            bd.setPadding(Ui.px(c, 5), 0, Ui.px(c, 5), 0); bd.minWidth = Ui.px(c, 22); bd.visibility = View.GONE
            fr.addView(bd, Ui.fl(-2, Ui.px(c, 22), Gravity.TOP or Gravity.END).apply { topMargin = -Ui.px(c, 6); rightMargin = -Ui.px(c, 4) }); adminBadge = bd
            right.addView(fr, LinearLayout.LayoutParams(-1, bh).apply { topMargin = Ui.px(c, 10) })
        }
        top.addView(right, LinearLayout.LayoutParams(-2, -2))
        top.clipChildren = false; right.clipChildren = false
        col.addView(top)

        col.addView(Ui.label(c, t("MULTI-CAM STUDIO", "STUDIO MULTI-KAMERA"), 12f, Ui.GOLD, true), Ui.lp(c, 10))
        val title = LinearLayout(c); title.orientation = LinearLayout.HORIZONTAL; title.gravity = Gravity.BOTTOM
        title.addView(Ui.label(c, "ProMultiCam", 34f, Ui.GOLD2, true))
        title.addView(Ui.label(c, "  V1.0", 13f, Ui.SUB, true).apply { setPadding(0, 0, 0, Ui.px(c, 8)) })
        col.addView(title)
        col.addView(Ui.label(c, t("Studio videos with many phones. Switch angles live.", "Video studio dengan banyak telefon. Tukar sudut secara langsung."), 14f, Ui.TEXT))
        col.addView(Ui.label(c, t("Device name", "Nama peranti"), 13f, Ui.SUB, true), Ui.lp(c, 12))
        val name: EditText = Ui.input(c, t("e.g. My Kitchen Studio", "cth: Studio Dapur Saya"), sp.getString("devName", "") ?: "")
        name.background = Ui.round(0xB0100F0C.toInt(), Ui.dp(c, 12f), Ui.LINE, Ui.px(c, 1))
        name.setOnFocusChangeListener { _, f -> if (!f) sp.edit().putString("devName", name.text.toString().trim()).apply() }
        col.addView(name, Ui.lp(c, 6))
        col.addView(Ui.label(c, t("Choose role", "Pilih peranan"), 13f, Ui.SUB, true), Ui.lp(c, 14))

        // the two role cards
        val roles = LinearLayout(c); roles.orientation = LinearLayout.HORIZONTAL; roles.minimumHeight = Ui.px(c, 150)
        roles.addView(role(true) { sp.edit().putString("devName", name.text.toString().trim()).apply(); goDirector() }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = Ui.px(c, 6) })
        roles.addView(role(false) { sp.edit().putString("devName", name.text.toString().trim()).apply(); startActivity(Intent(c, CameraActivity::class.java)) }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = Ui.px(c, 6) })
        col.addView(roles, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.px(c, 6) })

        // tools
        val g = GridLayout(c); g.columnCount = 3
        val tools = listOf(
            Triple(R.drawable.ic_crown, t("Plans", "Pelan")) { Nav.open(this, "pro") },
            Triple(R.drawable.ic_video, t("Videos", "Video")) { startActivity(Intent(c, VideosActivity::class.java)) },
            Triple(R.drawable.ic_pulse, t("Device check", "Semak peranti")) { Nav.open(this, "test") },
            Triple(R.drawable.ic_studio, "Template") { startActivity(Intent(c, EditorActivity::class.java).putExtra("tpl", true)) },
            Triple(R.drawable.ic_apk, "App Update") { Nav.open(this, "apk") },
            Triple(R.drawable.ic_info, t("About", "Tentang")) { Nav.open(this, "about") }) +
            emptyList<Triple<Int, String, () -> Unit>>()
        tools.forEachIndexed { i, (ic, l, f) ->
            val tl = LinearLayout(c); tl.orientation = LinearLayout.VERTICAL; tl.gravity = Gravity.CENTER
            tl.background = if (i == 0) Ui.raised(c, 0xFF262117.toInt(), 0xFF0E0D0B.toInt(), Ui.dp(c, 12f), Ui.GOLD) else Ui.raised(c, 0xB812100D.toInt(), 0xFF0E0D0B.toInt(), Ui.dp(c, 12f), Ui.LINE)
            tl.setPadding(Ui.px(c, 4), Ui.px(c, 8), Ui.px(c, 4), Ui.px(c, 12))
            val iv = ImageView(c); iv.setImageResource(ic); iv.imageTintList = ColorStateList.valueOf(if (i == 0) Ui.GOLD else Ui.SUB)
            tl.addView(iv, LinearLayout.LayoutParams(Ui.px(c, 22), Ui.px(c, 22)))
            val tx = Ui.center(Ui.label(c, l, 11f, if (i == 0) Ui.GOLD else Ui.TEXT, true)); tl.addView(tx, Ui.lp(c, 4))
            tl.setOnClickListener { f() }
            // red badge in the corner (new support messages)
            val cell = FrameLayout(c); cell.addView(tl, Ui.fl(-1, -1))
            if (ic == R.drawable.ic_info || ic == R.drawable.ic_apk || l == "Super Admin") { val bd = Ui.center(Ui.label(c, "", 11f, Color.WHITE, true)); bd.background = Ui.round(0xFFE5483B.toInt(), Ui.dp(c, 99f), Color.WHITE, Ui.px(c, 2))
                bd.setPadding(Ui.px(c, 5), 0, Ui.px(c, 5), 0); bd.minWidth = Ui.px(c, 22); bd.visibility = View.GONE
                cell.addView(bd, Ui.fl(-2, Ui.px(c, 22), Gravity.TOP or Gravity.END).apply { topMargin = Ui.px(c, 4); rightMargin = Ui.px(c, 6) })
                if (l == "Super Admin") adminBadge = bd else if (ic == R.drawable.ic_apk) apkBadge = bd else supBadge = bd }
            val p = GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f)); p.width = 0; p.height = Ui.px(c, 66); p.setMargins(Ui.px(c, 4), Ui.px(c, 4), Ui.px(c, 4), Ui.px(c, 4))
            g.addView(cell, p)
        }
        col.addView(g, Ui.lp(c, 12))
        setContentView(root)
    }

    private fun role(dir: Boolean, go: () -> Unit): View {
        val c = this
        val card = LinearLayout(c); card.orientation = LinearLayout.VERTICAL; card.gravity = Gravity.CENTER
        card.background = Ui.round(if (dir) 0x8C1A160C.toInt() else 0x7312100D, Ui.dp(c, 18f), if (dir) Ui.GOLD else 0x55FFFFFF, Ui.px(c, if (dir) 2 else 1))
        val icBox = FrameLayout(c); icBox.background = Ui.round(if (dir) Ui.GOLD else 0xFF1F2733.toInt(), Ui.dp(c, 16f))
        val iv = ImageView(c); iv.setImageResource(if (dir) R.drawable.ic_rec else R.drawable.ic_camera)
        icBox.addView(iv, Ui.fl(Ui.px(c, 34), Ui.px(c, 34), Gravity.CENTER))
        card.addView(icBox, LinearLayout.LayoutParams(Ui.px(c, 68), Ui.px(c, 68)))
        card.addView(Ui.center(Ui.label(c, if (dir) t("DIRECTOR", "PENGARAH") else t("CAMERA", "KAMERA"), 19f, if (dir) Ui.GOLD else 0xFFC9D4E6.toInt(), true)), Ui.lp(c, 14))
        card.addView(Ui.center(Ui.label(c, if (dir) t("Control angles & record.", "Kawal sudut & rakam.") else t("Extra camera.", "Kamera tambahan."), 13f, Ui.TEXT)), Ui.lp(c, 4))
        card.setOnClickListener { go() }
        return card
    }

    /* like the web: the Director needs an account (log in or register) */
    private fun goDirector() {
        if (!Auth.signedIn) { Toast.makeText(this, t("Please log in first", "Sila log masuk dahulu"), Toast.LENGTH_SHORT).show(); openAccount(); return }
        startActivity(Intent(this, DirectorActivity::class.java))
    }

    private fun paintLogin() { loginBtn.text = if (Auth.signedIn) (if (Auth.isAdmin) "Admin" else if (Auth.isPro) "Pro · ${Auth.daysLeft}" + t("d", "h") else t("Free", "Asas")) else t("Log in", "Log Masuk") }

    private fun openAccount() {
        if (Auth.signedIn) {
            val s = Ui.sheet(this, t("My account", "Akaun saya"))
            s.body.addView(Ui.label(this, Auth.email ?: "", 16f, Ui.TEXT, true))
            val plan = when { Auth.isAdmin -> t("Admin — everything is on", "Admin — semua dibuka"); Auth.status == "suspended" -> t("Suspended", "Digantung")
                Auth.isPro && Auth.plan == "trial" -> t("Free Pro: ${Auth.daysLeft} day(s) left", "Pro percuma: ${Auth.daysLeft} hari lagi")
                Auth.isPro -> t("Pro: ${Auth.daysLeft} day(s) left", "Pro: ${Auth.daysLeft} hari lagi"); else -> t("Basic (free)", "Asas (percuma)") }
            s.body.addView(Ui.label(this, plan, 14f, Ui.GOLD), Ui.lp(this, 4))
            // public user name (shown on shared templates instead of the email)
            val un = Ui.label(this, "@" + (Profile.name(this) ?: "—"), 15f, Ui.TEXT, true); s.body.addView(un, Ui.lp(this, 12))
            Thread { val n = Profile.load(this); runOnUiThread { un.text = "@" + (n ?: "—") } }.start()
            s.body.addView(Ui.btn(this, "✏️ " + t("Set user name", "Tetapkan nama pengguna")) { NameBox.show(AppCompatActivityLike(this)) { un.text = "@" + (Profile.name(this) ?: "—"); toast(t("Saved", "Disimpan")) } }, Ui.lp(this, 6))
            if (!Auth.isAdmin) s.body.addView(Ui.btn(this, if (Auth.isPro && Auth.plan != "trial") t("Renew", "Sambung langganan") else t("Upgrade to Pro", "Naik taraf ke Pro"), true) { s.dialog.dismiss(); Nav.open(this, "pro") }, Ui.lp(this, 14))
            s.body.addView(Ui.btn(this, t("Log out", "Log keluar")) { Auth.logout(); s.dialog.dismiss(); paintLogin() }, Ui.lp(this, 10))
            s.dialog.show(); return
        }
        var reg = false
        val s = Ui.sheet(this, t("Log in", "Log Masuk"))
        val tabs = LinearLayout(this); tabs.orientation = LinearLayout.HORIZONTAL
        val email = Ui.input(this, t("Email", "Emel"), Auth.email ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = Ui.input(this, t("Password (6+ characters)", "Kata laluan (6+ aksara)"), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        lateinit var go: android.widget.Button
        val tl = Ui.pill(this, t("Log in", "Log Masuk"), true) {}; val tr = Ui.pill(this, t("Register", "Daftar"), false) {}
        fun mode(r: Boolean) { reg = r; Ui.setPill(this, tl, !r); Ui.setPill(this, tr, r); go.text = if (r) t("Create account", "Cipta akaun") else t("Log in", "Log Masuk") }
        tl.setOnClickListener { mode(false) }; tr.setOnClickListener { mode(true) }
        tabs.addView(tl, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.px(this@MainActivity, 4) }); tabs.addView(tr, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.px(this@MainActivity, 4) })
        s.body.addView(tabs)
        s.body.addView(Ui.label(this, t("Email", "Emel"), 13f, Ui.SUB, true), Ui.lp(this, 14)); s.body.addView(email, Ui.lp(this, 4))
        s.body.addView(Ui.label(this, t("Password", "Kata laluan"), 13f, Ui.SUB, true), Ui.lp(this, 10)); s.body.addView(pass, Ui.lp(this, 4))
        go = Ui.btn(this, t("Log in", "Log Masuk"), true) {
            val em = email.text.toString().trim(); val pw = pass.text.toString()
            if (em.isEmpty() || pw.length < 6) { toast(t("Fill in email and a 6+ character password", "Isi emel dan kata laluan 6+ aksara")); return@btn }
            go.isEnabled = false
            val done: (String?) -> Unit = { err -> go.isEnabled = true; if (err == null) { s.dialog.dismiss(); toast(t("Welcome!", "Selamat datang!")); paintLogin() } else toast(Auth.errText(err)) }
            if (reg) Auth.register(em, pw, done) else Auth.login(em, pw, done)
        }
        s.body.addView(go, Ui.lp(this, 16))
        s.body.addView(Ui.center(Ui.label(this, t("— or —", "— atau —"), 13f, Ui.SUB)).apply { layoutParams = Ui.lp(this@MainActivity, 12) })
        val gb = Ui.btn(this, "G   " + t("Log in with Google", "Log masuk dengan Google")) {}
        gb.setOnClickListener { gb.isEnabled = false; Auth.google(this) { err -> gb.isEnabled = true; if (err == null) { s.dialog.dismiss(); toast(t("Welcome!", "Selamat datang!")); paintLogin() } else { val m = Auth.errText(err); if (m.isNotEmpty()) toast(m) } } }
        s.body.addView(gb, Ui.lp(this, 10))
        val forgot = Ui.label(this, t("Forgot password?", "Lupa kata laluan?"), 14f, Ui.GOLD, true); forgot.gravity = Gravity.CENTER; forgot.setPadding(0, Ui.px(this, 14), 0, Ui.px(this, 8))
        forgot.setOnClickListener { val em = email.text.toString().trim(); if (em.isEmpty()) toast(t("Type your email first", "Taip emel dahulu")) else Auth.reset(em) { e -> toast(if (e == null) t("Reset link sent to your email", "Pautan set semula dihantar ke emel") else Auth.errText(e)) } }
        s.body.addView(forgot)
        s.dialog.show()
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    @Suppress("unused") private fun noop(v: View) = Color.TRANSPARENT
}
