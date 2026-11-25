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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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

/* Director: gathers every camera (this phone + camera phones, web or native), shows them as tiles,
   one tap picks the angle, and records the program with the brand drawn in on the GPU. */
class DirectorActivity : AppCompatActivity(), PeerSignal.Listener, PeerLink.Cb {
    private class Src(val id: String, var name: String, val local: Boolean) {
        var track: VideoTrack? = null
        var tile: FrameLayout? = null
        var tileView: SurfaceViewRenderer? = null
        var data: PeerLink? = null
        var media: PeerLink? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var brand: Brand
    private lateinit var mainView: SurfaceViewRenderer
    private lateinit var ovView: ImageView
    private lateinit var stage: FrameLayout
    private lateinit var tiles: LinearLayout
    private lateinit var codeTx: TextView
    private lateinit var timeTx: TextView
    private lateinit var recBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var qBtn: Button
    private val srcs = ArrayList<Src>()
    private val links = HashMap<String, PeerLink>()
    private var mainSrc: Src? = null
    private var sig: PeerSignal? = null
    private var code = ""
    private var cam: Rtc.Cam? = null
    private var rec: Compositor? = null
    private var recUri: Uri? = null
    private var recFile: File? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recStart = 0L
    private var pausedMs = 0L
    private var pauseAt = 0L
    private var lastUri: Uri? = null
    private var stopped = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Rtc.init(this)
        brand = Brand(this); brand.refresh()
        setContentView(buildUi())
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startAll() else perms.launch(need)
    }

    override fun onDestroy() {
        super.onDestroy(); stopped = true; main.removeCallbacksAndMessages(null)
        rec?.let { r -> mainSrc?.track?.removeSink(r); r.stop { } }
        links.values.toList().forEach { it.close() }; sig?.close()
        srcs.forEach { s -> s.tileView?.let { v -> s.track?.removeSink(v); v.release() } }
        mainSrc?.track?.removeSink(mainView); mainView.release()
        cam?.stop()
    }

    /* ---------- screen ---------- */
    private fun buildUi(): View {
        val d = Ui.dp(this, 10f).toInt()
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Ui.BG)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(d, d * 3, d, d)
        top.addView(Ui.btn(this, "‹") { finish() })
        codeTx = Ui.label(this, "…", 15f, Ui.GOLD, true); codeTx.setPadding(d, 0, d, 0)
        top.addView(codeTx, LinearLayout.LayoutParams(0, -2, 1f))
        qBtn = Ui.btn(this, if (brand.hd) "1080p" else "720p") { if (rec == null) { brand.hd = !brand.hd; qBtn.text = if (brand.hd) "1080p" else "720p"; refreshOverlay() } }
        top.addView(qBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        top.addView(Ui.btn(this, "🔄") { cam?.let { it.flip(); srcs.firstOrNull { s -> s.local }?.tileView?.setMirror(it.front); if (mainSrc?.local == true) mainView.setMirror(it.front) } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        top.addView(Ui.btn(this, "🎨") { BrandUi.show(this, brand, { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { refreshOverlay() } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        root.addView(top)

        // the 9:16 program view
        val holder = FrameLayout(this)
        stage = object : FrameLayout(this) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                val pw = MeasureSpec.getSize(wSpec); val ph = MeasureSpec.getSize(hSpec)
                var w = pw; var h = w * 16 / 9; if (h > ph) { h = ph; w = h * 9 / 16 }
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
        }
        stage.setBackgroundColor(Color.BLACK)
        mainView = SurfaceViewRenderer(this)
        stage.addView(mainView, FrameLayout.LayoutParams(-1, -1))
        ovView = ImageView(this); ovView.scaleType = ImageView.ScaleType.FIT_XY
        stage.addView(ovView, FrameLayout.LayoutParams(-1, -1))
        timeTx = Ui.label(this, "", 14f, Color.WHITE, true); timeTx.setPadding(d, d / 3, d, d / 3); timeTx.background = Ui.round(0xFFD23C3C.toInt(), Ui.dp(this, 99f)); timeTx.visibility = View.GONE
        stage.addView(timeTx, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = d; topMargin = d })
        holder.addView(stage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))

        // camera tiles
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
        tiles = LinearLayout(this); tiles.orientation = LinearLayout.HORIZONTAL; tiles.setPadding(d, d, d, 0)
        hs.addView(tiles)
        root.addView(hs, LinearLayout.LayoutParams(-1, Ui.dp(this, 118f).toInt()))

        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.setPadding(d, d, d, d * 2)
        pauseBtn = Ui.btn(this, t("Pause", "Jeda")) { togglePause() }; pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f
        recBtn = Ui.btn(this, t("● Record", "● Rakam"), true) { if (rec == null) startRec() else stopRec() }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(recBtn, LinearLayout.LayoutParams(0, -2, 1.6f).apply { leftMargin = d; rightMargin = d })
        row.addView(Ui.btn(this, t("Video", "Video")) { openLast() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        return root
    }

    private fun refreshOverlay() {
        val b = brand.render(W, H)
        ovView.setImageBitmap(b)
        rec?.setOverlay(b)
    }

    /* ---------- cameras ---------- */
    private fun startAll() {
        mainView.init(Rtc.egl.eglBaseContext, null)
        mainView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        mainView.setEnableHardwareScaler(true)
        refreshOverlay()
        cam = Rtc.camera(this, false, 1920, 1080, "dir")
        cam?.let { c -> val s = Src("local", t("Director", "Pengarah"), true); s.track = c.track; addSrc(s) }
        openStudio()
    }

    private fun openStudio() {
        code = (100000 + (Math.random() * 900000).toInt()).toString()
        codeTx.text = t("Studio ID: ", "ID Studio: ") + code
        sig?.close(); sig = PeerSignal("pmc2-code-$code", this).also { it.connect() }
    }
    override fun onOpen(id: String) { codeTx.text = t("Studio ID: ", "ID Studio: ") + code + "  ·  " + srcs.size + " " + t("cam", "kamera") }
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
                link.send(mapOf("t" to "q", "hi" to (rec != null)))
                sendLive()
                toast(s.name + t(" joined", " menyertai"))
            }
            "probe" -> link.send(mapOf("t" to "info", "name" to t("Director", "Pengarah"), "cams" to srcs.size, "code" to code))
        }
    }
    override fun onTrack(link: PeerLink, track: MediaStreamTrack) {
        if (track is AudioTrack) { track.setEnabled(false); return }   // no echo: the Director records its own mic
        if (track !is VideoTrack) return
        val s = srcFor(link.peer); s.track = track
        s.tileView?.let { track.addSink(it) }
        if (mainSrc == null || mainSrc === s) setMain(s)
        onOpen(sig?.id ?: "")
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
        f.setOnClickListener { setMain(s) }
        v.setOnClickListener { setMain(s) }
        tiles.addView(f, LinearLayout.LayoutParams(Ui.dp(this, 62f).toInt(), -1).apply { rightMargin = d })
        s.tile = f; s.tileView = v
        s.track?.addSink(v)
        if (mainSrc == null && s.track != null) setMain(s)
        paintTiles(); onOpen("")
    }
    private fun label(s: Src) { (s.tile?.findViewWithTag<TextView>("lbl"))?.text = s.name }

    private fun removeSrc(id: String) {
        val s = srcs.firstOrNull { it.id == id } ?: return
        if (s.local) return
        srcs.remove(s)
        s.data?.close(); s.media?.close()
        s.tileView?.let { v -> s.track?.removeSink(v); v.release() }
        tiles.removeView(s.tile)
        if (mainSrc === s) { mainSrc?.track?.let { t -> t.removeSink(mainView); rec?.let { r -> t.removeSink(r) } }; mainSrc = null; srcs.firstOrNull { it.track != null }?.let { setMain(it) } }
        toast(s.name + t(" left", " keluar")); onOpen("")
    }

    private fun setMain(s: Src) {
        val tr = s.track ?: return
        val old = mainSrc
        if (old === s && old.track === tr) { paintTiles(); return }
        old?.track?.let { t -> try { t.removeSink(mainView) } catch (e: Exception) {}; rec?.let { r -> try { t.removeSink(r) } catch (e: Exception) {} } }
        mainSrc = s
        mainView.setMirror(s.local && cam?.front == true)
        tr.addSink(mainView)
        rec?.let { tr.addSink(it) }
        paintTiles(); sendLive()
    }
    private fun paintTiles() { srcs.forEach { s -> s.tile?.background = Ui.round(if (s === mainSrc) Ui.GOLD else Ui.CARD, Ui.dp(this, 10f)) } }
    private fun sendLive() { srcs.forEach { s -> s.data?.send(mapOf("t" to "live", "on" to (s === mainSrc && rec != null))) } }

    /* ---------- recording ---------- */
    private fun startRec() {
        val tr = mainSrc?.track ?: run { toast(t("No camera yet", "Belum ada kamera")); return }
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
        val r = Compositor(W, H, pfd.fileDescriptor, audio)
        r.setOverlay(brand.render(W, H))
        try { r.start() } catch (e: Exception) { toast(t("Recording failed", "Rakaman gagal")); pfd.close(); return }
        rec = r; tr.addSink(r)
        recStart = System.currentTimeMillis(); pausedMs = 0; pauseAt = 0
        recBtn.text = t("■ Stop", "■ Tamat"); pauseBtn.isEnabled = true; pauseBtn.alpha = 1f; qBtn.isEnabled = false
        timeTx.visibility = View.VISIBLE; tick()
        sendLive(); srcs.forEach { it.data?.send(mapOf("t" to "q", "hi" to true)) }
    }
    private fun tick() {
        val r = rec ?: return
        val ms = (if (pauseAt > 0) pauseAt else System.currentTimeMillis()) - recStart - pausedMs
        val s = ms / 1000
        timeTx.text = String.format(Locale.US, if (r.paused) "‖ %02d:%02d" else "● %02d:%02d", s / 60, s % 60)
        main.postDelayed({ tick() }, 500)
    }
    private fun togglePause() {
        val r = rec ?: return
        if (r.paused) { r.resume(); pausedMs += System.currentTimeMillis() - pauseAt; pauseAt = 0; pauseBtn.text = t("Pause", "Jeda") }
        else { r.pause(); pauseAt = System.currentTimeMillis(); pauseBtn.text = t("Resume", "Sambung") }
    }
    private fun stopRec() {
        val r = rec ?: return
        mainSrc?.track?.removeSink(r)
        rec = null
        recBtn.isEnabled = false; recBtn.text = t("Saving…", "Menyimpan…")
        r.stop { ok -> main.post { finishRec(ok) } }
    }
    private fun finishRec(ok: Boolean) {
        try { recPfd?.close() } catch (e: Exception) {}
        recPfd = null
        recUri?.let { u ->
            if (ok) { val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }; contentResolver.update(u, cv, null, null); lastUri = u }
            else contentResolver.delete(u, null, null)
        }
        recFile?.let { f -> if (ok) { MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null); lastUri = Uri.fromFile(f) } else f.delete() }
        recUri = null; recFile = null
        recBtn.isEnabled = true; recBtn.text = t("● Record", "● Rakam"); pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f; pauseBtn.text = t("Pause", "Jeda"); qBtn.isEnabled = true
        timeTx.visibility = View.GONE
        sendLive(); srcs.forEach { it.data?.send(mapOf("t" to "q", "hi" to false)) }
        toast(if (ok) t("Saved to gallery: Movies/ProMultiCam", "Disimpan ke galeri: Movies/ProMultiCam") else t("Recording failed", "Rakaman gagal"))
    }
    private fun openLast() {
        val u = lastUri ?: run { toast(t("No video yet", "Belum ada video")); return }
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
