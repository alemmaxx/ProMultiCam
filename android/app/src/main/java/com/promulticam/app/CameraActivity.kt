package com.promulticam.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.promulticam.app.Ui.t
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/* Camera phone: sends its picture and sound to a Director (web or native) using the 6-digit Studio ID */
class CameraActivity : AppCompatActivity(), PeerSignal.Listener, PeerLink.Cb {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var setup: LinearLayout
    private lateinit var live: FrameLayout
    private lateinit var view: SurfaceViewRenderer
    private lateinit var codeIn: EditText
    private lateinit var labelIn: EditText
    private lateinit var status: TextView
    private lateinit var onAir: TextView
    private lateinit var scanBox: FrameLayout
    @Volatile private var scanning = false
    private var lastScan = 0L
    private lateinit var tpBox: FrameLayout
    private lateinit var tpTx: TextView
    private var tpY = 0f; private var tpRun = false; private var tpSpd = 4f; private var tpLast = 0L
    private var cam: Rtc.Cam? = null
    private var mic: AudioTrack? = null
    private var sig: PeerSignal? = null
    private var dataLink: PeerLink? = null
    private var mediaLink: PeerLink? = null
    private var target = ""
    private var stopped = false
    private var kicked = false
    private val sp by lazy { getSharedPreferences("pmc", Context.MODE_PRIVATE) }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) startCam() else { toast(t("Camera permission is needed", "Perlu izin kamera")); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Ui.init(this); Rtc.init(this)
        setContentView(buildUi())
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCam() else perms.launch(need)
    }

    override fun onDestroy() {
        super.onDestroy(); stopped = true; main.removeCallbacksAndMessages(null)
        dataLink?.close(); mediaLink?.close(); sig?.close()
        cam?.let { it.track.removeSink(view); it.stop() }; mic?.dispose(); view.release()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this); root.setBackgroundColor(Color.BLACK)
        view = SurfaceViewRenderer(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        // tap = focus there; hold = auto focus again
        val tapper = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { val c = cam ?: return true
                if (Focus.tap(c.capturer, e.x / root.width, e.y / root.height, false)) Focus.ring(root, e.x, e.y); return true }
            override fun onLongPress(e: android.view.MotionEvent) { val c = cam ?: return; Focus.tap(c.capturer, 0.5f, 0.5f, c.front, true); toast(t("Auto focus", "Fokus automatik")) }
        })
        view.setOnTouchListener { _, e -> tapper.onTouchEvent(e) }
        live = FrameLayout(this)
        val d = Ui.dp(this, 14f).toInt()
        onAir = Ui.label(this, "● LIVE", 14f, Color.WHITE, true); onAir.setPadding(d, d / 2, d, d / 2); onAir.background = Ui.round(0xFFD23C3C.toInt(), Ui.dp(this, 99f)); onAir.visibility = View.GONE
        live.addView(onAir, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = d * 3 })
        status = Ui.label(this, "", 14f, Color.WHITE, true); status.setPadding(d, d / 2, d, d / 2); status.background = Ui.round(0xAA000000.toInt(), Ui.dp(this, 10f))
        live.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = d; bottomMargin = d * 2 })
        val bar = LinearLayout(this); bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(Ui.btn(this, "🔄") { cam?.let { it.flip(); view.setMirror(it.front) } })
        bar.addView(Ui.btn(this, t("Leave", "Keluar")) { finish() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        live.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = d; bottomMargin = d * 2 })
        // teleprompter: the script scrolls here only, never in the video
        tpBox = FrameLayout(this); tpBox.setBackgroundColor(0xB0000000.toInt()); tpBox.visibility = View.GONE; tpBox.clipChildren = true
        tpTx = Ui.label(this, "", 30f, Color.WHITE, true); tpTx.setPadding(d * 2, 0, d * 2, 0)
        tpBox.addView(tpTx, FrameLayout.LayoutParams(-1, -2))
        tpBox.setOnClickListener { tpRun = !tpRun; if (tpRun) tpTick() }
        live.addView(tpBox, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.38f).toInt(), Gravity.TOP).apply { topMargin = d * 6 })
        live.visibility = View.GONE
        root.addView(live, FrameLayout.LayoutParams(-1, -1))

        setup = LinearLayout(this); setup.orientation = LinearLayout.VERTICAL; setup.setPadding(d, d * 2, d, d * 2); setup.setBackgroundColor(Ui.BG)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.iconBtn(this, R.drawable.ic_back) { finish() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 46)))
        val tt = LinearLayout(this); tt.orientation = LinearLayout.VERTICAL; tt.setPadding(d, 0, 0, 0)
        tt.addView(Ui.label(this, t("CAMERA", "KAMERA"), 13f, Ui.INFO, true)); tt.addView(Ui.label(this, t("Connect to a Director", "Sambung ke Pengarah"), 20f, Ui.TEXT, true))
        top.addView(tt); setup.addView(top)
        val card = Ui.card(this)
        card.addView(Ui.label(this, t("Enter Studio ID", "Masukkan ID Studio"), 19f, Ui.TEXT, true))
        card.addView(Ui.hint(this, t("Type the Director's 6-digit ID or scan its QR.", "Taip ID 6 digit Pengarah atau imbas QR.")))
        val jr = LinearLayout(this); jr.orientation = LinearLayout.HORIZONTAL; jr.gravity = Gravity.CENTER_VERTICAL
        codeIn = Ui.input(this, t("Studio ID", "ID Studio"), sp.getString("lastCode", "") ?: "", InputType.TYPE_CLASS_NUMBER); codeIn.textSize = 20f
        jr.addView(codeIn, LinearLayout.LayoutParams(0, -2, 1f))
        jr.addView(Ui.btn(this, t("Join", "Sertai"), true) { join() }, LinearLayout.LayoutParams(-2, Ui.px(this, 58)).apply { leftMargin = d / 2 })
        card.addView(jr, Ui.lp(this, 12))
        card.addView(Ui.center(Ui.label(this, t("— or —", "— atau —"), 14f, Ui.SUB)).apply { layoutParams = Ui.lp(this@CameraActivity, 10) })
        card.addView(Ui.btn(this, t("Scan the Director's QR", "Imbas QR Pengarah")) { startScan() }, Ui.lp(this, 10))
        setup.addView(card, Ui.lp(this, 18))
        setup.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        setup.addView(Ui.label(this, t("Angle label for this camera", "Label sudut untuk kamera ini"), 14f, Ui.SUB, true))
        labelIn = Ui.input(this, t("e.g. Wok, Wide, Side", "cth: Kuali, Jauh, Sisi"), sp.getString("camLabel", "") ?: ""); setup.addView(labelIn, Ui.lp(this, 6))
        setup.addView(Ui.hint(this, t("Shown on the Director's screen.", "Dipapar di skrin Pengarah.")))
        // QR scanning: the camera picture with a gold frame
        scanBox = FrameLayout(this); scanBox.visibility = View.GONE
        val frame = View(this); frame.background = Ui.round(0, Ui.dp(this, 18f), Ui.GOLD, Ui.px(this, 3))
        scanBox.addView(frame, Ui.fl(Ui.px(this, 240), Ui.px(this, 240), Gravity.CENTER))
        scanBox.addView(Ui.center(Ui.label(this, t("Point at the QR on the Director", "Halakan ke QR di Pengarah"), 15f, Color.WHITE, true)), Ui.fl(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = Ui.px(this@CameraActivity, 80) })
        scanBox.addView(Ui.btn(this, t("Cancel", "Batal")) { stopScan() }, Ui.fl(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.px(this@CameraActivity, 40) })
        root.addView(scanBox, Ui.fl(-1, -1))
        root.addView(setup, FrameLayout.LayoutParams(-1, -1))
        return root
    }
    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@CameraActivity, top.toFloat()).toInt() }

    private fun startCam() {
        view.init(Rtc.egl.eglBaseContext, null)
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        cam = Rtc.camera(this, false, 1280, 720, "cam")
        cam?.track?.addSink(view)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) mic = Rtc.mic("cam")
    }

    private fun join() {
        val code = codeIn.text.toString().filter { it.isDigit() }
        if (code.length != 6) { toast(t("Type the 6-digit Studio ID", "Taip ID Studio 6 digit")); return }
        sp.edit().putString("lastCode", code).putString("camLabel", labelIn.text.toString().trim()).apply()
        target = "pmc2-code-$code"
        setup.visibility = View.GONE; live.visibility = View.VISIBLE
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(codeIn.windowToken, 0)
        connect()
    }
    private fun label() = labelIn.text.toString().trim().ifEmpty { Build_name() }
    @Suppress("FunctionName") private fun Build_name() = android.os.Build.MODEL ?: t("Camera", "Kamera")
    private fun via(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "wifi"
        return if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "data" else "wifi"
    }

    /* ---------- connection ---------- */
    private fun connect() {
        if (stopped || kicked) return
        status.text = t("Connecting…", "Menyambung…")
        dataLink?.close(); mediaLink?.close(); dataLink = null; mediaLink = null
        if (sig?.open == true) { onOpen(sig!!.id); return }
        sig?.close(); sig = PeerSignal(null, this).also { it.connect() }
    }
    override fun onOpen(id: String) {
        val s = sig ?: return
        dataLink = PeerLink(s, target, PeerLink.newId("dc_"), "data", this).also { it.offer(null) }
        main.removeCallbacks(retry); main.postDelayed(retry, 12000)
    }
    private val retry = Runnable { if (dataLink?.let { !it.closed && mediaLink != null } != true) lost() }
    override fun onMsg(type: String, src: String, payload: JSONObject) {
        val cid = payload.optString("connectionId")
        val link = listOfNotNull(dataLink, mediaLink).firstOrNull { it.connectionId == cid } ?: return
        when (type) {
            "ANSWER" -> link.gotAnswer(payload)
            "CANDIDATE" -> link.gotCandidate(payload)
            "LEAVE", "EXPIRE" -> lost()
        }
    }
    override fun onClosed() { if (!stopped) main.postDelayed({ sig = null; connect() }, 3000) }

    override fun onOpen(link: PeerLink) {
        if (link !== dataLink) return
        link.send(mapOf("t" to "hello", "name" to label(), "via" to via()))
        link.send(mapOf("t" to "rot", "v" to 0))
        val s = sig ?: return
        val m = PeerLink(s, target, PeerLink.newId("mc_"), "media", this)
        cam?.let { m.pc.addTrack(it.track, listOf("pmc")) }
        mic?.let { m.pc.addTrack(it, listOf("pmc")) }
        mediaLink = m
        m.offer(JSONObject().put("name", label()).put("via", via()))
        sendBattery()
    }
    override fun onData(link: PeerLink, msg: Map<String, Any?>) {
        when (msg["t"]) {
            "welcome" -> status.text = t("Connected to ", "Bersambung ke ") + (msg["name"] ?: t("Director", "Pengarah"))
            "live" -> onAir.visibility = if (msg["on"] == true) View.VISIBLE else View.GONE
            "tp" -> tp(msg)
            "cc" -> cam?.let { c -> when (msg["op"]) {
                "at" -> { val v = msg["v"] as? Map<*, *>; val x = (v?.get("x") as? Number)?.toFloat() ?: 0.5f; val y = (v?.get("y") as? Number)?.toFloat() ?: 0.5f
                    Focus.tap(c.capturer, x, y, c.front); val r = view.parent as? FrameLayout; if (r != null) Focus.ring(r, x * r.width, y * r.height) }
                "auto" -> Focus.tap(c.capturer, 0.5f, 0.5f, c.front, true)
                "lock" -> if (msg["v"] == false) Focus.tap(c.capturer, 0.5f, 0.5f, c.front, true) } }
            "full" -> { kicked = true; toast(t("Studio full", "Studio penuh")); main.postDelayed({ finish() }, 3000) }
            "kick" -> { kicked = true; toast(t("Removed by the Director", "Dikeluarkan oleh Pengarah")); main.postDelayed({ finish() }, 1500) }
        }
    }
    override fun onTrack(link: PeerLink, track: MediaStreamTrack) {}
    override fun onClosed(link: PeerLink) { if (link === dataLink || link === mediaLink) lost() }

    private fun lost() {
        if (stopped || kicked) return
        onAir.visibility = View.GONE; status.text = t("Connection lost — retrying…", "Sambungan putus — cuba semula…")
        main.removeCallbacks(reconnect); main.postDelayed(reconnect, 3000)
    }
    private val reconnect = Runnable { connect() }

    private fun sendBattery() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        dataLink?.send(mapOf("t" to "stat", "batt" to (lvl * 100 / maxOf(1, sc)), "charging" to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)))
        main.postDelayed({ if (!stopped) sendBattery() }, 60000)
    }

    private fun tp(m: Map<String, Any?>) {
        val cmd = m["cmd"] as? String
        if (cmd == "hide") { tpRun = false; tpBox.visibility = View.GONE; return }
        (m["text"] as? String)?.let { if (it != tpTx.text.toString()) { tpTx.text = it; tpY = tpBox.height * 0.3f } }
        (m["size"] as? Number)?.let { tpTx.textSize = it.toFloat() * 0.9f }
        (m["speed"] as? Number)?.let { tpSpd = it.toFloat() }
        tpBox.visibility = View.VISIBLE
        when (cmd) { "reset" -> { tpY = tpBox.height * 0.3f; tpRun = false }; "pause" -> tpRun = false; "start" -> { tpRun = true; tpLast = 0L; tpTick() } }
        tpTx.translationY = tpY
    }
    private fun tpTick() {
        if (!tpRun || stopped) return
        val now = System.currentTimeMillis(); val dt = if (tpLast == 0L) 16L else minOf(100L, now - tpLast); tpLast = now
        tpY -= tpSpd * 9 * resources.displayMetrics.density * dt / 1000f
        if (tpY < -tpTx.height) tpRun = false
        tpTx.translationY = tpY
        main.postDelayed({ tpTick() }, 16)
    }

    /* ---------- QR scan (reads the Director's QR from this camera's own pictures) ---------- */
    private val qrSink = org.webrtc.VideoSink { f ->
        if (!scanning) return@VideoSink
        val now = System.currentTimeMillis(); if (now - lastScan < 350) return@VideoSink; lastScan = now
        f.retain()
        Thread {
            try {
                val b = f.buffer.toI420() ?: return@Thread
                val w = b.width; val h = b.height; val y = ByteArray(w * h); val src = b.dataY; val st = b.strideY
                for (r in 0 until h) { src.position(r * st); src.get(y, r * w, w) }
                b.release()
                val lum = com.google.zxing.PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
                val res = com.google.zxing.MultiFormatReader().apply { setHints(mapOf(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE), com.google.zxing.DecodeHintType.TRY_HARDER to true)) }
                    .decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(lum)))
                val m = Regex("join=(\\d{6})").find(res.text) ?: Regex("^(\\d{6})$").find(res.text.trim())
                if (m != null) main.post { if (scanning) { stopScan(); codeIn.setText(m.groupValues[1]); toast(t("Studio found. Tap Join.", "Studio dijumpai. Tekan Sertai.")); join() } }
            } catch (e: Exception) {} finally { f.release() }
        }.start()
    }
    private fun startScan() { cam?.track?.addSink(qrSink); scanning = true; setup.visibility = View.GONE; scanBox.visibility = View.VISIBLE }
    private fun stopScan() { scanning = false; cam?.track?.removeSink(qrSink); scanBox.visibility = View.GONE; if (live.visibility != View.VISIBLE) setup.visibility = View.VISIBLE }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
