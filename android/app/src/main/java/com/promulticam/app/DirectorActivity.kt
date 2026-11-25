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
    private lateinit var codeTx: TextView
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

    private val W get() = if (brand.hd) 1080 else 720
    private val H get() = if (brand.hd) 1920 else 1280
    private var layout: String get() = sp.getString("layout", "full") ?: "full"; set(v) { sp.edit().putString("layout", v).apply() }
    private var trans: String get() = sp.getString("trans", "fade") ?: "fade"; set(v) { sp.edit().putString("trans", v).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Rtc.init(this)
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

    /* ---------- screen ---------- */
    private fun chip(label: String, onClick: () -> Unit): Button {
        val b = Ui.btn(this, label, false, onClick); b.textSize = 13f
        val p = Ui.dp(this, 8f).toInt(); b.setPadding(p, p / 2, p, p / 2); b.minHeight = 0; b.minimumHeight = 0
        return b
    }
    private fun buildUi(): View {
        val d = Ui.dp(this, 10f).toInt()
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Ui.BG)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(d, d * 3, d, d / 2)
        top.addView(Ui.btn(this, "‹") { finish() })
        codeTx = Ui.label(this, "…", 15f, Ui.GOLD, true); codeTx.setPadding(d, 0, d, 0)
        top.addView(codeTx, LinearLayout.LayoutParams(0, -2, 1f))
        qBtn = Ui.btn(this, if (brand.hd) "1080p" else "720p") { if (enc == null) { brand.hd = !brand.hd; qBtn.text = if (brand.hd) "1080p" else "720p"; prog.resize(W, H); refreshOverlay() } }
        top.addView(qBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        top.addView(Ui.btn(this, "🔄") { cam?.flip() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        top.addView(Ui.btn(this, "🎨") { BrandUi.show(this, brand, { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { refreshOverlay() } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        root.addView(top)

        // the program picture
        val stage = FrameLayout(this); stage.setBackgroundColor(Color.BLACK)
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { prog.setPreview(h.surface, w, hh) }
            override fun surfaceDestroyed(h: SurfaceHolder) { prog.setPreview(null, 0, 0) }
        })
        stage.addView(sv, FrameLayout.LayoutParams(-1, -1))
        timeTx = Ui.label(this, "", 14f, Color.WHITE, true); timeTx.setPadding(d, d / 3, d, d / 3); timeTx.background = Ui.round(0xFFD23C3C.toInt(), Ui.dp(this, 99f)); timeTx.visibility = View.GONE
        stage.addView(timeTx, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = d; topMargin = d })
        fpsTx = Ui.label(this, "", 11f, Color.WHITE, true); fpsTx.setPadding(d / 2, d / 4, d / 2, d / 4); fpsTx.background = Ui.round(0x88000000.toInt(), Ui.dp(this, 8f))
        stage.addView(fpsTx, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = d; topMargin = d })
        zoomBar = SeekBar(this); zoomBar.max = 100
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, user: Boolean) { if (!user) return; val z = 1f + p / 100f * 3f; mainSrc?.zoom = z; prog.zoomMain = z }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        stage.addView(zoomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = d * 2; rightMargin = d * 2; bottomMargin = d })
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // style / layout / switch / studio
        val hs0 = HorizontalScrollView(this); hs0.isHorizontalScrollBarEnabled = false
        val opts = LinearLayout(this); opts.orientation = LinearLayout.HORIZONTAL; opts.setPadding(d, d / 2, d, 0)
        lookBtn = chip("🎨 " + lookName(look)) { pickLook() }
        layBtn = chip(layName()) { cycleLayout() }
        trBtn = chip(trName()) { cycleTrans() }
        for (b in listOf(lookBtn, layBtn, trBtn, chip("⭐ Studio") { studioDialog() })) opts.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d / 2 })
        hs0.addView(opts); root.addView(hs0)

        // camera tiles
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
        tiles = LinearLayout(this); tiles.orientation = LinearLayout.HORIZONTAL; tiles.setPadding(d, d / 2, d, 0)
        hs.addView(tiles)
        root.addView(hs, LinearLayout.LayoutParams(-1, Ui.dp(this, 104f).toInt()))

        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.setPadding(d, d, d, d * 2)
        pauseBtn = Ui.btn(this, t("Pause", "Jeda")) { togglePause() }; pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f
        recBtn = Ui.btn(this, t("● Record", "● Rakam"), true) { if (enc == null) startRec() else stopRec() }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(recBtn, LinearLayout.LayoutParams(0, -2, 1.6f).apply { leftMargin = d; rightMargin = d })
        row.addView(Ui.btn(this, t("Videos", "Video")) { startActivity(Intent(this, VideosActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        fpsLoop()
        return root
    }
    private fun fpsLoop() { fpsTx.text = "${prog.fps} fps"; main.postDelayed({ if (!stopped) fpsLoop() }, 1000) }

    private fun lookName(l: Looks.Look) = if (Ui.ms) l.ms else l.en
    private fun layName() = when (layout) { "split" -> t("▦ Split", "▦ Belah"); "pip" -> t("▣ Small frame", "▣ Bingkai kecil"); else -> t("▢ Full", "▢ Penuh") }
    private fun trName() = when (trans) { "black" -> t("✦ Dip to black", "✦ Gelap"); "cut" -> t("✦ Straight cut", "✦ Terus"); else -> t("✦ Soft fade", "✦ Lembut") }
    private fun pickLook() {
        val names = Looks.ALL.map { lookName(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(t("Visual style", "Gaya visual")).setSingleChoiceItems(names, Looks.ALL.indexOf(look)) { dlg, i ->
            look = Looks.ALL[i]; sp.edit().putString("look", look.id).apply(); prog.setLook(look.f); lookBtn.text = "🎨 " + lookName(look); refreshOverlay(); dlg.dismiss()
        }.show()
    }
    private fun cycleLayout() {
        if (srcs.count { it.track != null } < 2 && layout == "full") { toast(t("Needs 2 cameras", "Perlu 2 kamera")); return }
        layout = when (layout) { "full" -> "split"; "split" -> "pip"; else -> "full" }
        prog.layout = layout; layBtn.text = layName(); pickSecond()
    }
    private fun cycleTrans() { trans = when (trans) { "fade" -> "black"; "black" -> "cut"; else -> "fade" }; prog.trans = trans; trBtn.text = trName() }

    private fun refreshOverlay() { prog.setOverlay(brand.render(W, H, look)) }

    /* ---------- Studio: title & closing cards, teleprompter ---------- */
    private fun studioDialog() {
        val d14 = Ui.dp(this, 14f).toInt()
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(d14 * 2, d14, d14 * 2, 0)
        val intro = CheckBox(this); intro.text = t("Title card (3s)", "Kad tajuk (3s)"); intro.isChecked = sp.getBoolean("intro", false); col.addView(intro)
        val outro = CheckBox(this); outro.text = t("Closing card (3s)", "Kad penutup (3s)"); outro.isChecked = sp.getBoolean("outro", false); col.addView(outro)
        val ot = EditText(this); ot.hint = t("Closing text, e.g. Order now!", "Teks penutup, cth. Tempah sekarang!"); ot.setText(sp.getString("outroTxt", "")); col.addView(ot)
        col.addView(Ui.label(this, t("Teleprompter (camera phones)", "Teleprompter (telefon Kamera)"), 15f, Ui.GOLD, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d14 })
        val tp = EditText(this); tp.hint = t("Your script…", "Skrip anda…"); tp.minLines = 3; tp.setText(sp.getString("tpText", "")); col.addView(tp)
        val spd = SeekBar(this); spd.max = 9; spd.progress = sp.getInt("tpSpeed", 4) - 1; col.addView(Ui.label(this, t("Speed", "Laju"), 13f, Ui.SUB)); col.addView(spd)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        fun tpSend(cmd: String) {
            sp.edit().putString("tpText", tp.text.toString()).putInt("tpSpeed", spd.progress + 1).apply()
            val m = mapOf("t" to "tp", "cmd" to cmd, "text" to tp.text.toString(), "speed" to (spd.progress + 1), "size" to 34)
            srcs.forEach { it.data?.send(m) }
            if (srcs.none { it.data != null }) toast(t("Connect a camera phone first", "Sambung telefon Kamera dahulu"))
        }
        for ((l, c) in listOf(t("Start", "Mula") to "start", t("Pause", "Jeda") to "pause", t("Top", "Atas") to "reset", t("Hide", "Sorok") to "hide"))
            row.addView(chip(l) { tpSend(c) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = d14 / 3 })
        col.addView(row)
        AlertDialog.Builder(this).setTitle("Studio").setView(col).setPositiveButton(t("Save", "Simpan")) { _, _ ->
            sp.edit().putBoolean("intro", intro.isChecked).putBoolean("outro", outro.isChecked).putString("outroTxt", ot.text.toString()).apply()
        }.show()
    }

    /* ---------- cameras ---------- */
    private fun startAll() {
        refreshOverlay()
        cam = Rtc.camera(this, false, 1920, 1080, "dir")
        cam?.let { c -> val s = Src("local", t("Director", "Pengarah"), true); s.track = c.track; addSrc(s) }
        openStudio()
    }

    private fun openStudio() {
        code = (100000 + (Math.random() * 900000).toInt()).toString()
        paintCode()
        sig?.close(); sig = PeerSignal("pmc2-code-$code", this).also { it.connect() }
    }
    private fun paintCode() { codeTx.text = t("Studio ID ", "ID Studio ") + code + "  ·  " + srcs.size + " " + t("cam", "kamera") }
    override fun onOpen(id: String) { paintCode() }
    override fun onIdTaken() { openStudio() }
    override fun onClosed() { if (!stopped) main.postDelayed({ if (!stopped) { sig?.close(); sig = PeerSignal("pmc2-code-$code", this).also { it.connect() } } }, 3000) }

    override fun onMsg(type: String, src: String, payload: JSONObject) {
        val cid = payload.optString("connectionId")
        when (type) {
            "OFFER" -> {
                val ty = payload.optString("type")
                if (srcs.count { !it.local } >= 6 && srcs.none { it.id == src }) return
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
                link.send(mapOf("t" to "fmt", "v" to "p"))
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
        tiles.addView(f, LinearLayout.LayoutParams(Ui.dp(this, 56f).toInt(), -1).apply { rightMargin = d })
        s.tile = f; s.tileView = v
        s.track?.addSink(v)
        if (mainSrc == null && s.track != null) setMain(s)
        paintTiles(); paintCode()
    }
    private fun label(s: Src) { (s.tile?.findViewWithTag<TextView>("lbl"))?.text = s.name }

    /* full: tap = main angle. split / small frame: tap another = second view, tap the main one = swap */
    private fun tap(s: Src) {
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
        pickSecond(); paintTiles(); sendLive()
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
        recBtn.text = t("■ Stop", "■ Tamat"); pauseBtn.isEnabled = true; pauseBtn.alpha = 1f; qBtn.isEnabled = false
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
        if (e.paused) { e.resume(); pausedMs += System.currentTimeMillis() - pauseAt; pauseAt = 0; pauseBtn.text = t("Pause", "Jeda") }
        else { e.pause(); pauseAt = System.currentTimeMillis(); pauseBtn.text = t("Resume", "Sambung") }
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
        recBtn.isEnabled = true; recBtn.text = t("● Record", "● Rakam"); pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f; pauseBtn.text = t("Pause", "Jeda"); qBtn.isEnabled = true
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
