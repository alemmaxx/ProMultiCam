package com.promulticam.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.promulticam.app.Ui.t
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Director: gathers every camera (this phone + camera phones, web or native), one tap picks the angle,
   and the program (style, layout, switch effect, brand) is made on the GPU and recorded by the hardware encoder. */
class DirectorActivity : AppCompatActivity(), PeerSignal.Listener, PeerLink.Cb {
    private class Src(val id: String, var name: String, val local: Boolean) {
        var track: VideoTrack? = null
        var tile: FrameLayout? = null
        var tileView: SurfaceViewRenderer? = null
        var data: PeerLink? = null
        var media: PeerLink? = null
        var zoom = 1f
    }
    private val main = Handler(Looper.getMainLooper())
    private val sp by lazy { getSharedPreferences("pmc", MODE_PRIVATE) }
    private lateinit var brand: Brand
    private lateinit var prog: Program
    private lateinit var tiles: LinearLayout
    private lateinit var timeTx: TextView
    private lateinit var fpsTx: TextView
    private lateinit var recBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var qBtn: Button
    private lateinit var lookBtn: Button
    private lateinit var layBtn: Button
    private lateinit var trBtn: Button
    private lateinit var zoomBar: SeekBar
    private var resumeUri: Uri? = null
    private val srcs = ArrayList<Src>()
    private val links = HashMap<String, PeerLink>()
    private var mainSrc: Src? = null
    private var secSrc: Src? = null
    private var sig: PeerSignal? = null
    private var code = ""
    private var cam: Rtc.Cam? = null
    private var enc: Encoder? = null
    private var recUri: Uri? = null
    private var recFile: File? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recStart = 0L
    private var pausedMs = 0L
    private var pauseAt = 0L
    private var lastUri: Uri? = null
    private var stopped = false
    private var ending = false
    private var look = Looks.byId(null)

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) startAll() else { toast(t("Camera permission is needed", "Perlu izin kamera")); finish() }
    }
    private val pickLogo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) try {
            contentResolver.openInputStream(uri)?.use { i -> brand.logoFile.outputStream().use { i.copyTo(it) } }
            brand.refresh(); refreshOverlay()
        } catch (e: Exception) { toast(t("Could not load the logo", "Gagal muat logo")) }
    }

    private val land get() = sp.getString("fmt", "p") == "l"
    private val W get() = if (land) (if (brand.hd) 1920 else 1280) else (if (brand.hd) 1080 else 720)
    private val H get() = if (land) (if (brand.hd) 1080 else 720) else (if (brand.hd) 1920 else 1280)
    private var layout: String get() = sp.getString("layout", "full") ?: "full"; set(v) { sp.edit().putString("layout", v).apply() }
    private var trans: String get() = sp.getString("trans", "fade") ?: "fade"; set(v) { sp.edit().putString("trans", v).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Ui.init(this); Auth.init(this); Rtc.init(this)
        brand = Brand(this); brand.refresh()
        look = Looks.byId(sp.getString("look", "superior"))
        resumeUri = intent.getStringExtra("resume")?.let { Uri.parse(it) }
        prog = Program(W, H); prog.setLook(look.f); prog.layout = layout; prog.trans = trans
        setContentView(buildUi())
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startAll() else perms.launch(need)
    }

    override fun onDestroy() {
        super.onDestroy(); stopped = true; main.removeCallbacksAndMessages(null)
        enc?.let { e -> prog.detachEncoder { e.stop { } } }
        links.values.toList().forEach { it.close() }; sig?.close()
        srcs.forEach { s -> s.track?.let { t -> try { t.removeSink(prog.mainSink); t.removeSink(prog.secSink) } catch (e: Exception) {} }; s.tileView?.let { v -> s.track?.removeSink(v); v.release() } }
        cam?.stop(); prog.release()
    }

    /* ---------- screen (same layout as the web Director) ---------- */
    private lateinit var srcChip: TextView
    private lateinit var lookChip: TextView
    private lateinit var camsTx: TextView
    private lateinit var zoomTx: TextView
    private lateinit var countTx: TextView

    private fun buildUi(): View {
        val c = this; val d = Ui.px(c, 10)
        val root = LinearLayout(c); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Ui.BG)
        // top bar: back · name + cameras live · videos · studio · style · settings
        val top = LinearLayout(c); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(d, d * 2, d, d)
        val ib = { ic: Int, f: () -> Unit -> Ui.iconBtn(c, ic) { f() } }
        top.addView(ib(R.drawable.ic_back) { finish() }, LinearLayout.LayoutParams(Ui.px(c, 46), Ui.px(c, 46)))
        val tc = LinearLayout(c); tc.orientation = LinearLayout.VERTICAL; tc.setPadding(d, 0, d / 2, 0)
        val nm = sp.getString("devName", "")?.takeIf { it.isNotEmpty() } ?: "ProMultiCam"
        tc.addView(Ui.label(c, nm, 18f, Ui.TEXT, true).apply { maxLines = 1 })
        camsTx = Ui.label(c, "", 12f, 0xFF6FCF97.toInt(), true); tc.addView(camsTx)
        top.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
        for ((ic, f) in listOf<Pair<Int, () -> Unit>>(R.drawable.ic_video to { startActivity(Intent(c, VideosActivity::class.java)) },
            R.drawable.ic_studio to { studioSheet() }, R.drawable.ic_palette to { styleSheet() }, R.drawable.ic_sliders to { settingsSheet() }))
            top.addView(ib(ic, f), LinearLayout.LayoutParams(Ui.px(c, 46), Ui.px(c, 46)).apply { leftMargin = Ui.px(c, 5) })
        root.addView(top)

        // the program picture
        val stage = FrameLayout(c); stage.setBackgroundColor(Color.BLACK)
        val sv = SurfaceView(c)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { prog.setPreview(h.surface, w, hh) }
            override fun surfaceDestroyed(h: SurfaceHolder) { prog.setPreview(null, 0, 0) }
        })
        stage.addView(sv, Ui.fl(-1, -1))
        val chips = LinearLayout(c); chips.orientation = LinearLayout.HORIZONTAL
        srcChip = Ui.label(c, "", 13f, Ui.GOLD, true); srcChip.background = Ui.round(0xB0000000.toInt(), Ui.dp(c, 99f)); srcChip.setPadding(d, d / 2, d, d / 2)
        lookChip = Ui.label(c, "", 13f, Ui.TEXT, true); lookChip.background = Ui.round(0xB0000000.toInt(), Ui.dp(c, 99f)); lookChip.setPadding(d, d / 2, d, d / 2)
        lookChip.setOnClickListener { styleSheet() }
        chips.addView(srcChip); chips.addView(lookChip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        stage.addView(chips, Ui.fl(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = d; topMargin = d })
        timeTx = Ui.label(c, "", 13f, Color.WHITE, true); timeTx.setPadding(d, d / 3, d, d / 3); timeTx.background = Ui.round(Ui.RED, Ui.dp(c, 99f)); timeTx.visibility = View.GONE
        stage.addView(timeTx, Ui.fl(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.px(c, 46) })
        fpsTx = Ui.label(c, "", 11f, Color.WHITE, true); fpsTx.setPadding(d / 2, d / 4, d / 2, d / 4); fpsTx.background = Ui.round(0x88000000.toInt(), Ui.dp(c, 8f))
        stage.addView(fpsTx, Ui.fl(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = d; topMargin = d })
        // zoom: upright slider on the right, like the web
        zoomTx = Ui.label(c, "1.0×", 13f, Ui.GOLD, true)
        stage.addView(zoomTx, Ui.fl(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = d; bottomMargin = Ui.px(c, 150) })
        zoomBar = SeekBar(c); zoomBar.max = 100; zoomBar.rotation = 270f
        zoomBar.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); zoomBar.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, user: Boolean) { val z = 1f + p / 100f * 3f; zoomTx.text = String.format(Locale.US, "%.1f×", z); if (!user) return; mainSrc?.zoom = z; prog.zoomMain = z }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        stage.addView(zoomBar, Ui.fl(Ui.px(c, 200), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = -Ui.px(c, 70) })
        countTx = Ui.label(c, "", 96f, Color.WHITE, true); countTx.visibility = View.GONE; countTx.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        stage.addView(countTx, Ui.fl(-2, -2, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // camera tiles + "add camera"
        val hs = HorizontalScrollView(c); hs.isHorizontalScrollBarEnabled = false
        tiles = LinearLayout(c); tiles.orientation = LinearLayout.HORIZONTAL; tiles.setPadding(d, d, d, 0)
        hs.addView(tiles)
        root.addView(hs, LinearLayout.LayoutParams(-1, Ui.px(c, 108)))
        addTile = LinearLayout(c); addTile.orientation = LinearLayout.VERTICAL; addTile.gravity = Gravity.CENTER
        addTile.background = Ui.round(0xFF15140F.toInt(), Ui.dp(c, 12f), Ui.GOLD, Ui.px(c, 1))
        addTile.addView(Ui.label(c, "+", 24f, Ui.GOLD, true)); addTile.addView(Ui.center(Ui.label(c, t("Camera", "Kamera"), 11f, Ui.GOLD, true)))
        addTile.setOnClickListener { connectSheet() }
        tiles.addView(addTile, LinearLayout.LayoutParams(Ui.px(c, 60), -1))

        val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL; row.setPadding(d, d, d, d * 2)
        pauseBtn = Ui.btn(c, "‖ " + t("Pause", "Jeda")) { togglePause() }; pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f
        recBtn = Ui.btn(c, "● " + t("Start Recording", "Mula Rakam"), true) { if (enc == null) countdown() else stopRec() }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, Ui.px(c, 56), 1f))
        row.addView(recBtn, LinearLayout.LayoutParams(0, Ui.px(c, 56), 2f).apply { leftMargin = d })
        root.addView(row)
        qBtn = Ui.btn(c, "") {}   // quality lives in Settings
        lookBtn = Ui.btn(c, "") {}; layBtn = Ui.btn(c, "") {}; trBtn = Ui.btn(c, "") {}
        paintChips(); fpsLoop()
        return root
    }
    private lateinit var addTile: LinearLayout
    private var lastSwitch = 0L
    private fun fpsLoop() {
        fpsTx.text = "${prog.fps} fps"
        val n = sp.getInt("auto", 0)
        if (n > 0 && System.currentTimeMillis() - lastSwitch >= n * 1000L) {
            val l = srcs.filter { it.track != null }
            if (l.size >= 2) { val i = l.indexOf(mainSrc); lastSwitch = System.currentTimeMillis(); setMain(l[(i + 1) % l.size]) }
        }
        main.postDelayed({ if (!stopped) fpsLoop() }, 1000)
    }
    private fun sendFmt() { srcs.forEach { it.data?.send(mapOf("t" to "fmt", "v" to if (land) "l" else "p")) }; refreshOverlay() }
    private fun paintChips() { srcChip.text = mainSrc?.name ?: t("Director phone", "Telefon Pengarah"); lookChip.text = lookName(look) }

    private fun lookName(l: Looks.Look) = if (Ui.ms) l.ms else l.en
    private fun needPro(): Boolean { if (Auth.isPro) return false; toast(t("Pro feature", "Ciri Pro")); WebActivity.open(this, "pro"); return true }

    /* 3-2-1 before recording (Settings) */
    private fun countdown() {
        if (mainSrc?.track == null) { toast(t("No camera yet", "Belum ada kamera")); return }
        if (!sp.getBoolean("count", true)) { startRec(); return }
        recBtn.isEnabled = false; countTx.visibility = View.VISIBLE
        fun step(n: Int) { if (n == 0) { countTx.visibility = View.GONE; recBtn.isEnabled = true; startRec(); return }; countTx.text = n.toString(); main.postDelayed({ step(n - 1) }, 1000) }
        step(3)
    }

    /* ---------- the panels (same as the web sheets) ---------- */
    private fun styleSheet() {
        val s = Ui.sheet(this, t("Visual style", "Gaya Visual"))
        val grid = android.widget.GridLayout(this); grid.columnCount = 3
        val pills = ArrayList<Button>()
        Looks.ALL.forEachIndexed { i, l ->
            val b = Ui.pill(this, lookName(l), l === look) {
                if (l.id != "std" && !Auth.isPro) { needPro(); return@pill }
                look = l; sp.edit().putString("look", l.id).apply(); prog.setLook(l.f); refreshOverlay(); paintChips()
                pills.forEachIndexed { j, p -> Ui.setPill(this, p, Looks.ALL[j] === look) }
            }
            pills.add(b)
            val p = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / 3), android.widget.GridLayout.spec(i % 3, 1f)); p.width = 0; p.setMargins(4, 6, 4, 6)
            grid.addView(b, p)
        }
        s.body.addView(grid)
        s.body.addView(Ui.section(this, t("Layout", "Susun atur")))
        val lay = LinearLayout(this); lay.orientation = LinearLayout.HORIZONTAL
        val lb = ArrayList<Button>()
        for ((id, name) in listOf("full" to t("Full", "Penuh"), "split" to t("Split", "Belah"), "pip" to t("Small frame", "Bingkai kecil"))) {
            val b = Ui.pill(this, name, layout == id) {
                if (id != "full" && !Auth.isPro) { needPro(); return@pill }
                if (id != "full" && srcs.count { it.track != null } < 2) { toast(t("Needs 2 cameras. Connect another phone.", "Perlu 2 kamera. Sambung telefon lain.")); return@pill }
                layout = id; prog.layout = id; pickSecond(); lb.forEachIndexed { j, p -> Ui.setPill(this, p, listOf("full", "split", "pip")[j] == layout) }
            }
            lb.add(b); lay.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
        }
        s.body.addView(lay, Ui.lp(this, 6))
        s.body.addView(Ui.section(this, t("Switch effect", "Kesan tukar")))
        val tr = LinearLayout(this); tr.orientation = LinearLayout.HORIZONTAL
        val tb = ArrayList<Button>()
        for ((id, name) in listOf("fade" to t("Soft fade", "Lembut"), "black" to t("Dip to black", "Gelap"), "cut" to t("Straight cut", "Terus"))) {
            val b = Ui.pill(this, name, trans == id) { trans = id; prog.trans = id; tb.forEachIndexed { j, p -> Ui.setPill(this, p, listOf("fade", "black", "cut")[j] == trans) } }
            tb.add(b); tr.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
        }
        s.body.addView(tr, Ui.lp(this, 6))
        s.dialog.show()
    }

    private fun settingsSheet() {
        val s = Ui.sheet(this, t("Recording settings", "Tetapan Rakaman"), t("Save settings", "Simpan Tetapan") to { refreshOverlay(); paintChips() })
        val b = s.body
        b.addView(Ui.label(this, t("Video format", "Format video"), 13f, Ui.SUB, true))
        lateinit var fp: Button; lateinit var fl: Button
        fp = Ui.pill(this, t("Vertical 9:16 · Short video & Story", "Menegak 9:16 · Video Pendek & Story"), !land) { if (enc != null) return@pill; sp.edit().putString("fmt", "p").apply(); prog.resize(W, H); Ui.setPill(this, fp, true); Ui.setPill(this, fl, false); sendFmt() }
        fl = Ui.pill(this, t("Horizontal 16:9 · Widescreen", "Melintang 16:9 · Skrin lebar"), land) { if (enc != null) return@pill; sp.edit().putString("fmt", "l").apply(); prog.resize(W, H); Ui.setPill(this, fp, false); Ui.setPill(this, fl, true); sendFmt() }
        b.addView(fp, Ui.lp(this, 6)); b.addView(fl, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Quality", "Kualiti"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val qr = LinearLayout(this); qr.orientation = LinearLayout.HORIZONTAL
        lateinit var q7: Button; lateinit var q10: Button
        q7 = Ui.pill(this, "720p", !brand.hd) { if (enc == null) { brand.hd = false; prog.resize(W, H); Ui.setPill(this, q7, true); Ui.setPill(this, q10, false) } }
        q10 = Ui.pill(this, "1080p", brand.hd) { if (enc != null) return@pill; if (!Auth.isPro) { needPro(); return@pill }; brand.hd = true; prog.resize(W, H); Ui.setPill(this, q7, false); Ui.setPill(this, q10, true) }
        qr.addView(q7, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 }); qr.addView(q10, LinearLayout.LayoutParams(0, -2, 1f))
        b.addView(qr, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Title (optional)", "Tajuk (pilihan)"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val ti = Ui.input(this, t("e.g. Nasi Lemak Sambal Sotong", "cth: Nasi Lemak Sambal Sotong"), brand.title); b.addView(ti, Ui.lp(this, 6))
        b.addView(Ui.hr(this))
        b.addView(Ui.section(this, t("Brand", "Jenama")))
        val lr = LinearLayout(this); lr.orientation = LinearLayout.HORIZONTAL; lr.gravity = Gravity.CENTER_VERTICAL
        val lv = android.widget.ImageView(this); lv.background = Ui.round(0xFF121110.toInt(), Ui.dp(this, 12f), Ui.LINE, 2); lv.setImageBitmap(brand.logo); lv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        lr.addView(lv, LinearLayout.LayoutParams(Ui.px(this, 54), Ui.px(this, 54)))
        lr.addView(Ui.btn(this, t("Upload logo", "Muat naik logo")) { if (!needPro()) pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12 })
        lr.addView(Ui.dangerBtn(this, t("Remove", "Buang")) { brand.logoFile.delete(); brand.refresh(); lv.setImageBitmap(null); refreshOverlay() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8 })
        b.addView(lr, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Price / tagline (optional)", "Harga / slogan (pilihan)"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val pr = Ui.input(this, t("e.g. RM12.90 or Buy 2 Free 1", "cth: RM12.90 atau Beli 2 Percuma 1"), brand.price); b.addView(pr, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Phone / contact (optional)", "Telefon / hubungi (pilihan)"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val ph = Ui.input(this, t("e.g. 012-345 6789 or Order via the bio link", "cth: 012-345 6789 atau Tempah di pautan bio"), brand.phone); b.addView(ph, Ui.lp(this, 6))
        b.addView(Ui.check(this, t("Brand in the video", "Jenama dalam video"), brand.on) { v -> brand.on = v }, Ui.lp(this, 8))
        b.addView(Ui.hr(this))
        b.addView(Ui.check(this, t("Use this phone's camera", "Guna kamera telefon ini"), sp.getBoolean("selfCam", true)) { v -> sp.edit().putBoolean("selfCam", v).apply(); toast(t("Applies next time you open the Director", "Berkuat kuasa bila buka Pengarah semula")) })
        b.addView(Ui.check(this, t("3s countdown", "Kiraan 3 saat"), sp.getBoolean("count", true)) { v -> sp.edit().putBoolean("count", v).apply() })
        b.addView(Ui.label(this, t("Auto-switch angles", "Tukar sudut automatik"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val ar = LinearLayout(this); ar.orientation = LinearLayout.HORIZONTAL; val ap = ArrayList<Button>()
        for (n in listOf(0, 5, 10, 15)) { val pb = Ui.pill(this, if (n == 0) t("Off", "Tutup") else "${n}s", sp.getInt("auto", 0) == n) {
                if (n > 0 && !Auth.isPro) { needPro(); return@pill }
                sp.edit().putInt("auto", n).apply(); ap.forEachIndexed { j, q -> Ui.setPill(this, q, listOf(0, 5, 10, 15)[j] == n) }; lastSwitch = System.currentTimeMillis() }
            ap.add(pb); ar.addView(pb, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 }) }
        b.addView(ar, Ui.lp(this, 6))
        ti.addTextChangedListener(watch { brand.title = it; brand.refresh() }); pr.addTextChangedListener(watch { brand.price = it; brand.refresh() }); ph.addTextChangedListener(watch { brand.phone = it; brand.refresh() })
        s.dialog.setOnDismissListener { refreshOverlay(); paintChips() }
        s.dialog.show()
    }
    private fun watch(f: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { f(s?.toString() ?: "") }
    }

    private fun connectSheet() {
        val s = Ui.sheet(this, t("Live cameras", "Kamera Langsung"))
        val b = s.body
        b.addView(Ui.label(this, t("Studio ID", "ID Studio"), 13f, Ui.SUB, true))
        b.addView(Ui.label(this, code.chunked(3).joinToString(" "), 40f, Ui.GOLD, true), Ui.lp(this, 4))
        val url = "https://promulticam.web.app/?join=$code"
        try {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600)
            val bmp = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until 600) for (y in 0 until 600) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
            val iv = android.widget.ImageView(this); iv.setImageBitmap(bmp); iv.background = Ui.round(Color.WHITE, Ui.dp(this, 14f)); iv.setPadding(16, 16, 16, 16)
            b.addView(iv, LinearLayout.LayoutParams(Ui.px(this, 220), Ui.px(this, 220)).apply { topMargin = Ui.px(this@DirectorActivity, 10); gravity = Gravity.CENTER_HORIZONTAL })
        } catch (e: Exception) {}
        b.addView(Ui.hint(this, t("Camera phones type this ID or scan the QR.", "Telefon Kamera taip ID ini atau imbas QR.")))
        b.addView(Ui.btn(this, t("Share link", "Kongsi pautan")) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t("Join my ProMultiCam studio as a camera (code $code): ", "Sertai studio ProMultiCam saya sebagai kamera (kod $code): ") + url), t("Share", "Kongsi")))
        }, Ui.lp(this, 12))
        b.addView(Ui.section(this, t("Connected", "Bersambung")))
        srcs.forEach { x -> b.addView(Ui.label(this, "• " + x.name + if (x === mainSrc) "  ★" else "", 15f, Ui.TEXT), Ui.lp(this, 4)) }
        s.dialog.show()
    }

    private fun studioSheet() {
        if (needPro()) return
        studioDialog()
    }

    private fun refreshOverlay() { prog.setOverlay(brand.render(W, H, look)) }

    /* ---------- Studio: title & closing cards, teleprompter ---------- */
    private fun studioDialog() {
        val sh = Ui.sheet(this, "Studio Pro")
        val col = sh.body
        col.addView(Ui.section(this, t("Title & closing cards", "Kad tajuk & penutup")))
        col.addView(Ui.check(this, t("Title card (3s)", "Kad tajuk (3s)"), sp.getBoolean("intro", false)) { v -> sp.edit().putBoolean("intro", v).apply() })
        col.addView(Ui.check(this, t("Closing card (3s)", "Kad penutup (3s)"), sp.getBoolean("outro", false)) { v -> sp.edit().putBoolean("outro", v).apply() })
        val ot = Ui.input(this, t("Closing text, e.g. Order now!", "Teks penutup, cth. Tempah sekarang!"), sp.getString("outroTxt", "") ?: ""); col.addView(ot, Ui.lp(this, 6))
        ot.addTextChangedListener(watch { sp.edit().putString("outroTxt", it).apply() })
        col.addView(Ui.hr(this))
        col.addView(Ui.section(this, "Teleprompter"))
        col.addView(Ui.hint(this, t("Camera phones only.", "Telefon Kamera sahaja.")))
        val tp = Ui.input(this, t("Your script…", "Skrip anda…"), sp.getString("tpText", "") ?: ""); tp.minLines = 4; tp.gravity = Gravity.TOP; col.addView(tp, Ui.lp(this, 6))
        col.addView(Ui.label(this, t("Speed", "Laju"), 13f, Ui.SUB, true), Ui.lp(this, 10))
        val spd = SeekBar(this); spd.max = 9; spd.progress = sp.getInt("tpSpeed", 4) - 1; spd.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); spd.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); col.addView(spd)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        fun tpSend(cmd: String) {
            sp.edit().putString("tpText", tp.text.toString()).putInt("tpSpeed", spd.progress + 1).apply()
            val m = mapOf("t" to "tp", "cmd" to cmd, "text" to tp.text.toString(), "speed" to (spd.progress + 1), "size" to 34)
            srcs.forEach { it.data?.send(m) }
            if (srcs.none { it.data != null }) toast(t("Connect a camera phone first", "Sambung telefon Kamera dahulu"))
        }
        for ((l, cmd) in listOf(t("Start", "Mula") to "start", t("Pause", "Jeda") to "pause", t("From top", "Dari atas") to "reset"))
            row.addView(Ui.btn(this, l, cmd == "start") { tpSend(cmd) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
        col.addView(row, Ui.lp(this, 8))
        col.addView(Ui.btn(this, t("Hide teleprompter", "Sorok teleprompter")) { tpSend("hide") }, Ui.lp(this, 8))
        sh.dialog.show()
    }

    /* ---------- cameras ---------- */
    private fun startAll() {
        refreshOverlay()
        cam = if (sp.getBoolean("selfCam", true)) Rtc.camera(this, false, 1920, 1080, "dir") else null
        cam?.let { c -> val s = Src("local", t("Director", "Pengarah"), true); s.track = c.track; addSrc(s) }
        openStudio()
    }

    private fun openStudio() {
        code = (100000 + (Math.random() * 900000).toInt()).toString()
        paintCode()
        sig?.close(); sig = PeerSignal("pmc2-code-$code", this).also { it.connect() }
    }
    private fun paintCode() { if (::camsTx.isInitialized) camsTx.text = srcs.count { it.track != null }.toString() + " " + t("Cameras live", "Kamera langsung") + "  ·  ID " + code }
    override fun onOpen(id: String) { paintCode() }
    override fun onIdTaken() { openStudio() }
    override fun onClosed() { if (!stopped) main.postDelayed({ if (!stopped) { sig?.close(); sig = PeerSignal("pmc2-code-$code", this).also { it.connect() } } }, 3000) }

    override fun onMsg(type: String, src: String, payload: JSONObject) {
        val cid = payload.optString("connectionId")
        when (type) {
            "OFFER" -> {
                val ty = payload.optString("type")
                val limit = if (Auth.isPro) 6 else 1
                if (srcs.count { !it.local } >= limit && srcs.none { it.id == src }) { if (ty == "data") main.post { toast(t("Camera limit reached. Pro allows up to 6.", "Had kamera dicapai. Pro sehingga 6.")) }; return }
                val l = PeerLink(sig ?: return, src, cid, ty, this); links[cid] = l
                if (ty == "media") { val s = srcFor(src); s.media = l; payload.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { s.name = it; label(s) } }
                l.answer(payload)
            }
            "ANSWER" -> links[cid]?.gotAnswer(payload)
            "CANDIDATE" -> links[cid]?.gotCandidate(payload)
            "LEAVE", "EXPIRE" -> removeSrc(src)
        }
    }

    private fun srcFor(id: String): Src = srcs.firstOrNull { it.id == id } ?: Src(id, t("Camera ", "Kamera ") + srcs.size, false).also { addSrc(it) }

    override fun onOpen(link: PeerLink) { if (link.type == "data") srcFor(link.peer).data = link }
    override fun onData(link: PeerLink, msg: Map<String, Any?>) {
        val s = srcFor(link.peer)
        when (msg["t"]) {
            "hello" -> {
                (msg["name"] as? String)?.takeIf { it.isNotEmpty() }?.let { s.name = it; label(s) }
                s.data = link
                link.send(mapOf("t" to "welcome", "name" to t("Director", "Pengarah")))
                link.send(mapOf("t" to "fmt", "v" to if (land) "l" else "p"))
                link.send(mapOf("t" to "q", "hi" to (enc != null)))
                sendLive(); toast(s.name + t(" joined", " menyertai"))
            }
            "probe" -> link.send(mapOf("t" to "info", "name" to t("Director", "Pengarah"), "cams" to srcs.size, "code" to code))
            "stat" -> { val b = (msg["batt"] as? Number)?.toInt() ?: -1; if (b in 0..15 && msg["charging"] != true) toast(s.name + ": " + t("battery ", "bateri ") + b + "%") }
        }
    }
    override fun onTrack(link: PeerLink, track: MediaStreamTrack) {
        if (track is AudioTrack) { track.setEnabled(false); return }   // no echo: the Director records its own mic
        if (track !is VideoTrack) return
        val s = srcFor(link.peer); s.track = track
        s.tileView?.let { track.addSink(it) }
        if (mainSrc == null) setMain(s) else pickSecond()
        paintCode()
    }
    override fun onClosed(link: PeerLink) {
        links.remove(link.connectionId)
        val s = srcs.firstOrNull { it.id == link.peer } ?: return
        if (link === s.media || link === s.data) removeSrc(s.id)
    }

    private fun addSrc(s: Src) {
        srcs.add(s)
        val d = Ui.dp(this, 6f).toInt()
        val f = FrameLayout(this); f.background = Ui.round(Ui.CARD, Ui.dp(this, 10f)); f.setPadding(d / 2, d / 2, d / 2, d / 2)
        val v = SurfaceViewRenderer(this); v.init(Rtc.egl.eglBaseContext, null); v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        v.setZOrderMediaOverlay(true)
        f.addView(v, FrameLayout.LayoutParams(-1, -1))
        val tx = Ui.label(this, s.name, 11f, Color.WHITE, true); tx.setShadowLayer(4f, 0f, 1f, Color.BLACK); tx.tag = "lbl"
        f.addView(tx, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = d; bottomMargin = d / 2 })
        f.setOnClickListener { tap(s) }; v.setOnClickListener { tap(s) }
        tiles.addView(f, maxOf(0, tiles.childCount - 1), LinearLayout.LayoutParams(Ui.dp(this, 60f).toInt(), -1).apply { rightMargin = d })
        s.tile = f; s.tileView = v
        s.track?.addSink(v)
        if (mainSrc == null && s.track != null) setMain(s)
        paintTiles(); paintCode()
    }
    private fun label(s: Src) { (s.tile?.findViewWithTag<TextView>("lbl"))?.text = s.name }

    /* full: tap = main angle. split / small frame: tap another = second view, tap the main one = swap */
    private fun tap(s: Src) {
        lastSwitch = System.currentTimeMillis()
        if (layout == "full" || s.track == null) { setMain(s); return }
        if (s === mainSrc) { val o = secSrc; if (o != null) { setSecond(null); setMain(o); setSecond(s) } }
        else setSecond(s)
    }

    private fun removeSrc(id: String) {
        val s = srcs.firstOrNull { it.id == id } ?: return
        if (s.local) return
        srcs.remove(s)
        s.data?.close(); s.media?.close()
        s.tileView?.let { v -> s.track?.removeSink(v); v.release() }
        tiles.removeView(s.tile)
        if (secSrc === s) setSecond(null)
        if (mainSrc === s) { s.track?.let { tr -> try { tr.removeSink(prog.mainSink) } catch (e: Exception) {} }; mainSrc = null; srcs.firstOrNull { it.track != null }?.let { setMain(it) } }
        pickSecond(); toast(s.name + t(" left", " keluar")); paintCode()
    }

    private fun setMain(s: Src) {
        val tr = s.track ?: return
        val old = mainSrc
        if (old === s) { paintTiles(); return }
        if (secSrc === s) setSecond(null)
        old?.track?.let { o -> try { o.removeSink(prog.mainSink) } catch (e: Exception) {} }
        mainSrc = s
        if (old != null) prog.switched()
        prog.zoomMain = s.zoom; zoomBar.progress = ((s.zoom - 1f) / 3f * 100).toInt()
        tr.addSink(prog.mainSink)
        pickSecond(); paintTiles(); sendLive(); paintChips()
    }
    private fun setSecond(s: Src?) {
        secSrc?.track?.let { o -> try { o.removeSink(prog.secSink) } catch (e: Exception) {} }
        secSrc = s; prog.clearSecond(); prog.zoomSec = s?.zoom ?: 1f
        s?.track?.addSink(prog.secSink)
        paintTiles()
    }
    private fun pickSecond() {
        if (layout == "full") { if (secSrc != null) setSecond(null); return }
        if (secSrc == null || secSrc === mainSrc || secSrc?.track == null) setSecond(srcs.firstOrNull { it !== mainSrc && it.track != null })
    }
    private fun paintTiles() { srcs.forEach { s -> s.tile?.background = Ui.round(if (s === mainSrc) Ui.GOLD else if (s === secSrc) 0xFF7FB3FF.toInt() else Ui.CARD, Ui.dp(this, 10f)) } }
    private fun sendLive() { srcs.forEach { s -> s.data?.send(mapOf("t" to "live", "on" to ((s === mainSrc || s === secSrc) && enc != null))) } }

    /* ---------- recording ---------- */
    private fun startRec() {
        if (mainSrc?.track == null) { toast(t("No camera yet", "Belum ada kamera")); return }
        val name = "ProMultiCam_" + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val pfd: ParcelFileDescriptor
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProMultiCam"); put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("insert")
                recUri = u; pfd = contentResolver.openFileDescriptor(u, "rw") ?: throw Exception("open")
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ProMultiCam"); dir.mkdirs()
                val f = File(dir, name); recFile = f
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
            }
        } catch (e: Exception) { toast(t("Cannot save here", "Tidak boleh simpan")); return }
        recPfd = pfd
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val e = Encoder(W, H, pfd.fileDescriptor, audio)
        try { e.start() } catch (x: Exception) { toast(t("Recording failed", "Rakaman gagal")); pfd.close(); return }
        enc = e; prog.attachEncoder(e)
        if (sp.getBoolean("intro", false)) { prog.setOverlay(brand.card(W, H, false, "")); main.postDelayed({ if (enc === e && !ending) refreshOverlay() }, 3000) }
        recStart = System.currentTimeMillis(); pausedMs = 0; pauseAt = 0; ending = false
        recBtn.text = "■ " + t("Stop", "Tamat"); pauseBtn.isEnabled = true; pauseBtn.alpha = 1f; qBtn.isEnabled = false
        timeTx.visibility = View.VISIBLE; tick()
        sendLive(); srcs.forEach { it.data?.send(mapOf("t" to "q", "hi" to true)) }
    }
    private fun tick() {
        val e = enc ?: return
        val ms = (if (pauseAt > 0) pauseAt else System.currentTimeMillis()) - recStart - pausedMs
        val s = ms / 1000
        timeTx.text = String.format(Locale.US, if (e.paused) "‖ %02d:%02d" else "● %02d:%02d", s / 60, s % 60)
        main.postDelayed({ tick() }, 500)
    }
    private fun togglePause() {
        val e = enc ?: return
        if (e.paused) { e.resume(); pausedMs += System.currentTimeMillis() - pauseAt; pauseAt = 0; pauseBtn.text = "‖ " + t("Pause", "Jeda") }
        else { e.pause(); pauseAt = System.currentTimeMillis(); pauseBtn.text = "▶ " + t("Resume", "Sambung") }
    }
    private fun stopRec() {
        val e = enc ?: return
        if (ending) return
        if (sp.getBoolean("outro", false)) {
            ending = true; if (e.paused) togglePause()
            prog.setOverlay(brand.card(W, H, true, sp.getString("outroTxt", "") ?: ""))
            recBtn.text = t("Closing…", "Penutup…"); recBtn.isEnabled = false
            main.postDelayed({ finishStop(e) }, 3000)
        } else finishStop(e)
    }
    private fun finishStop(e: Encoder) {
        recBtn.isEnabled = false; recBtn.text = t("Saving…", "Menyimpan…")
        prog.detachEncoder { e.stop { ok -> main.post { finishRec(ok) } } }
    }
    private fun finishRec(ok: Boolean) {
        enc = null; ending = false; refreshOverlay()
        try { recPfd?.close() } catch (e: Exception) {}
        recPfd = null
        recUri?.let { u ->
            if (ok) { val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }; contentResolver.update(u, cv, null, null); lastUri = u }
            else contentResolver.delete(u, null, null)
        }
        recFile?.let { f -> if (ok) { MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null); lastUri = Uri.fromFile(f) } else f.delete() }
        val part = recUri; recUri = null; recFile = null
        val base = resumeUri
        if (ok && base != null && part != null && Build.VERSION.SDK_INT >= 29) { resumeUri = null; joinInto(base, part) }
        recBtn.isEnabled = true; recBtn.text = "● " + t("Start Recording", "Mula Rakam"); pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f; pauseBtn.text = "‖ " + t("Pause", "Jeda"); qBtn.isEnabled = true
        timeTx.visibility = View.GONE
        sendLive(); srcs.forEach { it.data?.send(mapOf("t" to "q", "hi" to false)) }
        toast(if (ok) t("Saved to gallery: Movies/ProMultiCam", "Disimpan ke galeri: Movies/ProMultiCam") else t("Recording failed", "Rakaman gagal"))
    }

    /* Continue: the new part is added to the end of the earlier video, which keeps its name */
    private fun joinInto(base: Uri, part: Uri) {
        toast(t("Adding to the same video…", "Menambah ke video yang sama…"))
        Thread {
            var ok = false
            try {
                var name = "ProMultiCam.mp4"
                contentResolver.query(base, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "tmp_" + name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProMultiCam"); put(MediaStore.Video.Media.IS_PENDING, 1) }
                val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openFileDescriptor(u, "rw")!!.use { ok = Joiner.join(this, listOf(base, part), it.fileDescriptor) }
                if (ok) {
                    contentResolver.delete(base, null, null); contentResolver.delete(part, null, null)
                    contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0); put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
                } else contentResolver.delete(u, null, null)
            } catch (e: Exception) { ok = false }
            main.post { toast(if (ok) t("Added — one longer video", "Ditambah — satu video lebih panjang") else t("Could not join; the new part is kept as its own video", "Gagal gabung; bahagian baru disimpan berasingan")) }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
