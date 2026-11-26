package com.promulticam.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.promulticam.app.Ui.t

/* ---------------- notifications: new support messages (admin) and replies (users) ---------------- */
object Notifier {
    private const val CH = "support"
    fun schedule(c: Context) {
        val w = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("pmc-check", ExistingPeriodicWorkPolicy.KEEP, w)
    }
    /* how many support chats wait for the admin (also used for the badge) */
    var waiting = 0; var userUnread = false; var newApk = ""
    /* a newer app on the site? badge on "App APK" + one notification per new version */
    fun checkApk(c: Context) {
        try {
            val j = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("https://promulticam.web.app/apk/version.json?t=" + System.currentTimeMillis()).build()).execute().use { org.json.JSONObject(it.body?.string() ?: "{}") }
            val mine = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode; val code = j.optLong("code")
            newApk = if (code > mine) j.optString("v") else ""
            val sp = c.getSharedPreferences("pmc", Context.MODE_PRIVATE)
            if (code > mine && code > sp.getLong("nApk", 0)) { sp.edit().putLong("nApk", code).apply()
                show(c, 3, Ui.t("New ProMultiCam version ", "Versi baru ProMultiCam ") + j.optString("v"), Ui.t("Tap to update the app.", "Tekan untuk kemas kini app."), Intent(c, ApkActivity::class.java)) }
        } catch (e: Exception) {}
    }
    fun checkSync(c: Context) {
        checkApk(c)
        Auth.init(c); if (!Auth.signedIn) return
        val sp = c.getSharedPreferences("pmc", Context.MODE_PRIVATE)
        try {
            if (Auth.isAdmin) {
                val l = Fs.list("supportChats", whereField = "unreadAdmin", whereVal = true, limit = 100)
                waiting = l.size
                val top = l.maxByOrNull { it.t("lastAt") } ?: return
                if (top.t("lastAt") > sp.getLong("nAdmin", 0)) {
                    sp.edit().putLong("nAdmin", top.t("lastAt")).apply()
                    show(c, 1, Ui.t("Support: ", "Sokongan: ") + top.s("email").ifEmpty { Ui.t("customer", "pelanggan") } + if (l.size > 1) Ui.t(" (+${l.size - 1} more)", " (+${l.size - 1} lagi)") else "", top.s("lastMsg"),
                        Intent(c, AdminActivity::class.java).putExtra("tab", 4))
                }
            } else {
                val d = Fs.get("supportChats/" + Auth.uid) ?: run { userUnread = false; return }
                userUnread = d.b("unreadUser") && d.s("lastFrom") == "admin"
                if (d.b("unreadUser") && d.s("lastFrom") == "admin" && d.t("lastAt") > sp.getLong("nUser", 0)) {
                    sp.edit().putLong("nUser", d.t("lastAt")).apply()
                    show(c, 2, Ui.t("Reply from ProMultiCam support", "Balasan dari sokongan ProMultiCam"), d.s("lastMsg"), Intent(c, SupportActivity::class.java))
                }
            }
        } catch (e: Exception) {}
    }
    private fun show(c: Context, id: Int, title: String, text: String, open: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, Ui.t("Messages & updates", "Mesej & kemas kini"), NotificationManager.IMPORTANCE_HIGH))
        // back from the opened page goes to the home page, not out of the app
        val pi = androidx.core.app.TaskStackBuilder.create(c).addNextIntent(Intent(c, MainActivity::class.java)).addNextIntent(open).getPendingIntent(id, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, NotificationCompat.Builder(c, CH).setSmallIcon(R.drawable.ic_chat).setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
}
class CheckWorker(c: Context, p: WorkerParameters) : Worker(c, p) { override fun doWork(): Result { Ui.init(applicationContext); Notifier.checkSync(applicationContext); return Result.success() } }

/* ---------------- Super Admin (same as the web admin page) ---------------- */
class AdminActivity : PageActivity() {
    override val title0 get() = "Super Admin"
    private var tab = 0
    private lateinit var tabs: List<Button>
    private val dt = SimpleDateFormat("dd MMM yyyy", Locale("ms", "MY")); private val dtt = SimpleDateFormat("dd MMM, HH:mm", Locale("ms", "MY"))
    private fun rm(v: Double) = "RM" + String.format(Locale.US, "%.2f", v)
    private fun day(ms: Long) = if (ms > 0) dt.format(Date(ms)) else "—"
    override fun build() {
        if (!Auth.isAdmin) { body.addView(Ui.label(this, t("Super Admin only.", "Untuk Super Admin sahaja."), 15f)); return }
        val r = Flow(this, Ui.px(this, 6))
        tabs = listOf(t("Summary", "Ringkasan"), t("Customers", "Pelanggan"), t("Payments", "Bayaran"), t("Prices & promo", "Harga & Promo"), t("Support", "Sokongan")).mapIndexed { i, s ->
            Ui.pill(this, s, i == 0) { show(i) }.also { it.textSize = 13f; r.addView(it) } }
        root.addView(r, 1, Ui.lp(this, 8))
        show(intent.getIntExtra("tab", 0))
    }
    override fun onResume() { super.onResume(); if (::tabs.isInitialized && tab == 4) show(4) }
    private fun show(i: Int) {
        tab = i; tabs.forEachIndexed { j, b -> Ui.setPill(this, b, j == i) }
        body.removeAllViews(); val w = Ui.label(this, t("Loading…", "Memuatkan…"), 14f, Ui.SUB); body.addView(w)
        bg {
            when (i) {
                0, 1, 2 -> { val users = Fs.list("users", "createdAt", limit = 1000); val pays = if (i != 1) Fs.list("payments", "createdAt", limit = 500) else emptyList()
                    main.post { if (tab != i) return@post; body.removeAllViews(); when (i) { 0 -> stats(users, pays); 1 -> customers(users); else -> payments(pays) } } }
                3 -> { val plans = Fs.get("config/plans"); val promos = Fs.list("promos", limit = 300).sortedByDescending { it.t("createdAt") }
                    main.post { if (tab != i) return@post; body.removeAllViews(); prices(plans, promos) } }
                4 -> { val th = Fs.list("supportChats", "lastAt", limit = 300)
                    main.post { if (tab != i) return@post; body.removeAllViews(); support(th) } }
            }
        }
    }
    private fun status(u: Fs.Doc): Pair<String, Int> {
        val now = System.currentTimeMillis(); val ex = u.t("expiry")
        return when { u.s("plan") == "admin" -> "ADMIN" to 0xFF5FB38C.toInt(); u.s("status") == "suspended" -> t("SUSPENDED", "GANTUNG") to 0xFFD9776B.toInt()
            ex > now -> (if (u.s("plan") == "trial") t("TRIAL", "PERCUBAAN") else "PRO") to Ui.GOLD; else -> t("FREE", "PERCUMA") to Ui.SUB }
    }
    private fun stats(users: List<Fs.Doc>, pays: List<Fs.Doc>) {
        val now = System.currentTimeMillis(); val cust = users.filter { it.s("plan") != "admin" }; val pro = cust.filter { it.s("status") != "suspended" && it.t("expiry") > now }
        val paid = pays.filter { it.s("status") == "paid" }
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }
        val mRev = paid.filter { it.t("paidAt") >= cal.timeInMillis }.sumOf { it.n("amount") }
        val g = android.widget.GridLayout(this); g.columnCount = 2
        listOf(t("Customers", "Jumlah pelanggan") to cust.size.toString(), t("Paid Pro active", "Pro aktif (berbayar)") to pro.count { it.s("plan") != "trial" }.toString(),
            t("On trial", "Dalam percubaan") to pro.count { it.s("plan") == "trial" }.toString(), t("Successful payments", "Bayaran berjaya") to paid.size.toString(),
            t("This month", "Hasil bulan ini") to rm(mRev), t("All time", "Hasil keseluruhan") to rm(paid.sumOf { it.n("amount") }), t("Support waiting", "Sokongan menunggu") to Notifier.waiting.toString()).forEachIndexed { i, (k, v) ->
            val c = Ui.card(this); c.addView(Ui.label(this, k, 12f, Ui.SUB, true)); c.addView(Ui.label(this, v, 22f, Ui.GOLD, true), Ui.lp(this, 4))
            val p = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / 2), android.widget.GridLayout.spec(i % 2, 1f)); p.width = 0; p.setMargins(5, 5, 5, 5); g.addView(c, p) }
        body.addView(g)
    }
    private fun customers(users: List<Fs.Doc>) {
        val q = Ui.input(this, t("Search email…", "Cari emel…")); body.addView(q)
        val list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL; body.addView(list)
        val plbl = mapOf("admin" to "Admin", "trial" to t("Trial", "Percubaan"), "monthly" to t("Monthly", "Bulanan"), "yearly" to t("Yearly", "Tahunan"), "promo" to t("Promo code", "Kod promo"), "free" to t("Free", "Percuma"))
        fun paint() {
            list.removeAllViews(); val s = q.text.toString().trim().lowercase()
            val l = users.filter { s.isEmpty() || it.s("email").lowercase().contains(s) }
            list.addView(Ui.label(this, "${l.size} " + t("customers", "pelanggan"), 12f, Ui.SUB), Ui.lp(this, 8))
            l.take(300).forEach { u -> val (st, col) = status(u)
                val c = Ui.card(this); val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
                top.addView(Ui.label(this, u.s("email").ifEmpty { u.id }, 14f, Ui.TEXT, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(Ui.label(this, " $st ", 11f, col, true).apply { background = Ui.round(0x22FFFFFF, Ui.dp(this@AdminActivity, 8f), col, 1); setPadding(8, 2, 8, 2) })
                c.addView(top)
                c.addView(Ui.label(this, (plbl[u.s("plan")] ?: u.s("plan").ifEmpty { "—" }) + " · " + t("until ", "hingga ") + (if (u.s("plan") == "admin") t("no limit", "tiada had") else day(u.t("expiry"))) +
                    " · " + rm(u.n("totalPaid")) + " · " + t("joined ", "daftar ") + day(u.t("createdAt")), 12f, Ui.SUB), Ui.lp(this, 4))
                list.addView(c, Ui.lp(this, 6)) }
        }
        q.addTextChangedListener(object : android.text.TextWatcher { override fun afterTextChanged(e: android.text.Editable?) { paint() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}; override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {} })
        paint()
    }
    private fun payments(pays: List<Fs.Doc>) {
        if (pays.isEmpty()) { body.addView(Ui.label(this, t("No payments yet.", "Belum ada bayaran."), 14f, Ui.SUB)); return }
        pays.forEach { p -> val c = Ui.card(this); val col = when (p.s("status")) { "paid" -> 0xFF5FB38C.toInt(); "pending" -> Ui.GOLD; else -> 0xFFD9776B.toInt() }
            val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
            top.addView(Ui.label(this, rm(p.n("amount")) + "  " + (if (p.s("plan") == "yearly") t("Yearly", "Tahunan") else t("Monthly", "Bulanan")), 15f, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(Ui.label(this, " " + p.s("status") + " ", 11f, col, true).apply { background = Ui.round(0x22FFFFFF, Ui.dp(this@AdminActivity, 8f), col, 1); setPadding(8, 2, 8, 2) })
            c.addView(top)
            c.addView(Ui.label(this, p.s("email") + " · " + day(p.t("createdAt")) + (if (p.s("promo").isNotEmpty()) " · " + p.s("promo") else "") + (if (p.n("discount") > 0) " (-${p.n("discount").toInt()}%)" else "") + " · " + p.s("billCode"), 12f, Ui.SUB), Ui.lp(this, 4))
            body.addView(c, Ui.lp(this, 6)) }
    }
    private fun prices(plans: Fs.Doc?, promos: List<Fs.Doc>) {
        val c = Ui.card(this); c.addView(Ui.label(this, t("Prices", "Harga"), 16f, Ui.TEXT, true))
        fun num(h: String, v: String): android.widget.EditText { c.addView(Ui.label(this, h, 12f, Ui.SUB, true), Ui.lp(this, 8)); val e = Ui.input(this, h, v, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL); c.addView(e, Ui.lp(this, 4)); return e }
        fun fmt(d: Double) = if (d == 0.0) "" else if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
        val m = num(t("Monthly (RM)", "Bulanan (RM)"), fmt(plans?.n("monthly") ?: 0.0)); val y = num(t("Yearly (RM)", "Tahunan (RM)"), fmt(plans?.n("yearly") ?: 0.0))
        val tr = num(t("Free trial days", "Hari percubaan percuma"), ((plans?.f?.get("trialDays") as? Number)?.toInt() ?: 7).toString())
        c.addView(Ui.btn(this, t("Save prices", "Simpan harga"), true) {
            val mv = m.text.toString().toDoubleOrNull() ?: 0.0; val yv = y.text.toString().toDoubleOrNull() ?: 0.0; val tv = tr.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (mv < 1 || yv < 1) { toast(t("Enter at least RM1", "Masukkan harga sekurang-kurangnya RM1")); return@btn }
            bg { val ok = Fs.write("config/plans", mapOf("monthly" to mv, "yearly" to yv, "trialDays" to tv.toLong()), now = listOf("updatedAt")); main.post { toast(if (ok) t("Prices saved", "Harga disimpan") else t("Failed", "Gagal")) } }
        }, Ui.lp(this, 12))
        body.addView(c)
        // promo codes
        val pc = Ui.card(this); pc.addView(Ui.label(this, t("Promo codes", "Kod promo"), 16f, Ui.TEXT, true))
        val code = Ui.input(this, t("Code, e.g. RAYA30", "Kod, cth. RAYA30")); code.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; pc.addView(code, Ui.lp(this, 8))
        val days = Ui.input(this, t("Pro days", "Hari Pro"), "", InputType.TYPE_CLASS_NUMBER); pc.addView(days, Ui.lp(this, 6))
        val max = Ui.input(this, t("How many people can use it", "Berapa orang boleh guna"), "", InputType.TYPE_CLASS_NUMBER); pc.addView(max, Ui.lp(this, 6))
        pc.addView(Ui.btn(this, t("Create code", "Cipta kod"), true) {
            val k = code.text.toString().trim().uppercase(); val dv = days.text.toString().toIntOrNull() ?: 0; val mx = max.text.toString().toIntOrNull() ?: 0
            if (!Regex("^[A-Z0-9-]{3,30}$").matches(k)) { toast(t("Code: 3–30 letters/numbers, no spaces", "Nama kod: 3–30 huruf/nombor, tanpa jarak")); return@btn }
            if (mx < 1) { toast(t("Enter how many people (at least 1)", "Masukkan berapa orang boleh guna (sekurang-kurangnya 1)")); return@btn }
            if (dv !in 1..3650) { toast(t("Enter Pro days (1–3650)", "Masukkan berapa hari Pro (1–3650)")); return@btn }
            bg { val ok = Fs.write("promos/$k", mapOf("type" to "days", "value" to dv.toLong(), "maxUses" to mx.toLong(), "uses" to 0L, "active" to true), now = listOf("createdAt"), merge = false, create = true)
                main.post { toast(if (ok) t("Code created", "Kod dicipta") else t("Code exists or failed", "Kod sudah wujud atau gagal")); if (ok) show(3) } }
        }, Ui.lp(this, 10))
        promos.forEach { p -> val full = p.n("maxUses") > 0 && p.n("uses") >= p.n("maxUses"); val on = p.f["active"] != false
            val r = LinearLayout(this); r.orientation = LinearLayout.VERTICAL; r.setPadding(0, Ui.px(this, 10), 0, 0)
            r.addView(Ui.label(this, p.id + "  ·  " + (if (p.s("type") == "days") "${p.n("value").toInt()} " + t("days", "hari") else t("Discount ", "Diskaun ") + "${p.n("value").toInt()}%") +
                "  ·  ${p.n("uses").toInt()}" + (if (p.n("maxUses") > 0) " / ${p.n("maxUses").toInt()}" else "") + "  ·  " + (if (!on) t("Off", "Tidak aktif") else if (full) t("Full", "Penuh") else t("Active", "Aktif")), 14f, if (on && !full) Ui.TEXT else Ui.SUB, true))
            val br = LinearLayout(this)
            br.addView(Ui.btn(this, if (on) t("Turn off", "Matikan") else t("Turn on", "Aktifkan")) { bg { Fs.write("promos/" + p.id, mapOf("active" to !on)); main.post { show(3) } } }.apply { textSize = 13f }, LinearLayout.LayoutParams(-2, Ui.px(this, 42)).apply { rightMargin = 8 })
            if (!on) br.addView(Ui.dangerBtn(this, t("Delete", "Padam")) { AlertDialog.Builder(this).setMessage(t("Delete code ${p.id}?", "Padam kod ${p.id}?")).setPositiveButton(t("Delete", "Padam")) { _, _ -> bg { Fs.delete("promos/" + p.id); main.post { show(3) } } }.setNegativeButton(t("Cancel", "Batal"), null).show() }.apply { textSize = 13f }, LinearLayout.LayoutParams(-2, Ui.px(this, 42)))
            r.addView(br, Ui.lp(this, 6)); pc.addView(r) }
        body.addView(pc, Ui.lp(this, 12))
    }
    private var topicF = ""
    private fun support(all: List<Fs.Doc>) {
        Notifier.waiting = all.count { it.b("unreadAdmin") }
        // filter by kind of issue
        val fr = Flow(this, Ui.px(this, 6))
        (listOf("" to t("All", "Semua")) + Topics.ALL.map { it to Topics.name(it) } + listOf("none" to t("No type", "Tiada jenis"))).forEach { (k, l) ->
            val n = all.count { if (k.isEmpty()) true else if (k == "none") it.s("topic").isEmpty() else it.s("topic") == k }
            val nn = all.count { it.b("unreadAdmin") && (if (k.isEmpty()) true else if (k == "none") it.s("topic").isEmpty() else it.s("topic") == k) }
            if (n == 0 && k.isNotEmpty()) return@forEach
            fr.addView(Ui.pill(this, "$l ($n)" + if (nn > 0) " ●" else "", topicF == k) { topicF = k; body.removeAllViews(); support(all) }.apply { textSize = 12f }) }
        body.addView(fr, Ui.lp(this, 2))
        val th = all.filter { if (topicF.isEmpty()) true else if (topicF == "none") it.s("topic").isEmpty() else it.s("topic") == topicF }
        if (th.isEmpty()) { body.addView(Ui.label(this, t("No support messages yet.", "Belum ada mesej sokongan."), 14f, Ui.SUB), Ui.lp(this, 10)); return }
        th.forEach { x -> val c = Ui.card(this)
            if (x.b("unreadAdmin")) c.background = Ui.round(0xFF262117.toInt(), Ui.dp(this, 16f), Ui.GOLD, Ui.px(this, 2))
            val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
            top.addView(Ui.label(this, x.s("email").ifEmpty { x.id }, 14f, Ui.TEXT, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            if (x.b("unreadAdmin")) top.addView(Ui.label(this, " ● " + t("New", "Baru") + " ", 11f, Ui.GOLD, true))
            c.addView(top)
            if (x.s("topic").isNotEmpty()) c.addView(Ui.label(this, Topics.name(x.s("topic")), 12f, Ui.GOLD, true), Ui.lp(this, 2))
            c.addView(Ui.label(this, (if (x.s("lastFrom") == "admin") t("You: ", "Anda: ") else "") + x.s("lastMsg"), 13f, Ui.SUB).apply { maxLines = 2 }, Ui.lp(this, 4))
            if (x.t("lastAt") > 0) c.addView(Ui.label(this, dtt.format(Date(x.t("lastAt"))), 11f, Ui.SUB))
            c.setOnClickListener { startActivity(Intent(this, AdminChatActivity::class.java).putExtra("id", x.id).putExtra("email", x.s("email"))) }
            body.addView(c, Ui.lp(this, 6)) }
    }
}

/* one support conversation, answered by the admin */
class AdminChatActivity : PageActivity() {
    override val title0 get() = intent.getStringExtra("email")?.ifEmpty { null } ?: t("Support", "Sokongan")
    private val id get() = intent.getStringExtra("id") ?: ""
    private var alive = true; private var lastN = -1
    private lateinit var inp: android.widget.EditText
    private val pick = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { u -> if (u != null) bg { val b = Chat.photo(this, u); main.post { if (b != null) send(inp.text.toString().trim(), b) else toast(t("This photo cannot be used", "Gambar ini tidak boleh digunakan")) } } }
    override fun build() {
        val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.btn(this, "📎") { pick.launch("image/*") }, LinearLayout.LayoutParams(Ui.px(this, 52), -2).apply { rightMargin = d / 2 })
        inp = Ui.input(this, t("Reply…", "Balas…")); inp.maxLines = 4; row.addView(inp, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.btn(this, t("Send", "Hantar"), true) { send(inp.text.toString().trim().take(2000), null) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8 })
        root.addView(row, Ui.lp(this, 6))
        val ban = Ui.label(this, Ui.t("Issue: …", "Jenis masalah: …"), 14f, Ui.GOLD, true); ban.background = Ui.round(0xFF262117.toInt(), Ui.dp(this, 12f), Ui.GOLD, 1); ban.setPadding(d, d * 2 / 3, d, d * 2 / 3)
        root.addView(ban, 1, Ui.lp(this, 6))
        bg { val k = Fs.get("supportChats/$id")?.s("topic") ?: ""; main.post { ban.text = t("Issue: ", "Jenis masalah: ") + if (k.isEmpty()) t("not chosen", "tidak dipilih") else Topics.name(k) } }
        bg { Fs.write("supportChats/$id", mapOf("unreadAdmin" to false)) }
        poll(true)
    }
    private fun send(v: String, img: String?) {
        if (v.isEmpty() && img == null) return
        inp.setText("")
        bg { val ok = Chat.send(id, "admin", v, img); main.post { if (!ok) { inp.setText(v); toast(t("Not sent", "Tidak dihantar")) } else poll(false) } }
    }
    override fun onDestroy() { alive = false; super.onDestroy() }
    private fun poll(again: Boolean) {
        bg { val l = Fs.list("messages", "at", desc = false, limit = 300, parent = "supportChats/$id")
            main.post { if (!alive) return@post
                if (l.size != lastN) { lastN = l.size; Chat.show(this, body, l, "admin", null) }
                if (again) main.postDelayed({ if (alive) poll(true) }, 5000) } }
    }
}
