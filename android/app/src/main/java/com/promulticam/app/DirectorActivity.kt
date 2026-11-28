package com.promulticam.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.graphics.drawable.GradientDrawable
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
    private var resumeFile: File? = null
    private var logoView: android.widget.ImageView? = null
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
            val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: throw Exception("logo")
            brand.useLogo(bmp); refreshOverlay(); logoView?.setImageBitmap(brand.logo)
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
        look = Looks.byId(sp.getString("look", "supermassive"))
        resumeFile = intent.getStringExtra("resume")?.let { File(it) }
        main.postDelayed({ loadStill() }, 800)
        prog = Program(W, H); prog.setLook(look.f); prog.layout = layout; prog.trans = trans
        setContentView(buildUi())
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startAll() else perms.launch(need)
        turn = Turn(this) { n -> onTurn(n) }
    }
    /* ---------- turning the phone (like the web): the screen turns, and when not recording the video format follows so the logo and names sit right ---------- */
    private lateinit var turn: Turn
    /* "Continue" from Videos comes back to this same Director (no second Director behind it) */
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); i.getStringExtra("resume")?.let { if (enc == null) { resumeFile = File(it); stillReady = false; main.postDelayed({ loadStill() }, 300); toast(t("Continue: the next recording joins this video", "Sambung: rakaman seterusnya masuk ke video ini")) } } }
    override fun onResume() { super.onResume(); if (::turn.isInitialized && sp.getBoolean("follow", true)) turn.enable() }
    override fun onPause() { super.onPause(); if (::turn.isInitialized) turn.disable() }
    /* like the web: the screen turns and the picture stays upright, but the video format you chose (9:16 or 16:9) never changes by itself */
    private fun onTurn(n: Int) {
        val side = n == 90 || n == 270
        if (side != land) toast(if (land) t("Video stays horizontal 16:9", "Video kekal melintang 16:9") else t("Video stays vertical 9:16", "Video kekal menegak 9:16"))
    }
    override fun onConfigurationChanged(c: android.content.res.Configuration) { super.onConfigurationChanged(c); arrange(c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) }
    private lateinit var rootV: LinearLayout; private lateinit var topV: View; private lateinit var stageV: View; private lateinit var tilesV: View; private lateinit var rowV: View
    private fun arrange(sideUi: Boolean) {
        if (!::rootV.isInitialized) return
        listOf(topV, stageV, tilesV, rowV).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        rootV.removeAllViews()
        if (!sideUi) {
            rootV.orientation = LinearLayout.VERTICAL
            rootV.addView(topV); rootV.addView(stageV, LinearLayout.LayoutParams(-1, 0, 1f)); rootV.addView(tilesV, LinearLayout.LayoutParams(-1, Ui.px(this, 108))); rootV.addView(rowV)
        } else {
            rootV.orientation = LinearLayout.HORIZONTAL
            rootV.addView(stageV, LinearLayout.LayoutParams(0, -1, 1f))
            val side = LinearLayout(this); side.orientation = LinearLayout.VERTICAL
            side.addView(topV); side.addView(tilesV, LinearLayout.LayoutParams(-1, Ui.px(this, 108))); side.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f)); side.addView(rowV)
            rootV.addView(side, LinearLayout.LayoutParams(Ui.px(this, 360), -1))
        }
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
        val root = LinearLayout(c); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Ui.BG); rootV = root
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
        root.addView(top); topV = top

        // the program picture
        val stage = FrameLayout(c); stage.setBackgroundColor(Color.BLACK)
        val sv = SurfaceView(c); mainSv = sv
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { prog.setPreview(h.surface, w, hh) }
            override fun surfaceDestroyed(h: SurfaceHolder) { prog.setPreview(null, 0, 0) }
        })
        stage.addView(sv, Ui.fl(-1, -1))
        // tap = focus there (like the phone camera); hold = back to auto focus
        val tapper = android.view.GestureDetector(c, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { focusAt(stage, e.x, e.y, false); return true }
            override fun onLongPress(e: android.view.MotionEvent) { focusAt(stage, e.x, e.y, true) }
        })
        var pinching = false
        val pinch = android.view.ScaleGestureDetector(c, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(g: android.view.ScaleGestureDetector): Boolean { pinching = true; return mainSrc != null }
            override fun onScale(g: android.view.ScaleGestureDetector): Boolean {
                val s = mainSrc ?: return false; val z = (s.zoom * g.scaleFactor).coerceIn(1f, 4f); s.zoom = z; prog.zoomMain = z
                zoomTx.text = String.format(Locale.US, "%.1f×", z); zoomTx.animate().cancel(); zoomTx.alpha = 1f; return true }
            override fun onScaleEnd(g: android.view.ScaleGestureDetector) { zoomTx.animate().alpha(0f).setStartDelay(900).setDuration(300).start() }
        })
        sv.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) pinching = false
            if (e.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN) { val cx = android.view.MotionEvent.obtain(e); cx.action = android.view.MotionEvent.ACTION_CANCEL; tapper.onTouchEvent(cx); cx.recycle() }
            if (e.pointerCount > 1 || pinching) { if (e.actionMasked == android.view.MotionEvent.ACTION_UP) pinching = false; true } else tapper.onTouchEvent(e)
        }
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
        // front / back camera of the angle on screen
        val flipB = android.widget.ImageView(c); flipB.setImageResource(R.drawable.ic_flip); flipB.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        flipB.background = Ui.round(0x88000000.toInt(), Ui.dp(c, 99f)); flipB.setPadding(d, d, d, d)
        flipB.setOnClickListener { val s = mainSrc ?: return@setOnClickListener
            if (s.local) { cam?.flip(); Focus.torch = false } else s.data?.send(mapOf("t" to "cc", "op" to "flip"))
            camState[s.id]?.set(0, 0); toast(t("Front / back camera", "Kamera depan / belakang")) }
        stage.addView(flipB, Ui.fl(Ui.px(c, 48), Ui.px(c, 48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = d; bottomMargin = d })
        // zoom: upright slider on the right, like the web
        zoomTx = Ui.label(c, "1.0×", 13f, Ui.GOLD, true)
        // zoom with two fingers on the picture; the level shows for a moment
        zoomTx.textSize = 22f; zoomTx.setPadding(d, d / 2, d, d / 2); zoomTx.background = Ui.round(0x99000000.toInt(), Ui.dp(c, 99f)); zoomTx.alpha = 0f
        stage.addView(zoomTx, Ui.fl(-2, -2, Gravity.CENTER))
        zoomBar = SeekBar(c); zoomBar.max = 100; zoomBar.rotation = 270f
        zoomBar.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); zoomBar.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, user: Boolean) { val z = 1f + p / 100f * 3f; zoomTx.text = String.format(Locale.US, "%.1f×", z); if (!user) return; mainSrc?.zoom = z; prog.zoomMain = z }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        zoomBar.visibility = View.GONE
        countTx = Ui.label(c, "", 96f, Color.WHITE, true); countTx.visibility = View.GONE; countTx.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        stage.addView(countTx, Ui.fl(-2, -2, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f)); stageV = stage

        // camera tiles + "add camera"
        val hs = HorizontalScrollView(c); hs.isHorizontalScrollBarEnabled = false
        tiles = LinearLayout(c); tiles.orientation = LinearLayout.HORIZONTAL; tiles.setPadding(d, d, d, 0)
        hs.addView(tiles)
        root.addView(hs, LinearLayout.LayoutParams(-1, Ui.px(c, 108))); tilesV = hs
        addTile = LinearLayout(c); addTile.orientation = LinearLayout.VERTICAL; addTile.gravity = Gravity.CENTER
        addTile.background = GradientDrawable().apply { setColor(0xFF15140F.toInt()); cornerRadius = Ui.dp(c, 10f); setStroke(Ui.px(c, 2), Ui.GOLD, Ui.dp(c, 6f), Ui.dp(c, 4f)) }
        val pc = android.widget.ImageView(c); pc.setImageResource(R.drawable.ic_plus); pc.setColorFilter(0xFF1A1407.toInt())
        pc.background = Ui.goldFace(c, Ui.dp(c, 16f)); val pp = Ui.px(c, 7); pc.setPadding(pp, pp, pp, pp)
        addTile.addView(pc, LinearLayout.LayoutParams(Ui.px(c, 32), Ui.px(c, 32)))
        addTile.addView(Ui.center(Ui.label(c, t("Camera", "Kamera"), 11f, Ui.GOLD, true)), Ui.lp(c, 6))
        addTile.setOnClickListener { connectSheet() }
        tiles.addView(addTile, LinearLayout.LayoutParams(Ui.px(c, 60), -1))

        val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL; row.setPadding(d, d, d, d * 2)
        pauseBtn = Ui.btn(c, "‖ " + t("Pause", "Jeda")) { togglePause() }; pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f
        recBtn = Ui.btn(c, "● " + t("Start Recording", "Mula Rakam"), true) { if (enc == null) countdown() else stopRec() }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, Ui.px(c, 56), 1f))
        row.addView(recBtn, LinearLayout.LayoutParams(0, Ui.px(c, 56), 2f).apply { leftMargin = d })
        root.addView(row); rowV = row
        qBtn = Ui.btn(c, "") {}   // quality lives in Settings
        lookBtn = Ui.btn(c, "") {}; layBtn = Ui.btn(c, "") {}; trBtn = Ui.btn(c, "") {}
        paintChips(); fpsLoop(); main.postDelayed({ visLoop() }, 500)
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
    private fun needPro(): Boolean { if (Auth.isPro) return false; toast(t("Pro feature", "Ciri Pro")); Nav.open(this, "pro"); return true }

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
        val s = Ui.sheet(this, t("Visual Style", "Gaya Visual"))
        s.body.addView(Ui.label(this, "Look", 13f, Ui.SUB, true))
        val grid = android.widget.GridLayout(this); grid.columnCount = 3
        val pills = ArrayList<Button>()
        Looks.ALL.forEachIndexed { i, l ->
            val b = Ui.pill(this, lookName(l), l === look) {
                if (l.id != "std" && !Auth.isPro) { needPro(); return@pill }
                val oldEv = look.ev; look = l; sp.edit().putString("look", l.id).apply(); prog.setLook(l.f); refreshOverlay(); paintChips(); if (oldEv != l.ev) applyEv()
                pills.forEachIndexed { j, p -> Ui.setPill(this, p, Looks.ALL[j] === look) }
            }
            b.maxLines = 1; b.textSize = 14f; b.setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)   // long names shrink to one line
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
        val ti = Ui.input(this, t("e.g. Nasi Lemak Sambal Sotong", "cth: Nasi Lemak Sambal Sotong"), brand.title); multi(ti); b.addView(ti, Ui.lp(this, 6))
        b.addView(Ui.hr(this))
        // brand + when to show, kept as 6 styles: tap a style to use it, "Edit" to change it
        b.addView(Ui.section(this, t("Brand styles", "Gaya Jenama")))
        val sf = Flow(this, Ui.px(this, 6)); val sbs = ArrayList<Button>()
        val editB = Ui.btn(this, "", true) { brandSheet() }
        fun paintSlots() { val c = sp.getInt("brandSlot", 1); sbs.forEachIndexed { j, q -> Ui.setPill(this, q, j + 1 == c) }; editB.text = "✎ " + t("Edit style ", "Ubah Gaya ") + c }
        for (n in 1..6) { val pb = Ui.pill(this, t("Style ", "Gaya ") + n, false) { switchSlot(n); paintSlots() }; pb.textSize = 13f; sbs.add(pb); sf.addView(pb) }
        b.addView(sf, Ui.lp(this, 6)); b.addView(editB, Ui.lp(this, 8)); paintSlots()
        slotPaint = { paintSlots() }
        b.addView(Ui.hr(this))
        b.addView(Ui.check(this, t("Turn the screen with the phone", "Skrin ikut putaran telefon"), sp.getBoolean("follow", true)) { v -> sp.edit().putBoolean("follow", v).apply(); if (v) turn.enable() else { turn.disable(); turn.cur = 0; turn.apply() } })
        b.addView(Ui.check(this, t("Use this phone's camera", "Guna kamera telefon ini"), sp.getBoolean("selfCam", true)) { v -> sp.edit().putBoolean("selfCam", v).apply(); toast(t("Applies next time you open the Director", "Berkuat kuasa bila buka Pengarah semula")) })
        b.addView(Ui.check(this, t("3s countdown", "Kiraan 3 saat"), sp.getBoolean("count", true)) { v -> sp.edit().putBoolean("count", v).apply() })
        b.addView(Ui.label(this, t("Auto-switch angles", "Tukar sudut automatik"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        fun demo(m: String) = DemoView(this).also { it.mode = m; b.addView(it, LinearLayout.LayoutParams(-1, Ui.px(this, 110)).apply { topMargin = Ui.px(this@DirectorActivity, 6) }) }
        val dAuto = demo("auto"); dAuto.auto = sp.getInt("auto", 0)
        val ar = LinearLayout(this); ar.orientation = LinearLayout.HORIZONTAL; val ap = ArrayList<Button>()
        for (n in listOf(0, 5, 10, 15)) { val pb = Ui.pill(this, if (n == 0) t("Off", "Tutup") else "${n}s", sp.getInt("auto", 0) == n) {
                if (n > 0 && !Auth.isPro) { needPro(); return@pill }
                sp.edit().putInt("auto", n).apply(); dAuto.auto = n; ap.forEachIndexed { j, q -> Ui.setPill(this, q, listOf(0, 5, 10, 15)[j] == n) }; lastSwitch = System.currentTimeMillis() }
            ap.add(pb); ar.addView(pb, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 }) }
        b.addView(ar, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Switch effect", "Kesan tukar"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        // same switch effects as the editor
        val tr = Flow(this, Ui.px(this, 6)); val tb = ArrayList<Button>(); val tIds = listOf("auto") + Fx.TRANS.take(12)
        fun cur() = if (trans == "cut") "none" else trans
        val dTr = demo("trans"); dTr.trans = cur()
        for (id in tIds) {
            val pb = Ui.pill(this, if (id == "auto") "🎲 Auto" else Fx.transName(id), cur() == id) { trans = id; prog.trans = id; dTr.trans = id; tb.forEachIndexed { j, q -> Ui.setPill(this, q, tIds[j] == cur()) } }
            pb.textSize = 12f; tb.add(pb); tr.addView(pb) }
        b.addView(tr, Ui.lp(this, 6))
        // fade at the start and end of each recording (also each Continue / Resume)
        b.addView(Ui.label(this, t("Recording fade", "Pudar rakaman"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val dFade = demo("fade"); dFade.fi = recFi(); dFade.fo = recFo()
        val fr = Flow(this, Ui.px(this, 6)); val fbs = ArrayList<Button>()
        val fadeRows = LinearLayout(this); fadeRows.orientation = LinearLayout.VERTICAL
        fun fmode() = when { recFi() > 0 && recFo() > 0 -> "both"; recFi() > 0 -> "in"; recFo() > 0 -> "out"; else -> "none" }
        val ids = listOf("none", "in", "out", "both")
        fun secRow(label: String, key: String): View { val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.label(this, label, 13f, Ui.SUB, true), LinearLayout.LayoutParams(Ui.px(this, 110), -2))
            val cur = sp.getFloat(key, 0f).takeIf { it > 0 } ?: 1f; val vt = Ui.label(this, String.format(Locale.US, "%.1fs", cur), 13f, Ui.TEXT, true)
            val sb = SeekBar(this); sb.max = 28; sb.progress = ((cur - 0.2f) * 10).toInt().coerceIn(0, 28); sb.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); sb.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(x: SeekBar?, p: Int, u: Boolean) { if (!u) return; val v = 0.2f + p / 10f; vt.text = String.format(Locale.US, "%.1fs", v); if (sp.getFloat(key, 0f) > 0) sp.edit().putFloat(key, v).apply() }
                override fun onStartTrackingTouch(x: SeekBar?) {}; override fun onStopTrackingTouch(x: SeekBar?) {} })
            r.addView(sb, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(vt, LinearLayout.LayoutParams(Ui.px(this, 48), -2)); r.tag = sb; return r }
        val inR = secRow(t("Fade in", "Pudar masuk"), "recFi"); val outR = secRow(t("Fade out", "Pudar keluar"), "recFo")
        fun paintF() { val m = fmode(); dFade.fi = recFi(); dFade.fo = recFo(); fbs.forEachIndexed { j, q -> Ui.setPill(this, q, ids[j] == m) }; inR.visibility = if (recFi() > 0) View.VISIBLE else View.GONE; outR.visibility = if (recFo() > 0) View.VISIBLE else View.GONE }
        for ((j, l) in listOf(t("None", "Tiada"), t("Start", "Awal"), t("End", "Akhir"), t("Both", "Kedua")).withIndex()) {
            val pb = Ui.pill(this, l, false) { val wantIn = ids[j] == "in" || ids[j] == "both"; val wantOut = ids[j] == "out" || ids[j] == "both"
                fun v(r: View) = 0.2f + (r.tag as SeekBar).progress / 10f
                sp.edit().putFloat("recFi", if (wantIn) v(inR) else 0f).putFloat("recFo", if (wantOut) v(outR) else 0f).apply(); paintF() }
            pb.textSize = 12f; fbs.add(pb); fr.addView(pb) }
        b.addView(fr, Ui.lp(this, 4)); fadeRows.addView(inR, Ui.lp(this, 6)); fadeRows.addView(outR, Ui.lp(this, 4)); b.addView(fadeRows); paintF()
        b.addView(Ui.check(this, t("Noise filter", "Penapis bunyi"), sp.getBoolean("noise", false)) { v -> sp.edit().putBoolean("noise", v).apply() }, Ui.lp(this, 8))
        ti.addTextChangedListener(watch { brand.title = it; brand.refreshText(); refreshOverlay() })
        s.dialog.setOnDismissListener { refreshOverlay(); paintChips(); logoView = null }
        s.dialog.show()
    }
    /* ---------- brand styles (6 saved sets of brand + when to show) ---------- */
    private var slotPaint: (() -> Unit)? = null
    private var mainSv: SurfaceView? = null
    private val SLOT_S = listOf("price", "phone", "ovStyle", "ovColor", "ovFont", "logoPos", "shop", "desc", "menu") + listOf("logo", "title", "price", "phone").map { "vis_$it" }
    private val SLOT_B = listOf("brand", "logoBg"); private val SLOT_I = listOf("logo", "title", "price", "phone").flatMap { listOf("dur_$it", "every_$it") }
    private fun saveSlot(n: Int) {
        val j = org.json.JSONObject(); val all = sp.all
        (SLOT_S + SLOT_B + SLOT_I).forEach { k -> all[k]?.let { j.put(k, it) } }
        sp.edit().putString("slot$n", j.toString()).apply()
        for ((a, b) in listOf("logo.png" to "logo_s$n.png", "logo_orig.png" to "logo_orig_s$n.png")) { val src = File(filesDir, a); val dst = File(filesDir, b); if (src.exists()) src.copyTo(dst, true) else dst.delete() }
    }
    private fun loadSlot(n: Int) {
        val js = sp.getString("slot$n", null) ?: return   // a new style starts as a copy of the current one
        val j = try { org.json.JSONObject(js) } catch (e: Exception) { return }
        val ed = sp.edit()
        (SLOT_S + SLOT_B + SLOT_I).forEach { k -> if (!j.has(k)) ed.remove(k) else when (k) { in SLOT_B -> ed.putBoolean(k, j.getBoolean(k)); in SLOT_I -> ed.putInt(k, j.getInt(k)); else -> ed.putString(k, j.getString(k)) } }
        ed.apply()
        for ((a, b) in listOf("logo.png" to "logo_s$n.png", "logo_orig.png" to "logo_orig_s$n.png")) { val src = File(filesDir, b); val dst = File(filesDir, a); if (src.exists()) src.copyTo(dst, true) else dst.delete() }
    }
    private fun switchSlot(n: Int) {
        val cur = sp.getInt("brandSlot", 1); if (n == cur) return
        saveSlot(cur); sp.edit().putInt("brandSlot", n).apply(); loadSlot(n)
        brand.loadLogo(); brand.refresh(); visKey = ""; visTick(true); refreshOverlay()
        toast(t("Style ", "Gaya ") + n)
    }
    private fun brandSheet() {
        val n = sp.getInt("brandSlot", 1)
        val s = Ui.sheet(this, t("Style ", "Gaya ") + n, t("Done", "Siap") to { refreshOverlay() })
        val b = s.body
        // the live picture stays on top while the settings scroll below, so every change shows at once
        val outer = (b.parent as View).parent as LinearLayout
        val pv = android.view.SurfaceView(this)
        pv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { prog.setPreview(h.surface, w, hh) }
            override fun surfaceDestroyed(h: SurfaceHolder) { mainSv?.let { m -> if (m.holder.surface.isValid) prog.setPreview(m.holder.surface, m.width, m.height) else prog.setPreview(null, 0, 0) } }
        })
        val box = FrameLayout(this); box.setBackgroundColor(android.graphics.Color.BLACK); box.addView(pv, FrameLayout.LayoutParams(-1, -1))
        outer.addView(box, 1, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.42f).toInt()).apply { topMargin = Ui.px(this@DirectorActivity, 6); bottomMargin = Ui.px(this@DirectorActivity, 6) })
        s.dialog.window?.setLayout(-1, -1); s.dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val full = (resources.displayMetrics.heightPixels * 0.42f).toInt(); val small = (resources.displayMetrics.heightPixels * 0.2f).toInt()
        outer.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect(); outer.getWindowVisibleDisplayFrame(r); val kb = resources.displayMetrics.heightPixels - r.bottom > resources.displayMetrics.heightPixels * 0.15f
            val want = if (kb) small else full; val lp = box.layoutParams; if (lp.height != want) { lp.height = want; box.layoutParams = lp } }
        val lr = LinearLayout(this); lr.orientation = LinearLayout.HORIZONTAL; lr.gravity = Gravity.CENTER_VERTICAL
        val lv = android.widget.ImageView(this); logoView = lv; lv.background = Ui.round(0xFF121110.toInt(), Ui.dp(this, 12f), Ui.LINE, 2); lv.setImageBitmap(brand.logo); lv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        lr.addView(lv, LinearLayout.LayoutParams(Ui.px(this, 54), Ui.px(this, 54)))
        lr.addView(Ui.btn(this, t("Upload logo", "Muat naik logo")) { if (!needPro()) pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 12 })
        lr.addView(Ui.dangerBtn(this, t("Remove", "Buang")) { brand.clearLogo(); lv.setImageBitmap(null); refreshOverlay() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8 })
        b.addView(lr, Ui.lp(this, 6))
        b.addView(Ui.check(this, t("Remove logo background (see-through)", "Buang latar logo (lutsinar)"), brand.removeBg) { v -> brand.removeBg = v; lv.setImageBitmap(brand.logo); refreshOverlay() })
        b.addView(Ui.label(this, t("Price / tagline (optional)", "Harga / slogan (pilihan)"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val pr = Ui.input(this, t("e.g. RM12.90 or Buy 2 Free 1", "cth: RM12.90 atau Beli 2 Percuma 1"), brand.price); multi(pr); b.addView(pr, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Phone / contact (optional)", "Telefon / hubungi (pilihan)"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val ph = Ui.input(this, t("e.g. 012-345 6789 or Order via the bio link", "cth: 012-345 6789 atau Tempah di pautan bio"), brand.phone); multi(ph); b.addView(ph, Ui.lp(this, 6))
        b.addView(Ui.check(this, t("Brand in the video", "Jenama dalam video"), brand.on) { v -> brand.on = v; refreshOverlay() }, Ui.lp(this, 8))
        // each part: always, hidden, or shown for some seconds (once at the start, or again and again)
        b.addView(Ui.label(this, t("When to show", "Bila dipaparkan"), 15f, Ui.TEXT, true), Ui.lp(this, 14))
        for ((k, nm) in listOf("logo" to "Logo", "title" to t("Title", "Tajuk"), "price" to t("Price", "Harga"), "phone" to t("Phone", "Telefon"))) {
            val box = Ui.card(this); box.addView(Ui.label(this, nm, 14f, Ui.GOLD, true))
            val modes = Flow(this, Ui.px(this, 6)); val more = LinearLayout(this); more.orientation = LinearLayout.VERTICAL
            val mb = ArrayList<Button>()
            fun paintMode() { val m = sp.getString("vis_$k", "on"); mb.forEachIndexed { j, q -> Ui.setPill(this, q, listOf("on", "off", "timed")[j] == m) }; more.visibility = if (m == "timed") View.VISIBLE else View.GONE }
            for ((id, l) in listOf("on" to t("Always", "Sentiasa"), "off" to t("Hidden", "Tiada"), "timed" to t("Timed", "Ikut masa"))) {
                val p = Ui.pill(this, l, false) { sp.edit().putString("vis_$k", id).apply(); paintMode(); visTick(true) }; p.textSize = 13f; mb.add(p); modes.addView(p) }
            box.addView(modes, Ui.lp(this, 6))
            val r1 = LinearLayout(this); r1.gravity = Gravity.CENTER_VERTICAL
            r1.addView(Ui.label(this, t("Show", "Papar"), 13f, Ui.SUB, true), LinearLayout.LayoutParams(Ui.px(this, 110), -2))
            val du = Ui.input(this, "5", sp.getInt("dur_$k", 5).toString(), android.text.InputType.TYPE_CLASS_NUMBER); du.addTextChangedListener(watch { v -> sp.edit().putInt("dur_$k", v.toIntOrNull()?.coerceIn(1, 3600) ?: 5).apply() })
            r1.addView(du, LinearLayout.LayoutParams(Ui.px(this, 90), -2)); r1.addView(Ui.label(this, "  " + t("seconds", "saat"), 13f, Ui.SUB))
            more.addView(r1, Ui.lp(this, 8))
            more.addView(Ui.label(this, t("Repeat", "Ulang"), 13f, Ui.SUB, true), Ui.lp(this, 8))
            val reps = Flow(this, Ui.px(this, 6)); val rb = ArrayList<Button>(); val ev = listOf(0, 30, 60, 120, 300)
            fun paintRep() { val e = sp.getInt("every_$k", 0); rb.forEachIndexed { j, q -> Ui.setPill(this, q, ev[j] == e) } }
            for (e in ev) { val p = Ui.pill(this, when (e) { 0 -> t("Once", "Sekali"); 30 -> "30s"; 60 -> "1 min"; 120 -> "2 min"; else -> "5 min" }, false) { sp.edit().putInt("every_$k", e).apply(); paintRep() }
                p.textSize = 12f; rb.add(p); reps.addView(p) }
            more.addView(reps, Ui.lp(this, 4))
            box.addView(more); paintMode(); paintRep()
            b.addView(box, Ui.lp(this, 8))
        }
        b.addView(Ui.hr(this))
        b.addView(Ui.section(this, t("Overlay", "Paparan")))
        val og = android.widget.GridLayout(this); og.columnCount = 2
        val cards = ArrayList<LinearLayout>()
        val shopIn = Ui.input(this, t("e.g. Dapur Mak Long", "cth. Dapur Mak Long"), brand.shop)
        val descIn = Ui.input(this, t("e.g. Fish broth, fresh herbs", "cth. Kuah ikan, ulam segar"), brand.desc)
        val menuIn = Ui.input(this, "Laksam - RM5\nNasi kerabu - RM8", brand.menu, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); menuIn.minLines = 3; menuIn.gravity = Gravity.TOP
        val shopL = Ui.label(this, t("Shop name or @account", "Nama kedai atau @akaun"), 13f, Ui.SUB, true); val descL = Ui.label(this, t("Short description", "Penerangan ringkas"), 13f, Ui.SUB, true)
        val menuL = Ui.label(this, t("Menu (name - price, one per line)", "Menu (nama - harga, satu baris)"), 13f, Ui.SUB, true)
        lateinit var fontBtn: Button
        fun showRows() { val st = brand.style
            val shopOn = st in listOf("band", "menu", "minimal", "polaroid", "gold", "panel")
            for (v in listOf(shopL, shopIn)) v.visibility = if (shopOn) View.VISIBLE else View.GONE
            for (v in listOf(descL, descIn)) v.visibility = if (st == "panel") View.VISIBLE else View.GONE
            for (v in listOf(menuL, menuIn)) v.visibility = if (st == "menu") View.VISIBLE else View.GONE
            cards.forEachIndexed { j, cv -> cv.background = Ui.round(if (Overlay.STYLES[j] == st) 0xFF2A2416.toInt() else 0xFF15140F.toInt(), Ui.dp(this, 12f), if (Overlay.STYLES[j] == st) Ui.GOLD else Ui.LINE, Ui.px(this, if (Overlay.STYLES[j] == st) 2 else 1)) }
            fontBtn.text = (Overlay.FONTS[brand.font.ifEmpty { Overlay.DEF_FONT[st] ?: "bricolage" }]?.let { if (Ui.ms) it.ms else it.en } ?: "") + "  ▾"
            refreshOverlay() }
        Overlay.STYLES.forEachIndexed { k, st ->
            val cv = LinearLayout(this); cv.orientation = LinearLayout.VERTICAL; cv.setPadding(Ui.px(this, 10), Ui.px(this, 8), Ui.px(this, 10), Ui.px(this, 8))
            val nm = Overlay.STYLE_NAMES[st]!!; val ds = Overlay.STYLE_DESC[st]!!
            cv.addView(Ui.label(this, "${k + 1}. " + (if (Ui.ms) nm.second else nm.first), 13f, Ui.TEXT, true)); cv.addView(Ui.label(this, if (Ui.ms) ds.second else ds.first, 11f, Ui.SUB))
            cv.setOnClickListener { if (st != "card" && !Auth.isPro) { needPro(); return@setOnClickListener }; brand.style = st; showRows() }
            cards.add(cv)
            (cv.getChildAt(0) as TextView).maxLines = 1; (cv.getChildAt(1) as TextView).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val gp = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(k / 2), android.widget.GridLayout.spec(k % 2, 1f)); gp.width = 0; gp.height = Ui.px(this, 78); gp.setMargins(4, 5, 4, 5); og.addView(cv, gp)
        }
        b.addView(og, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Colour", "Warna"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val sw = LinearLayout(this); sw.orientation = LinearLayout.HORIZONTAL; val sws = ArrayList<View>()
        Overlay.COLORS.keys.forEach { k -> val v = View(this); sws.add(v)
            v.setOnClickListener { if (k != "gold" && !Auth.isPro) { needPro(); return@setOnClickListener }; brand.col = k; sws.forEachIndexed { j, x -> val ck = Overlay.COLORS.keys.elementAt(j); x.background = Ui.round(Overlay.COLORS[ck]!!.c, Ui.dp(this, 99f), if (ck == brand.col) Ui.TEXT else Ui.LINE, Ui.px(this, if (ck == brand.col) 3 else 1)) }; refreshOverlay() }
            v.background = Ui.round(Overlay.COLORS[k]!!.c, Ui.dp(this, 99f), if (k == brand.col) Ui.TEXT else Ui.LINE, Ui.px(this, if (k == brand.col) 3 else 1))
            sw.addView(v, LinearLayout.LayoutParams(Ui.px(this, 40), Ui.px(this, 40)).apply { rightMargin = Ui.px(this@DirectorActivity, 10) }) }
        b.addView(sw, Ui.lp(this, 6))
        b.addView(Ui.label(this, t("Font", "Fon"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        fontBtn = Ui.btn(this, "") {
            if (!Auth.isPro) { needPro(); return@btn }
            val keys = Overlay.FONTS.keys.toList(); val names = keys.map { k -> Overlay.FONTS[k]!!.let { if (Ui.ms) it.ms else it.en } }.toTypedArray()
            AlertDialog.Builder(this).setTitle(t("Font", "Fon")).setItems(names) { _, w -> brand.font = keys[w]; showRows() }.show()
        }
        b.addView(fontBtn, Ui.lp(this, 6))
        for (v in listOf(shopL, shopIn, descL, descIn, menuL, menuIn)) b.addView(v, Ui.lp(this, if (v is TextView && v !is android.widget.EditText) 12 else 6))
        shopIn.addTextChangedListener(watch { brand.shop = it; refreshOverlay() }); descIn.addTextChangedListener(watch { brand.desc = it; refreshOverlay() }); menuIn.addTextChangedListener(watch { brand.menu = it; refreshOverlay() })
        b.addView(Ui.label(this, t("Logo position", "Kedudukan logo"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val pg = android.widget.GridLayout(this); pg.columnCount = 2; val pbs = ArrayList<Button>()
        Overlay.POS.forEachIndexed { k, (id, nm) -> val pb = Ui.pill(this, if (Ui.ms) nm.second else nm.first, brand.logoPos == id) {
                brand.logoPos = id; pbs.forEachIndexed { j, x -> Ui.setPill(this, x, Overlay.POS[j].first == id) }; refreshOverlay() }
            pbs.add(pb); val gp = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(k / 2), android.widget.GridLayout.spec(k % 2, 1f)); gp.width = 0; gp.setMargins(4, 5, 4, 5); pg.addView(pb, gp) }
        b.addView(pg, Ui.lp(this, 6))
        showRows()
        pr.addTextChangedListener(watch { brand.price = it; brand.refreshText(); refreshOverlay() }); ph.addTextChangedListener(watch { brand.phone = it; brand.refreshText(); refreshOverlay() })
        s.dialog.setOnDismissListener { saveSlot(n); refreshOverlay(); logoView = null; slotPaint?.invoke() }
        s.dialog.show()
    }

    /* text boxes that take more than one line (Enter = new line) */
    private fun multi(e: android.widget.EditText) { e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; e.isSingleLine = false; e.maxLines = 3; e.minLines = 1 }
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
        val url = "https://promulticam.web.app/join/$code"
        try {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600)
            val bmp = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until 600) for (y in 0 until 600) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
            val iv = android.widget.ImageView(this); iv.setImageBitmap(bmp); iv.background = Ui.round(Color.WHITE, Ui.dp(this, 14f)); iv.setPadding(16, 16, 16, 16)
            b.addView(iv, LinearLayout.LayoutParams(Ui.px(this, 220), Ui.px(this, 220)).apply { topMargin = Ui.px(this@DirectorActivity, 10); gravity = Gravity.CENTER_HORIZONTAL })
        } catch (e: Exception) {}
        b.addView(Ui.hint(this, t("Camera phones type this ID or scan the QR. With the app installed, the link opens the app.", "Telefon Kamera taip ID ini atau imbas QR. Jika app sudah dipasang, pautan terus buka app.")))
        b.addView(Ui.btn(this, t("Share link", "Kongsi pautan")) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t("Join my ProMultiCam studio as a camera (code $code): ", "Sertai studio ProMultiCam saya sebagai kamera (kod $code): ") + url +
                "\n\n" + t("Smoother video with the Android app:", "Video lebih lancar dengan app Android:") + " https://promulticam.web.app/apk.html"), t("Share", "Kongsi")))
        }, Ui.lp(this, 12))
        b.addView(Ui.section(this, t("Connected", "Bersambung")))
        srcs.forEach { x -> b.addView(Ui.label(this, "• " + x.name + if (x === mainSrc) "  ★" else "", 15f, Ui.TEXT), Ui.lp(this, 4)) }
        s.dialog.show()
    }

    private fun studioSheet() {
        if (needPro()) return
        studioDialog()
    }

    private fun refreshOverlay() {
        fadeGen++
        brand.speaker = mainSrc?.name ?: ""
        prog.vRect = if (Auth.isPro && brand.on) Overlay.videoRect(brand.style, W, H) else null
        prog.setOverlay(brand.render(W, H, look))
    }

    /* ---------- Studio: title & closing cards, teleprompter ---------- */
    private val TPL = mapOf(
        "cook" to arrayOf("warm", "pip", "fade", "1", "1", "0"), "music" to arrayOf("cine", "full", "fade", "1", "0", "0"),
        "pod" to arrayOf("std", "split", "cut", "1", "1", "0"), "drama" to arrayOf("drama", "full", "fade", "0", "0", "1"))
    private fun applyTpl(k: String?) {
        sp.edit().putString("tpl", k ?: "").apply()
        val c = TPL[k] ?: arrayOf("superior", "full", "fade", "0", "0", "0")
        look = Looks.byId(c[0]); sp.edit().putString("look", look.id).putBoolean("intro", c[3] == "1").putBoolean("outro", c[4] == "1").putBoolean("story", c[5] == "1").apply()
        prog.setLook(look.f); trans = c[2]; prog.trans = trans
        layout = if (c[1] != "full" && srcs.count { it.track != null } < 2) "full" else c[1]; prog.layout = layout; pickSecond()
        refreshOverlay(); paintChips()
    }
    private fun studioDialog() {
        val sh = Ui.sheet(this, "Studio Pro", t("Save settings", "Simpan Tetapan") to { })
        val col = sh.body
        // "how to use" next to the title
        col.addView(Ui.btn(this, t("How to use", "Cara Guna")) { Nav.open(this, "manual") }.apply { setTextColor(Ui.GOLD); textSize = 14f }, LinearLayout.LayoutParams(-2, Ui.px(this, 44)))
        // on now
        val now = Ui.card(this)
        now.addView(Ui.label(this, t("On now", "Aktif sekarang"), 14f, Ui.SUB, true))
        val on = ArrayList<String>()
        sp.getString("tpl", "")?.takeIf { it.isNotEmpty() }?.let { on.add(t("Template: ", "Templat: ") + tplName(it)) }
        if (sp.getBoolean("story", false)) on.add(t("Story mode", "Mod Cerita"))
        if (sp.getBoolean("intro", false)) on.add(t("Title card", "Kad tajuk")); if (sp.getBoolean("outro", false)) on.add(t("Closing card", "Kad penutup"))
        now.addView(Ui.label(this, if (on.isEmpty()) t("Nothing on.", "Tiada yang aktif.") else on.joinToString(" · "), 15f, Ui.TEXT), Ui.lp(this, 6))
        now.addView(Ui.btn(this, t("Turn everything off", "Matikan semua")) { applyTpl(null); sp.edit().putBoolean("story", false).putBoolean("intro", false).putBoolean("outro", false).apply(); sh.dialog.dismiss(); studioDialog() }, Ui.lp(this, 10))
        col.addView(now, Ui.lp(this, 10))
        col.addView(Ui.hr(this))
        col.addView(Ui.section(this, t("Quick templates", "Templat Pantas")))
        col.addView(Ui.hint(this, t("Tap to use.", "Tekan untuk guna.")))
        val g = android.widget.GridLayout(this); g.columnCount = 2
        val cur = sp.getString("tpl", "")
        listOf("cook" to t("Warm, order card", "Hangat, kad tempahan"), "music" to t("Cinematic, title card", "Sinematik, kad tajuk"),
               "pod" to t("Split screen, clear voice", "Skrin belah, suara jelas"), "drama" to t("Dramatic, scenes", "Dramatik, babak")).forEachIndexed { i, (k, d) ->
            val cv = LinearLayout(this); cv.orientation = LinearLayout.VERTICAL; cv.setPadding(Ui.px(this, 12), Ui.px(this, 10), Ui.px(this, 12), Ui.px(this, 14))
            cv.background = if (k == cur) Ui.raised(this, 0xFF2A2416.toInt(), 0xFF0E0D0B.toInt(), Ui.dp(this, 12f), Ui.GOLD) else Ui.raised(this, Ui.CARD2, 0xFF0E0D0B.toInt(), Ui.dp(this, 12f), Ui.LINE)
            cv.addView(Ui.label(this, tplName(k), 16f, Ui.TEXT, true)); cv.addView(Ui.label(this, d, 12f, Ui.SUB))
            cv.setOnClickListener { applyTpl(k); sh.dialog.dismiss(); studioDialog(); toast(tplName(k) + " ✓") }
            val p = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / 2), android.widget.GridLayout.spec(i % 2, 1f)); p.width = 0; p.setMargins(4, 5, 4, 5); g.addView(cv, p)
        }
        col.addView(g, Ui.lp(this, 6))
        col.addView(Ui.dangerBtn(this, t("Turn template off", "Matikan templat")) { applyTpl(null); sh.dialog.dismiss(); studioDialog() }, Ui.lp(this, 6))
        col.addView(Ui.hr(this))
        col.addView(Ui.check(this, t("Story mode", "Mod Cerita"), sp.getBoolean("story", false)) { v -> sp.edit().putBoolean("story", v).apply() })
        col.addView(Ui.hint(this, t("Record scene by scene; take numbers go up by themselves.", "Rakam babak demi babak; nombor take naik sendiri.")))
        col.addView(Ui.btn(this, "+ " + t("Next scene", "Babak seterusnya")) { val n = sp.getInt("scene", 1) + 1; sp.edit().putInt("scene", n).putInt("take", 0).apply(); toast(t("Scene ", "Babak ") + n) }, Ui.lp(this, 8))
        col.addView(Ui.hr(this))
        col.addView(Ui.section(this, "Teleprompter"))
        col.addView(Ui.hint(this, t("Camera phones only.", "Telefon Kamera sahaja.")))
        val tp = Ui.input(this, t("Your script…", "Skrip anda…"), sp.getString("tpText", "") ?: ""); tp.minLines = 4; tp.gravity = Gravity.TOP; col.addView(tp, Ui.lp(this, 6))
        fun slider(label: String, max: Int, v: Int): SeekBar { val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.label(this, label, 13f, Ui.SUB, true), LinearLayout.LayoutParams(Ui.px(this, 86), -2))
            val sb = SeekBar(this); sb.max = max; sb.progress = v; sb.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); sb.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
            r.addView(sb, LinearLayout.LayoutParams(0, -2, 1f)); col.addView(r, Ui.lp(this, 8)); return sb }
        val spd = slider(t("Speed", "Laju"), 9, sp.getInt("tpSpeed", 4) - 1)
        val siz = slider(t("Text size", "Saiz teks"), 40, sp.getInt("tpSize", 34) - 20)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        fun tpSend(cmd: String) {
            sp.edit().putString("tpText", tp.text.toString()).putInt("tpSpeed", spd.progress + 1).putInt("tpSize", siz.progress + 20).apply()
            val m = mapOf("t" to "tp", "cmd" to cmd, "text" to tp.text.toString(), "speed" to (spd.progress + 1), "size" to (siz.progress + 20))
            srcs.forEach { it.data?.send(m) }
            if (srcs.none { it.data != null }) toast(t("Connect a camera phone first", "Sambung telefon Kamera dahulu"))
        }
        for ((l, cmd) in listOf(t("Start", "Mula") to "start", t("Pause", "Jeda") to "pause", t("From top", "Dari atas") to "reset"))
            row.addView(Ui.btn(this, l, cmd == "start") { tpSend(cmd) }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, Ui.px(this, 50), 1f).apply { rightMargin = 6 })
        col.addView(row, Ui.lp(this, 10))
        col.addView(Ui.btn(this, t("Hide teleprompter", "Sorok teleprompter")) { tpSend("hide") }.apply { setTextColor(Ui.GOLD); textSize = 14f }, Ui.lp(this, 8))
        col.addView(Ui.hr(this))
        col.addView(Ui.section(this, t("Title & closing cards", "Kad Tajuk & Penutup")))
        col.addView(Ui.check(this, t("Title card (3s)", "Kad tajuk (3s)"), sp.getBoolean("intro", false)) { v -> sp.edit().putBoolean("intro", v).apply() })
        val sub = Ui.input(this, t("Subtitle (optional), e.g. Episode 1", "Subtajuk (pilihan), cth. Episod 1"), sp.getString("introSub", "") ?: ""); col.addView(sub, Ui.lp(this, 4))
        sub.addTextChangedListener(watch { sp.edit().putString("introSub", it).apply() })
        col.addView(Ui.check(this, t("Closing card (3s)", "Kad penutup (3s)"), sp.getBoolean("outro", false)) { v -> sp.edit().putBoolean("outro", v).apply() }, Ui.lp(this, 8))
        val ot = Ui.input(this, t("Closing text, e.g. Order now!", "Teks penutup, cth. Tempah sekarang!"), sp.getString("outroTxt", "") ?: ""); col.addView(ot, Ui.lp(this, 4))
        ot.addTextChangedListener(watch { sp.edit().putString("outroTxt", it).apply() })
        col.addView(Ui.hint(this, t("Uses your title, logo, price and contact.", "Guna tajuk, logo, harga dan hubungan anda.")))
        sh.dialog.setOnDismissListener { sp.edit().putString("tpText", tp.text.toString()).putInt("tpSpeed", spd.progress + 1).putInt("tpSize", siz.progress + 20).apply() }
        sh.dialog.show()
    }
    private fun tplName(k: String) = when (k) { "cook" -> t("Cooking", "Masakan"); "music" -> t("Music video", "Video Muzik"); "pod" -> t("Podcast & interview", "Podcast & Temu Bual"); else -> t("Story & drama", "Cerita & Drama") }

    /* ---------- cameras ---------- */
    private fun startAll() {
        refreshOverlay()
        cam = if (sp.getBoolean("selfCam", true)) Rtc.camera(this, false, 1920, 1080, "dir") else null
        cam?.let { c -> val s = Src("local", t("Director", "Pengarah"), true); s.track = c.track; addSrc(s) }
        if (look.ev != 0) main.postDelayed({ applyEv() }, 1500)
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

    /* the look's camera brightness, on every camera (Supermassive makes them a little brighter) */
    private fun applyEv(only: Src? = null) {
        (if (only != null) listOf(only) else srcs).forEach { s -> val st = camState.getOrPut(s.id) { intArrayOf(0, 0) }; st[1] = look.ev
            if (s.local) cam?.let { c -> Focus.light(c.capturer, c.front, st[0] == 1, look.ev) } else s.data?.send(mapOf("t" to "cc", "op" to "light", "torch" to (st[0] == 1), "ev" to look.ev)) }
    }
    private fun addSrc(s: Src) {
        srcs.add(s)
        if (!s.local && look.ev != 0) main.postDelayed({ if (srcs.contains(s)) applyEv(s) }, 2500)
        val d = Ui.dp(this, 6f).toInt()
        val f = FrameLayout(this); f.background = Ui.round(Ui.CARD, Ui.dp(this, 10f)); f.setPadding(d / 2, d / 2, d / 2, d / 2)
        val v = SurfaceViewRenderer(this); v.init(Rtc.egl.eglBaseContext, null); v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        v.setZOrderMediaOverlay(true)
        f.addView(v, FrameLayout.LayoutParams(-1, -1))
        val tx = Ui.label(this, s.name, 11f, Color.WHITE, true); tx.setShadowLayer(4f, 0f, 1f, Color.BLACK); tx.tag = "lbl"
        f.addView(tx, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = d; bottomMargin = d / 2 })
        f.setOnClickListener { tap(s) }; v.setOnClickListener { tap(s) }
        // long press: control that camera from here
        f.setOnLongClickListener { camSheet(s); true }; v.setOnLongClickListener { camSheet(s); true }
        tiles.addView(f, maxOf(0, tiles.childCount - 1), LinearLayout.LayoutParams(Ui.dp(this, 60f).toInt(), -1).apply { rightMargin = d })
        s.tile = f; s.tileView = v
        s.track?.addSink(v)
        if (mainSrc == null && s.track != null) setMain(s)
        paintTiles(); paintCode()
    }
    private fun label(s: Src) { (s.tile?.findViewWithTag<TextView>("lbl"))?.text = s.name }

    /* full: tap = main angle. split / small frame: tap another = second view, tap the main one = swap */
    /* change a camera from the Director: front/back, torch, brightness, focus */
    private fun camSheet(s: Src) {
        val sh = Ui.sheet(this, "📷 " + s.name)
        fun send(m: Map<String, Any?>) { if (s.local) { val c = cam ?: return; when (m["op"]) {
                "flip" -> { c.flip(); Focus.torch = false; sTorch = false }
                "light" -> Focus.light(c.capturer, c.front, m["torch"] == true, (m["ev"] as? Int) ?: 0)
                "auto" -> Focus.tap(c.capturer, 0.5f, 0.5f, c.front, true) } }
            else s.data?.send(mapOf("t" to "cc") + m) }
        val st = camState.getOrPut(s.id) { intArrayOf(0, 0) }   // [torch 0/1, brightness -2..2]
        sh.body.addView(Ui.btn(this, "🔄  " + t("Switch front / back", "Tukar depan / belakang")) { st[0] = 0; send(mapOf("op" to "flip")); sh.dialog.dismiss() })
        val tb = Ui.btn(this, "") {}; fun paintT() { tb.text = "🔦  " + t("Torch: ", "Lampu suluh: ") + if (st[0] == 1) t("ON", "HIDUP") else t("OFF", "MATI") }; paintT()
        tb.setOnClickListener { st[0] = 1 - st[0]; paintT(); send(mapOf("op" to "light", "torch" to (st[0] == 1), "ev" to st[1])) }
        sh.body.addView(tb, Ui.lp(this, 8))
        sh.body.addView(Ui.label(this, t("Brightness", "Kecerahan"), 13f, Ui.SUB, true), Ui.lp(this, 14))
        val r = Flow(this, Ui.px(this, 6)); val pills = ArrayList<android.widget.Button>()
        (-2..2).forEach { e -> pills.add(Ui.pill(this, if (e > 0) "+$e" else "$e", e == st[1]) { st[1] = e; pills.forEachIndexed { j, b -> Ui.setPill(this, b, j - 2 == e) }; send(mapOf("op" to "light", "torch" to (st[0] == 1), "ev" to e)) }.also { r.addView(it) }) }
        sh.body.addView(r, Ui.lp(this, 6))
        sh.body.addView(Ui.btn(this, "🎯  " + t("Auto focus", "Fokus automatik")) { send(mapOf("op" to "auto")); toast(t("Auto focus", "Fokus automatik")) }, Ui.lp(this, 14))
        sh.body.addView(Ui.hint(this, t("Tip: long-press a camera to open this.", "Tip: tekan lama pada kamera untuk buka ini.")))
        sh.dialog.show()
    }
    private val camState = HashMap<String, IntArray>(); private var sTorch = false
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
        if (brand.style == "speaker") refreshOverlay()
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
        var nm = brand.title.ifEmpty { "Video" }
        if (sp.getBoolean("story", false)) { val sc = sp.getInt("scene", 1); val tk = sp.getInt("take", 0) + 1; sp.edit().putInt("take", tk).apply(); nm += "_S${sc}T$tk" }
        val f = Store.newFile(this, nm); recFile = f
        val pfd = try { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE) } catch (e: Exception) { toast(t("Cannot save here", "Tidak boleh simpan")); return }
        recPfd = pfd
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val e = Encoder(W, H, pfd.fileDescriptor, audio, look.id == "supermassive")
        try { e.start() } catch (x: Exception) { toast(t("Recording failed", "Rakaman gagal")); pfd.close(); return }
        enc = e; prog.attachEncoder(e)
        fadeIn(e)
        if (resumeFile != null && useStill()) { if (stillReady) prog.fadeIn() else { stillReady = false } }
        if (sp.getBoolean("intro", false)) { prog.setOverlay(brand.card(W, H, false, sp.getString("introSub", "") ?: "")); main.postDelayed({ if (enc === e && !ending) refreshOverlay() }, 3000) }
        recStart = System.currentTimeMillis(); pausedMs = 0; pauseAt = 0; ending = false
        recBtn.text = "■ " + t("Stop", "Tamat"); pauseBtn.isEnabled = true; pauseBtn.alpha = 1f; qBtn.isEnabled = false
        timeTx.visibility = View.VISIBLE; tick()
        sendLive(); srcs.forEach { it.data?.send(mapOf("t" to "q", "hi" to true)) }
    }
    /* which parts of the brand show now; the picture is redrawn only when that changes */
    private var visKey = ""
    private fun recSec(): Long = if (enc == null) -1 else ((if (pauseAt > 0) pauseAt else System.currentTimeMillis()) - recStart - pausedMs) / 1000
    private fun visTick(force: Boolean = false) {
        val t0 = recSec(); val h = HashSet<String>()
        for (k in listOf("logo", "title", "price", "phone")) when (sp.getString("vis_$k", "on")) {
            "off" -> h.add(k)
            "timed" -> if (t0 >= 0) { val du = sp.getInt("dur_$k", 5); val ev = sp.getInt("every_$k", 0); val show = if (ev > 0) t0 % maxOf(ev, du + 1) < du else t0 < du; if (!show) h.add(k) }
        }
        val key = h.sorted().joinToString(","); if (key != visKey || force) { visKey = key; val old = brand.hidden; brand.hidden = h
            val card = ending || (enc != null && sp.getBoolean("intro", false) && System.currentTimeMillis() - recStart < 3100)   // title / closing card on screen
            if (!card) { if (force || old == h) refreshOverlay() else fadeOverlay(old, h) } }
    }
    /* parts appear and disappear softly: blend the picture without them into the picture with them */
    private var fadeGen = 0
    private fun fadeOverlay(from: Set<String>, to: Set<String>) {
        val gen = ++fadeGen; val w = W; val hh = H
        Thread {
            brand.hidden = from; val a = brand.render(w, hh, look); brand.hidden = to; val b = brand.render(w, hh, look)
            if (a == null || b == null) { main.post { if (gen == fadeGen) refreshOverlay() }; return@Thread }
            val steps = 10; val add = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) }
            for (i in 1..steps) {
                if (gen != fadeGen || stopped) return@Thread
                val p = i / steps.toFloat(); val out = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(out)
                c.drawBitmap(a, 0f, 0f, android.graphics.Paint().apply { alpha = ((1 - p) * 255).toInt() }); add.alpha = (p * 255).toInt(); c.drawBitmap(b, 0f, 0f, add)
                prog.setOverlay(out); Thread.sleep(60)
            }
            main.post { if (gen == fadeGen) { brand.speaker = mainSrc?.name ?: ""; prog.setOverlay(b) } }
        }.start()
    }
    private fun visLoop() { if (stopped) return; visTick(); main.postDelayed({ visLoop() }, 250) }
    private fun tick() {
        val e = enc ?: return
        val ms = (if (pauseAt > 0) pauseAt else System.currentTimeMillis()) - recStart - pausedMs
        val s = ms / 1000
        timeTx.text = String.format(Locale.US, if (e.paused) "‖ %02d:%02d" else "● %02d:%02d", s / 60, s % 60)
        main.postDelayed({ tick() }, 500)
    }
    private fun togglePause() {
        val e = enc ?: return
        if (fading) return
        if (!e.paused && recFo() > 0 && !fadeOutDone) { fadeOut { togglePause(); prog.dim = 0f }; return }
        if (e.paused) { if (useStill()) prog.fadeIn(); fadeIn(e); e.resume(); pausedMs += System.currentTimeMillis() - pauseAt; pauseAt = 0; pauseBtn.text = "‖ " + t("Pause", "Jeda") }
        else { if (useStill()) prog.holdLast(); e.pause(); pauseAt = System.currentTimeMillis(); pauseBtn.text = "▶ " + t("Resume", "Sambung") }
    }
    /* fade in / out of a recording (picture to black and sound), set in Settings */
    /* with a fade in/out set, a continued part simply fades up from black: no picture of the last moment */
    private fun useStill() = trans != "cut" && trans != "none" && recFi() <= 0f && recFo() <= 0f
    private fun recFi() = sp.getFloat("recFi", 0f); private fun recFo() = sp.getFloat("recFo", 0f)
    private var fading = false; private var fadeOutDone = false
    private fun ramp(up: Boolean, sec: Float, done: () -> Unit) {
        val t0 = System.currentTimeMillis(); val ms = (sec * 1000).toLong().coerceAtLeast(50)
        fun step() { val p = ((System.currentTimeMillis() - t0).toFloat() / ms).coerceIn(0f, 1f); val g = if (up) p else 1 - p
            prog.dim = 1 - g; enc?.vol = g; if (p < 1f) main.postDelayed({ step() }, 30) else done() }
        step()
    }
    private fun fadeIn(e: Encoder) { val fi = recFi(); if (fi <= 0f) { prog.dim = 0f; e.vol = 1f; return }; prog.dim = 1f; e.vol = 0f; ramp(true, fi) { } }
    private fun fadeOut(then: () -> Unit) {
        val fo = recFo(); val e = enc
        if (fo <= 0f || e == null || e.paused || fadeOutDone) { fadeOutDone = false; then(); return }
        fading = true; ramp(false, fo) { fading = false; fadeOutDone = true; then(); fadeOutDone = false }
    }
    private fun stopRec() {
        val e = enc ?: return
        if (ending || fading) return
        if (sp.getBoolean("outro", false)) {
            ending = true; if (e.paused) togglePause()
            prog.setOverlay(brand.card(W, H, true, sp.getString("outroTxt", "") ?: ""))
            recBtn.text = t("Closing…", "Penutup…"); recBtn.isEnabled = false
            main.postDelayed({ fadeOut { finishStop(e); prog.dim = 0f } }, 3000)
        } else fadeOut { finishStop(e); prog.dim = 0f }
    }
    private fun finishStop(e: Encoder) {
        recBtn.isEnabled = false; recBtn.text = t("Saving…", "Menyimpan…")
        prog.detachEncoder { e.stop { ok -> main.post { finishRec(ok) } } }
    }
    private fun finishRec(ok: Boolean) {
        enc = null; ending = false; refreshOverlay()
        try { recPfd?.close() } catch (e: Exception) {}
        recPfd = null
        val part = recFile; recFile = null
        if (!ok) part?.delete()
        val base = resumeFile
        if (ok && base != null && part != null) joinInto(base, part)
        if (resumeFile != null && useStill()) { prog.holdLast(); stillReady = true }
        recBtn.isEnabled = true; recBtn.text = "● " + t("Start Recording", "Mula Rakam"); pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f; pauseBtn.text = "‖ " + t("Pause", "Jeda"); qBtn.isEnabled = true
        timeTx.visibility = View.GONE
        sendLive(); srcs.forEach { it.data?.send(mapOf("t" to "q", "hi" to false)) }
        toast(if (ok) t("✅ Saved in Videos. Tap Save to Phone for your gallery.", "✅ Disimpan dalam Video. Tekan Simpan ke Telefon untuk galeri.") else t("Recording failed", "Rakaman gagal"))
    }

    /* Continue: the new part is added to the end of the earlier video, which keeps its name */
    private fun joinInto(base: File, part: File) {
        toast(t("Adding to the same video…", "Menambah ke video yang sama…"))
        joinQ.execute {
            val tmp = File(base.parentFile, "tmp_" + base.name)
            val ok = try { ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { Joiner.join(this, listOf(Uri.fromFile(base), Uri.fromFile(part)), it.fileDescriptor) } } catch (e: Exception) { false }
            if (ok) { base.delete(); part.delete(); tmp.renameTo(base) } else tmp.delete()
            main.post { toast(if (ok) t("Added — one longer video", "Ditambah — satu video lebih panjang") else t("Could not join; the new part is kept as its own video", "Gagal gabung; bahagian baru disimpan berasingan")) }
        }
    }

    private fun focusAt(stage: FrameLayout, x: Float, y: Float, auto: Boolean) {
        val s = mainSrc ?: return
        var w = stage.width.toFloat(); var hh = w * H / W; if (hh > stage.height) { hh = stage.height.toFloat(); w = hh * W / H }
        val ox = (stage.width - w) / 2; val oy = (stage.height - hh) / 2
        var u = (x - ox) / w; var v = (y - oy) / hh
        if (u < 0 || u > 1 || v < 0 || v > 1) return
        u = 0.5f + (u - 0.5f) / s.zoom; v = 0.5f + (v - 0.5f) / s.zoom
        if (s.local) { val c = cam ?: return; if (!Focus.tap(c.capturer, u, v, c.front, auto)) { toast(t("This camera cannot focus by tap", "Kamera ini tidak boleh fokus dengan tekan")); return } }
        else { val d = s.data ?: return; d.send(if (auto) mapOf("t" to "cc", "op" to "auto") else mapOf("t" to "cc", "op" to "at", "v" to mapOf("x" to u.toDouble(), "y" to v.toDouble()))) }
        if (auto) toast(t("Auto focus", "Fokus automatik")) else Focus.ring(stage, x, y)
    }

    private val joinQ = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var stillReady = false
    /* the last picture of the video being continued, so the new part starts with the switch effect */
    private fun loadStill() {
        if (!useStill()) return
        val f = resumeFile ?: return
        Thread {
            val b = try { val r = android.media.MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
                val d = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                val fr = r.getFrameAtTime(maxOf(0L, d - 50_000), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(maxOf(0L, d - 50_000)); r.release()
                fr?.let { android.graphics.Bitmap.createScaledBitmap(it, W, H, true) } } catch (e: Exception) { null }
            if (b != null) main.post { prog.fromStill(b); stillReady = true }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
