package com.promulticam.app

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.promulticam.app.Ui.t
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Where every button of the app goes. Everything is inside the app (no web pages). */
object Nav {
    fun open(a: AppCompatActivity, what: String, file: String? = null) {
        val cls = when (what) {
            "pro" -> PlansActivity::class.java; "support" -> SupportActivity::class.java; "lounge" -> LoungeActivity::class.java; "test" -> TestActivity::class.java
            "manual" -> ManualActivity::class.java; "about" -> AboutActivity::class.java; "apk" -> ApkActivity::class.java
            "edit", "media" -> EditorActivity::class.java
            else -> return
        }
        if ((what == "support" || what == "pro" || what == "lounge") && !Auth.signedIn) { Toast.makeText(a, t("Please log in first", "Sila log masuk dahulu"), Toast.LENGTH_SHORT).show(); return }
        a.startActivity(Intent(a, cls).putExtra("f", file ?: ""))
    }
}

/* the frame of every page: back button + title, then a scrolling body */
abstract class PageActivity : AppCompatActivity() {
    protected lateinit var body: LinearLayout
    protected lateinit var root: LinearLayout
    protected val main = Handler(Looper.getMainLooper())
    protected val d get() = Ui.px(this, 12)
    abstract val title0: String
    open val scroll = true   // false: the page fits one screen, no scrolling
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this); Auth.init(this)
        root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Ui.BG); root.setPadding(d, d * 2, d, d)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.iconBtn(this, R.drawable.ic_back) { finish() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 46)))
        top.addView(Ui.label(this, title0, 21f, Ui.TEXT, true).apply { setPadding(d, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        body = LinearLayout(this); body.orientation = LinearLayout.VERTICAL; body.setPadding(0, d / 2, 0, if (scroll) d * 2 else 0)
        if (scroll) { val sv = ScrollView(this); sv.addView(body); root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d / 2 }) }
        else root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d / 2 })
        setContentView(root)
        build()
    }
    abstract fun build()
    protected fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    protected fun bg(work: () -> Unit) = Thread { try { work() } catch (e: Exception) { main.post { toast(t("No internet connection", "Tiada sambungan internet")) } } }.start()
}

/* ---------------- Plans & subscription ---------------- */
class PlansActivity : PageActivity() {
    override val title0 get() = t("Plans & subscription", "Pelan & langganan")
    private var plans = JSONObject()
    override fun onResume() { super.onResume(); Auth.refreshProfile { if (!isFinishing) paint() } }
    override fun build() { paint(); bg { plans = Auth.plans(); main.post { if (!isFinishing) paint() } } }
    private fun money(v: Double) = "RM" + (if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v))
    private fun paint() {
        body.removeAllViews()
        val adm = Auth.isAdmin; val pro = Auth.isPro; val dl = Auth.daysLeft
        val paid = pro && !adm && (Auth.plan == "monthly" || Auth.plan == "yearly")
        val cur = if (adm) "admin" else if (paid) Auth.plan else if (pro) "trial" else "free"
        val pn = { p: String -> if (p == "yearly") t("Yearly", "Tahunan") else t("Monthly", "Bulanan") }
        val now = when (cur) { "admin" -> t("Admin account — everything is on.", "Akaun admin — semua dibuka.")
            "free" -> t("You are on the free Basic plan.", "Anda guna pelan Asas (percuma).")
            "trial" -> t("Free Pro: $dl day(s) left.", "Pro percuma: tinggal $dl hari.")
            else -> t("You are on Pro ${pn(cur)}: $dl day(s) left.", "Anda guna Pro ${pn(cur)}: tinggal $dl hari.") }
        body.addView(Ui.card(this).apply { addView(Ui.label(this@PlansActivity, now, 15f, Ui.GOLD, true)) })
        val mo = plans.optString("monthly").toDoubleOrNull() ?: 0.0; val yr = plans.optString("yearly").toDoubleOrNull() ?: 0.0
        val save = if (mo > 0 && yr > 0) Math.round((1 - yr / (mo * 12)) * 100).toInt() else 0
        data class P(val id: String, val name: String, val price: Double, val per: String, val feats: List<String>, val save: Int = 0)
        val list = listOf(
            P("free", t("Basic (free)", "Asas (percuma)"), 0.0, "", listOf(t("Director + 1 camera phone", "Pengarah + 1 telefon kamera"), t("Standard look, 720p, Full layout", "Gaya Standard, 720p, susun atur Penuh"), t("Zoom, focus, front/back camera", "Zoom, fokus, kamera depan/belakang"), t("Record, pause, save to phone", "Rakam, jeda, simpan ke telefon"))),
            P("monthly", t("Monthly", "Bulanan"), mo, t("per month", "sebulan"), listOf(t("Up to 6 camera phones", "Sehingga 6 telefon kamera"), t("1080p from every phone", "1080p dari setiap telefon"), t("All looks, Split Screen and Picture-In-Picture", "Semua gaya, Split Screen dan Picture-In-Picture"), t("Switch effects, auto switching and quick templates", "Kesan tukar sudut, tukar automatik dan templat pantas"), t("Title & closing cards", "Kad tajuk & penutup"), t("Editor: cut, text, video & photos", "Editor: potong, teks, video & gambar"), t("Brand card, logo, colours and fonts, teleprompter, scenes", "Kad jenama, logo, warna dan fon, teleprompter, babak"))),
            P("yearly", t("Yearly", "Tahunan"), yr, t("per year", "setahun"), listOf(t("Everything in Monthly, at a lower price", "Semua ciri Bulanan, harga lebih jimat")), save))
        var pick: String? = null
        val pay = LinearLayout(this); pay.orientation = LinearLayout.VERTICAL; pay.visibility = View.GONE
        val payFor = Ui.label(this, "", 15f, Ui.TEXT, true)
        list.forEach { p ->
            val c = Ui.card(this)
            if (cur == p.id) c.background = Ui.round(0xFF262117.toInt(), Ui.dp(this, 16f), Ui.GOLD, Ui.px(this, 2))
            val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
            top.addView(Ui.label(this, p.name, 17f, Ui.TEXT, true))
            fun badge(s: String, col: Int) = Ui.label(this, " $s ", 11f, col, true).apply { background = Ui.round(0xFF262117.toInt(), Ui.dp(this@PlansActivity, 8f), col, 1); setPadding(8, 2, 8, 2) }
            if (p.id != "free") top.addView(badge("PRO", Ui.GOLD), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
            if (p.save > 0) top.addView(badge(t("Save ${p.save}%", "Jimat ${p.save}%"), 0xFF5FB38C.toInt()), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
            c.addView(top)
            c.addView(Ui.label(this, if (p.id == "free") "RM0" else (if (p.price > 0) money(p.price) else "—") + "  " + p.per, 22f, Ui.GOLD, true), Ui.lp(this, 6))
            p.feats.forEach { c.addView(Ui.label(this, "•  $it", 14f, Ui.TEXT), Ui.lp(this, 4)) }
            var label: String; var on = false
            when {
                adm -> label = t("Admin — no payment needed", "Admin — tiada bayaran")
                p.id == "free" -> label = if (cur == "free") t("Current plan", "Pelan semasa") else t("Used when Pro ends", "Digunakan bila Pro tamat")
                cur == p.id -> if (dl <= 5) { label = t("Renew now", "Perbaharui sekarang"); on = true } else label = t("Active · $dl day(s) left", "Aktif · tinggal $dl hari")
                cur == "yearly" && p.id == "monthly" -> label = t("You already have yearly", "Anda sudah ada tahunan")
                cur == "monthly" && p.id == "yearly" -> { label = t("Upgrade to yearly", "Naik taraf ke tahunan"); on = true }
                else -> { label = t("Choose ${p.name}", "Pilih ${p.name}"); on = true }
            }
            if (p.price <= 0 && p.id != "free") on = false
            val b = Ui.btn(this, label, on) { pick = p.id; payFor.text = t("Pay for ${p.name}: ${money(p.price)}", "Bayar untuk ${p.name}: ${money(p.price)}"); pay.visibility = View.VISIBLE }
            b.isEnabled = on; if (!on) b.alpha = 0.6f
            c.addView(b, Ui.lp(this, 10))
            body.addView(c, Ui.lp(this, 10))
        }
        // pay box
        pay.addView(payFor)
        val promo = Ui.input(this, t("Promo code", "Kod promo")); pay.addView(promo, Ui.lp(this, 8))
        val sp = getSharedPreferences("pmc", Context.MODE_PRIVATE)
        val phone = Ui.input(this, t("Phone number", "No. telefon"), sp.getString("payphone", "") ?: "", android.text.InputType.TYPE_CLASS_PHONE); pay.addView(phone, Ui.lp(this, 6))
        pay.addView(Ui.hint(this, t("Check first: Director phone Android 10 or newer, 4 GB RAM and 5 GB free storage. Use Device check and the free Pro days before paying.",
            "Semak dahulu: telefon Pengarah Android 10 ke atas, RAM 4 GB dan storan kosong 5 GB. Guna Semak peranti dan Pro percuma sebelum bayar.")))
        var ok = false
        pay.addView(Ui.check(this, t("I have checked my phones", "Saya sudah semak telefon saya"), false) { ok = it }, Ui.lp(this, 4))
        val pb = Ui.btn(this, t("Pay now", "Bayar sekarang"), true) {}
        pb.setOnClickListener {
            if (!ok) { toast(t("Please confirm you have checked the device requirements first.", "Sila sahkan anda telah semak keperluan peranti dahulu.")); return@setOnClickListener }
            val pl = pick ?: return@setOnClickListener
            sp.edit().putString("payphone", phone.text.toString().trim()).apply()
            pb.isEnabled = false
            Thread {
                val r = try { Auth.callFn("pmc_createBill", JSONObject().put("plan", pl).put("promo", promo.text.toString().trim().uppercase()).put("phone", phone.text.toString().trim())) } catch (e: Exception) { JSONObject().put("err", e.message ?: "") }
                main.post {
                    pb.isEnabled = true
                    val url = r.optString("url")
                    if (url.isNotEmpty()) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}; toast(t("Finish paying, then come back here.", "Siapkan bayaran, kemudian kembali ke sini.")) }
                    else toast(r.optString("err").ifEmpty { t("Something went wrong", "Ada masalah") })
                }
            }.start()
        }
        pay.addView(pb, Ui.lp(this, 8))
        body.addView(pay, Ui.lp(this, 14))
    }
}

/* ---------------- Support chat (same chat as the web) ---------------- */
class SupportActivity : PageActivity() {
    override val title0 get() = t("Support", "Sokongan")
    private var alive = true
    private var lastN = -1
    private val feed by lazy { Feed("messages", "supportChats/" + Auth.uid, 300) }
    private var pending: String? = null
    private lateinit var inp: android.widget.EditText
    private var topic = ""
    private val pick = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { u -> if (u != null) attach(u) }
    override fun build() {
        // the kind of issue, so support can sort messages
        val sp = getSharedPreferences("pmc", Context.MODE_PRIVATE); topic = sp.getString("supTopic", "") ?: ""
        val ban = Ui.label(this, "", 14f, Ui.GOLD, true); ban.background = Ui.round(0xFF262117.toInt(), Ui.dp(this, 12f), Ui.GOLD, 1); ban.setPadding(d, d * 2 / 3, d, d * 2 / 3)
        root.addView(ban, 1, Ui.lp(this, 6))
        fun banner() { ban.text = t("Issue: ", "Jenis masalah: ") + if (topic.isEmpty()) t("choose below", "pilih di bawah") else Topics.name(topic) }
        banner()
        root.addView(Ui.label(this, t("Issue", "Jenis masalah"), 12f, Ui.SUB, true), Ui.lp(this, 6))
        val fl = Flow(this, Ui.px(this, 6)); val tbs = ArrayList<android.widget.Button>()
        Topics.ALL.forEachIndexed { j, k -> val b = Ui.pill(this, Topics.name(k), topic == k) { topic = Topics.ALL[j]; sp.edit().putString("supTopic", topic).apply(); tbs.forEachIndexed { jj, q -> Ui.setPill(this, q, Topics.ALL[jj] == topic) }; banner() }
            b.textSize = 12f; tbs.add(b); fl.addView(b) }
        root.addView(fl, Ui.lp(this, 4))
        // the issue already saved on this conversation wins
        bg { val k = Fs.get("supportChats/" + Auth.uid)?.s("topic") ?: ""
            if (k.isNotEmpty()) main.post { topic = k; tbs.forEachIndexed { jj, q -> Ui.setPill(this, q, Topics.ALL[jj] == topic) }; banner() } }
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.btn(this, "📎") { pick.launch("image/*") }, LinearLayout.LayoutParams(Ui.px(this, 52), -2).apply { rightMargin = d / 2 })
        inp = Ui.input(this, t("Type a message…", "Taip mesej…")); inp.maxLines = 4
        row.addView(inp, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.btn(this, t("Send", "Hantar"), true) { send(inp.text.toString().trim().take(2000), null) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        root.addView(row, Ui.lp(this, 6))
        poll(true)
        Thread { try { Fs.write("supportChats/" + Auth.uid, mapOf("uid" to Auth.uid, "unreadUser" to false)) } catch (e: Exception) {} }.start()
    }
    private fun send(v: String, img: String?) {
        if (v.isEmpty() && img == null) return
        if (topic.isEmpty()) { toast(t("Choose the issue first", "Pilih jenis masalah dahulu")); return }
        inp.setText("")
        bg { val ok = Chat.send(Auth.uid ?: "", "user", v, img, topic); main.post { if (!ok) { inp.setText(v); toast(t("Not sent. Try again.", "Tidak dihantar. Cuba lagi.")) } else poll(false) } }
    }
    /* proof photo: preview, optional note, send */
    private fun attach(u: android.net.Uri) {
        if (topic.isEmpty()) { toast(t("Choose the issue first", "Pilih jenis masalah dahulu")); return }
        bg { val b = Chat.photo(this, u); main.post {
            if (b == null) { toast(t("This photo cannot be used", "Gambar ini tidak boleh digunakan")); return@post }
            val note = Ui.input(this, t("Note (optional)", "Nota (pilihan)"), inp.text.toString())
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(t("Send this photo?", "Hantar gambar ini?")).setView(LinearLayout(this).apply { setPadding(d * 2, d, d * 2, 0); addView(note, LinearLayout.LayoutParams(-1, -2)) })
                .setPositiveButton(t("Send", "Hantar")) { _, _ -> send(note.text.toString().trim().take(2000), b) }.setNegativeButton(t("Cancel", "Batal"), null).show() } }
    }
    override fun onDestroy() { alive = false; super.onDestroy() }
    private fun poll(again: Boolean) {
        bg { val ch = try { feed.refresh() } catch (e: Exception) { false }; val l = ArrayList(feed.docs)
            main.post { if (!alive) return@post
                if (ch || lastN < 0) { lastN = l.size; Chat.show(this, body, l, "user", t("Hi! How can we help you with ProMultiCam?", "Hai! Apa yang boleh kami bantu tentang ProMultiCam?")) }
                if (again) main.postDelayed({ if (alive) poll(true) }, 5000) } }
    }
}

/* ---------------- Device check ---------------- */
class TestActivity : PageActivity() {
    override val title0 get() = t("Device check", "Semak peranti")
    private val perm = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { run() }
    override fun build() {
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) perm.launch(need.toTypedArray()) else run()
    }
    private fun row(ok: Boolean?, title: String, detail: String) {
        val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
        r.background = Ui.round(Ui.CARD, Ui.dp(this, 14f), Ui.LINE, 1); r.setPadding(d, d, d, d)
        val (bgc, fg, s) = when (ok) { true -> Triple(0xFF17261F.toInt(), 0xFF5FB38C.toInt(), "✓"); false -> Triple(0xFF2E1D1B.toInt(), 0xFFD9776B.toInt(), "✕"); else -> Triple(0xFF262117.toInt(), Ui.GOLD, "!") }
        val ic = Ui.center(Ui.label(this, s, 16f, fg, true)); ic.background = Ui.round(bgc, Ui.dp(this, 99f))
        r.addView(ic, LinearLayout.LayoutParams(Ui.px(this, 34), Ui.px(this, 34)))
        val tx = LinearLayout(this); tx.orientation = LinearLayout.VERTICAL; tx.setPadding(d, 0, 0, 0)
        tx.addView(Ui.label(this, title, 15f, Ui.TEXT, true)); tx.addView(Ui.label(this, detail, 13f, Ui.SUB))
        r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(r, Ui.lp(this, 8))
    }
    private fun run() {
        body.removeAllViews()
        val wait = Ui.label(this, t("Testing…", "Menguji…"), 15f, Ui.GOLD, true); body.addView(wait)
        Thread {
            val res = ArrayList<Triple<Boolean?, String, String>>()
            // camera
            try {
                val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                val ids = cm.cameraIdList
                var best = ""; ids.forEach { id -> val m = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sz = m?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.maxByOrNull { it.width * it.height }; if (sz != null && best.isEmpty()) best = "${sz.width}×${sz.height}" }
                val ok = ids.isNotEmpty() && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                res.add(Triple(ok, t("Camera", "Kamera"), if (ok) "${ids.size} " + t("camera(s)", "kamera") + " · $best" else t("Allow camera permission", "Benarkan kebenaran kamera")))
            } catch (e: Exception) { res.add(Triple(false, t("Camera", "Kamera"), t("No camera found", "Tiada kamera"))) }
            // mic
            val micOk = try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) false else {
                    val n = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val a = AudioRecord(MediaRecorder.AudioSource.MIC, 48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, n * 2)
                    val ok = a.state == AudioRecord.STATE_INITIALIZED; a.release(); ok } } catch (e: Exception) { false }
            res.add(Triple(micOk, t("Microphone", "Mikrofon"), if (micOk) t("OK", "OK") else t("Allow microphone permission", "Benarkan kebenaran mikrofon")))
            // encoder
            val avc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { s -> s.equals("video/avc", true) } }
            val hw = avc.any { !it.name.startsWith("OMX.google") && !it.name.startsWith("c2.android") }
            res.add(Triple(avc.isNotEmpty() && hw, t("Recording", "Rakaman"), if (hw) "H.264 · " + t("hardware", "perkakasan") else if (avc.isNotEmpty()) "H.264 · " + t("software only (slower)", "perisian sahaja (lebih perlahan)") else t("No video encoder", "Tiada pengekod video")))
            // storage: free space + write speed
            val dir = Store.dir(this)
            val st = StatFs(dir.absolutePath); val free = st.availableBytes; val tot = st.totalBytes
            val pct = if (tot > 0) (free * 100 / tot).toInt() else 0
            var mbps = 0.0
            try { val f = File(dir, "_speedtest"); val blk = ByteArray(1 shl 20); val t0 = System.nanoTime()
                f.outputStream().use { o -> repeat(24) { o.write(blk) }; o.fd.sync() }; mbps = 24 / ((System.nanoTime() - t0) / 1e9); f.delete() } catch (e: Exception) {}
            val gb = free / 1_073_741_824.0
            res.add(Triple(if (pct >= 20 && mbps >= 40) true else if (pct >= 10 && mbps >= 15) null else false, t("Storage", "Storan"),
                String.format(Locale.US, "%.1f GB ", gb) + t("free", "kosong") + " ($pct%) · " + t("write", "tulis") + " ${mbps.toInt()} MB/s · " + t("keep 20% free for smooth recording", "simpan 20% kosong supaya rakaman lancar")))
            // network
            val cmg = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cmg.getNetworkCapabilities(cmg.activeNetwork)
            val wifi = nc?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true; val cell = nc?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val down = (nc?.linkDownstreamBandwidthKbps ?: 0) / 1000
            res.add(Triple(if (nc == null) false else true, t("Network", "Rangkaian"), if (nc == null) t("No internet", "Tiada internet") else (if (wifi) "Wi-Fi" else if (cell) t("Mobile data", "Data mudah alih") else t("Connected", "Bersambung")) + (if (down > 0) " · ~$down Mbps" else "")))
            // signalling server
            val sig = try { val r = OkHttpClient().newCall(Request.Builder().url("https://0.peerjs.com/peerjs/id").build()).execute(); val ok = r.isSuccessful; r.close(); ok } catch (e: Exception) { false }
            res.add(Triple(sig, t("Connection server", "Pelayan sambungan"), if (sig) "OK" else t("Cannot reach. Check the internet.", "Tidak dapat dihubungi. Semak internet.")))
            // battery
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val lv = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); val chg = bm.isCharging
            res.add(Triple(if (chg || lv > 40) true else null, t("Battery", "Bateri"), "$lv%" + (if (chg) " ⚡" else "") + if (chg || lv > 40) "" else " · " + t("charge for long sessions", "cas untuk sesi panjang")))
            // memory
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager; val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            val ram = Math.round(mi.totalMem / 1_073_741_824.0)
            res.add(Triple(if (ram >= 4) true else null, "RAM", "$ram GB" + if (ram < 4) " · " + t("4 GB or more recommended for the Director", "4 GB ke atas disyorkan untuk Pengarah") else ""))
            main.post {
                if (isFinishing) return@post
                body.removeView(wait); res.forEach { (a, b, c) -> row(a, b, c) }
                body.addView(Ui.hint(this, t("Tip: same Wi-Fi, phones charging, 720p for long sessions.", "Tip: Wi-Fi sama, telefon dicas, 720p untuk sesi panjang.")))
                body.addView(Ui.btn(this, t("Test again", "Uji semula")) { run() }, Ui.lp(this, 12))
            }
        }.start()
    }
}

/* ---------------- Manual (the same booklet as the web, kept inside the app) ---------------- */
class ManualActivity : PageActivity() {
    override val title0 get() = "Manual"
    private var pg = 0
    private lateinit var pages: JSONArray
    private var pics = JSONObject()
    override fun build() {
        val j = try { JSONObject(assets.open("manual/manual.json").bufferedReader().readText()) } catch (e: Exception) { JSONObject() }
        pages = j.optJSONArray(if (Ui.ms) "ms" else "en") ?: JSONArray(); pics = j.optJSONObject("pics") ?: JSONObject()
        val nav = LinearLayout(this); nav.orientation = LinearLayout.HORIZONTAL
        nav.addView(Ui.btn(this, "‹ " + t("Back", "Kembali")) { if (pg > 0) { pg--; paint() } else finish() }, LinearLayout.LayoutParams(0, Ui.px(this, 52), 1f).apply { rightMargin = d / 2 })
        nav.addView(Ui.btn(this, t("Next", "Seterusnya") + " ›", true) { if (pg < pages.length() - 1) { pg++; paint() } else finish() }, LinearLayout.LayoutParams(0, Ui.px(this, 52), 1f))
        root.addView(nav, Ui.lp(this, 6))
        paint()
    }
    private fun paint() {
        body.removeAllViews()
        if (pages.length() == 0) { body.addView(Ui.label(this, t("Manual not found", "Manual tiada"), 15f)); return }
        val p = pages.getJSONObject(pg)
        body.addView(Ui.label(this, "${pg + 1} / ${pages.length()}", 12f, Ui.SUB))
        body.addView(Ui.label(this, p.optString("ic") + "  " + p.optString("t"), 20f, Ui.TEXT, true), Ui.lp(this, 4))
        pics.optJSONArray(pg.toString())?.let { arr ->
            for (i in 0 until arr.length()) { val a = arr.getJSONArray(i); val name = a.getString(0); val caps = a.getJSONArray(if (Ui.ms) 1 else 2)
                try { val bm = assets.open("manual/${name}_${if (Ui.ms) "ms" else "en"}.jpg").use { BitmapFactory.decodeStream(it) }
                    val iv = ImageView(this); iv.setImageBitmap(bm); iv.adjustViewBounds = true; iv.background = Ui.round(Ui.CARD, Ui.dp(this, 12f)); iv.clipToOutline = true
                    body.addView(iv, Ui.lp(this, 10)) } catch (e: Exception) {}
                val cp = StringBuilder(); for (k in 0 until caps.length()) cp.append("${k + 1}  ${caps.getString(k)}    ")
                body.addView(Ui.label(this, cp.toString().trim(), 13f, Ui.GOLD, true), Ui.lp(this, 4)) }
        }
        if (p.optBoolean("toc")) {
            for (i in 1 until pages.length()) { val q = pages.getJSONObject(i)
                body.addView(Ui.btn(this, q.optString("ic") + "  " + q.optString("t")) { pg = i; paint() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.lp(this, 6)) }
        } else p.optJSONArray("b")?.let { b ->
            for (i in 0 until b.length()) { val tv = TextView(this); tv.text = Html.fromHtml("•  " + b.getString(i), Html.FROM_HTML_MODE_LEGACY); tv.textSize = 15f; tv.setTextColor(Ui.TEXT); tv.setLineSpacing(0f, 1.25f)
                body.addView(tv, Ui.lp(this, 10)) }
        }
        (body.parent as? ScrollView)?.scrollTo(0, 0)
    }
}

/* ---------------- About ---------------- */
class AboutActivity : PageActivity() {
    override val title0 get() = t("About", "Tentang")
    override val scroll = false
    override fun build() {
        // logo + name in one row
        val hd = LinearLayout(this); hd.orientation = LinearLayout.HORIZONTAL; hd.gravity = Gravity.CENTER_VERTICAL
        val iv = ImageView(this); iv.setImageResource(R.drawable.ic_logo); hd.addView(iv, LinearLayout.LayoutParams(Ui.px(this, 60), Ui.px(this, 60)))
        val nm = LinearLayout(this); nm.orientation = LinearLayout.VERTICAL; nm.setPadding(d, 0, 0, 0)
        nm.addView(Ui.label(this, "ProMultiCam", 22f, Ui.TEXT, true))
        nm.addView(Ui.label(this, t("Many phones. One video.", "Banyak telefon. Satu video."), 14f, Ui.GOLD, true))
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        nm.addView(Ui.label(this, "App $v", 12f, Ui.SUB))
        hd.addView(nm); body.addView(hd, Ui.lp(this, 4))
        // what it does: 6 small boxes, 2 per row, sharing the space
        val items = listOf(
            "🎬" to t("Many phones, switch angles live", "Banyak telefon, tukar sudut langsung"),
            "🔗" to t("Join by Studio ID or QR", "Sertai guna ID Studio atau QR"),
            "🎨" to t("Looks, effects, logo & price", "Gaya, kesan, logo & harga"),
            "📝" to t("Teleprompter & title cards", "Teleprompter & kad tajuk"),
            "✂️" to t("Editor: cut, text, music", "Editor: potong, teks, muzik"),
            "🔒" to t("Videos stay on your phone", "Video kekal dalam telefon"))
        val grid = LinearLayout(this); grid.orientation = LinearLayout.VERTICAL
        for (r in 0 until 3) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            for (k in 0..1) { val (ic, txt) = items[r * 2 + k]
                val c = Ui.card(this); c.gravity = Gravity.CENTER
                c.addView(Ui.center(Ui.label(this, ic, 24f)))
                c.addView(Ui.center(Ui.label(this, txt, 13f, Ui.TEXT, true)), Ui.lp(this, 6))
                row.addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply { if (k == 0) rightMargin = d / 2 else leftMargin = d / 2 }) }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d })
        }
        body.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        // buttons side by side
        val bt = LinearLayout(this); bt.orientation = LinearLayout.HORIZONTAL
        bt.addView(Ui.btn(this, "Manual") { Nav.open(this, "manual") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = d / 2 })
        bt.addView(Ui.btn(this, t("Support", "Sokongan")) { Nav.open(this, "support") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d / 2 })
        body.addView(bt, Ui.lp(this, 14))
    }
}

/* ---------------- App Update: this version, check for a newer one, update ---------------- */
class ApkActivity : PageActivity() {
    override val title0 get() = "App Update"
    private val VJ = "https://promulticam.web.app/apk/version.json"
    private val APK = "https://promulticam.web.app/apk/ProMultiCam.apk"
    override fun build() {
        val pi = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        val mine = pi?.versionName ?: ""; val code = pi?.longVersionCode ?: 0
        val c = Ui.card(this)
        c.addView(Ui.label(this, t("This app", "App ini"), 13f, Ui.SUB, true))
        c.addView(Ui.label(this, "ProMultiCam $mine", 20f, Ui.TEXT, true), Ui.lp(this, 4))
        val st = Ui.label(this, t("Checking for updates…", "Menyemak versi baru…"), 14f, Ui.GOLD, true); c.addView(st, Ui.lp(this, 8))
        body.addView(c)
        up = Ui.btn(this, t("Download update", "Muat turun versi baru"), true) { download() }; up.visibility = View.GONE
        body.addView(up, Ui.lp(this, 12))
        // download progress
        prog = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); prog.max = 100; prog.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); prog.visibility = View.GONE
        body.addView(prog, LinearLayout.LayoutParams(-1, Ui.px(this, 14)).apply { topMargin = Ui.px(this@ApkActivity, 14) })
        pTx = Ui.label(this, "", 14f, Ui.TEXT, true); body.addView(pTx, Ui.lp(this, 6))
        inst = Ui.btn(this, t("Install now", "Pasang sekarang"), true) { install() }; inst.visibility = View.GONE
        body.addView(inst, Ui.lp(this, 12))
        body.addView(Ui.hint(this, t("Your videos stay after the update.", "Video anda kekal selepas kemas kini.")))
        bg {
            val j = OkHttpClient().newCall(Request.Builder().url("$VJ?t=" + System.currentTimeMillis()).build()).execute().use { JSONObject(it.body?.string() ?: "{}") }
            main.post {
                if (isFinishing) return@post
                val n = j.optLong("code"); val v = j.optString("v"); val mb = j.optString("mb"); val dt = j.optString("date")
                newCode = n
                if (n > code) { st.text = t("New version $v ($mb MB, $dt)", "Versi baru $v ($mb MB, $dt)")
                    // not downloading yet (or an older file): start it now, no button needed
                    if (dlId < 0 || getSharedPreferences("pmc", Context.MODE_PRIVATE).getLong("apkDlCode", 0) < n) download() }
                else st.text = t("You have the latest version.", "Anda guna versi terkini.")
            }
        }
        // a download that is still going (page opened again)
        val sp = getSharedPreferences("pmc", Context.MODE_PRIVATE); dlId = sp.getLong("apkDl", -1L)
        autoInst = intent.getBooleanExtra("install", false)   // opened from "tap to install"
        if (dlId >= 0) watch()
    }
    private lateinit var up: android.widget.Button; private lateinit var inst: android.widget.Button
    private lateinit var prog: android.widget.ProgressBar; private lateinit var pTx: TextView
    private var dlId = -1L; private var alive = true; private var newCode = 0L; private var autoInst = false
    override fun onDestroy() { alive = false; super.onDestroy() }
    private fun dm() = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
    private fun download() {
        try {
            dlId = Notifier.fetchApk(this, newCode)
            up.visibility = View.GONE; watch()
        } catch (e: Exception) { toast(t("Could not download", "Gagal muat turun")) }
    }
    /* show how far the download is, every half second */
    private fun watch() {
        if (!alive || dlId < 0) return
        prog.visibility = View.VISIBLE
        var status = -1; var got = 0L; var tot = 0L
        try { dm().query(DownloadManager.Query().setFilterById(dlId))?.use { c -> if (c.moveToFirst()) {
            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)); tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)) } } } catch (e: Exception) {}
        val mb = { b: Long -> String.format(Locale.US, "%.1f", b / 1048576.0) }
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> { prog.progress = 100; pTx.text = "✅ " + t("Downloaded. Tap Install now.", "Siap dimuat turun. Tekan Pasang sekarang."); inst.visibility = View.VISIBLE
                if (autoInst) { autoInst = false; install() }; return }
            DownloadManager.STATUS_FAILED, -1 -> { prog.visibility = View.GONE; pTx.text = t("Download failed. Try again.", "Muat turun gagal. Cuba lagi."); up.visibility = View.VISIBLE
                getSharedPreferences("pmc", Context.MODE_PRIVATE).edit().remove("apkDl").apply(); dlId = -1; return }
            DownloadManager.STATUS_PAUSED -> pTx.text = t("Waiting for the internet…", "Menunggu internet…")
            else -> { val pc = if (tot > 0) (got * 100 / tot).toInt() else 0; prog.isIndeterminate = tot <= 0; prog.progress = pc
                pTx.text = t("Downloading… ", "Sedang muat turun… ") + (if (tot > 0) "$pc%  (${mb(got)} / ${mb(tot)} MB)" else mb(got) + " MB") }
        }
        main.postDelayed({ watch() }, 500)
    }
    private fun install() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast(t("Allow installing from this app, then come back and tap Install now.", "Benarkan pasang dari app ini, kemudian kembali dan tekan Pasang sekarang."))
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
            return
        }
        val f = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ProMultiCam.apk")
        val u = try { Store.uri(this, f) } catch (e: Exception) { null } ?: try { dm().getUriForDownloadedFile(dlId) } catch (e: Exception) { null }
        if (u == null) { toast(t("File not found. Download again.", "Fail tiada. Muat turun semula.")); return }
        getSharedPreferences("pmc", Context.MODE_PRIVATE).edit().remove("apkDl").apply()
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast(t("Could not open the installer", "Gagal buka pemasang")) }
    }
}
