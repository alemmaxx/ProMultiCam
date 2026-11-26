package com.promulticam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.promulticam.app.Ui.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Director, stage 1: one camera recorded natively (camera -> GPU -> hardware encoder), brand drawn on the GPU */
class RecordActivity : AppCompatActivity() {
    private lateinit var pv: PreviewView
    private lateinit var brand: Brand
    private lateinit var recBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var timeTx: TextView
    private lateinit var qBtn: Button
    private var camera: Camera? = null
    private var vc: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var paused = false
    private var back = true
    private var lastUri: Uri? = null
    private var overlay: OverlayEffect? = null
    private val ovThread = HandlerThread("pmc-overlay").apply { start() }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) bind()
        else { Toast.makeText(this, t("Camera permission is needed", "Perlu izin kamera"), Toast.LENGTH_LONG).show(); finish() }
    }
    private val pickLogo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: throw Exception("logo")
                brand.setLogo(bmp); toast(t("Logo added", "Logo ditambah"))
            } catch (e: Exception) { toast(t("Could not load the logo", "Gagal muat logo")) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        brand = Brand(this); brand.refresh()
        setContentView(buildUi())
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) bind() else perms.launch(need)
    }

    override fun onDestroy() { super.onDestroy(); recording?.stop(); overlay?.close(); ovThread.quitSafely() }

    /* ---------- screen ---------- */
    private fun buildUi(): ViewGroup {
        val root = FrameLayout(this); root.setBackgroundColor(Color.BLACK)
        pv = PreviewView(this); pv.scaleType = PreviewView.ScaleType.FIT_CENTER
        pv.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))

        val d8 = Ui.dp(this, 8f).toInt(); val d14 = Ui.dp(this, 14f).toInt()
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.setPadding(d14, d14 * 2, d14, d14); top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.btn(this, "‹") { finish() })
        timeTx = Ui.label(this, "", 16f, Color.WHITE, true); timeTx.setPadding(d14, 0, d14, 0)
        top.addView(timeTx, LinearLayout.LayoutParams(0, -2, 1f))
        qBtn = Ui.btn(this, if (brand.hd) "1080p" else "720p") { if (recording == null) { brand.hd = !brand.hd; qBtn.text = if (brand.hd) "1080p" else "720p"; bind() } }
        top.addView(qBtn, gap(d8))
        top.addView(Ui.btn(this, "🔄") { if (recording == null) { back = !back; bind() } }, gap(d8))
        top.addView(Ui.btn(this, "🎨") { brandDialog() }, gap(d8))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bot = LinearLayout(this); bot.orientation = LinearLayout.VERTICAL; bot.setPadding(d14, d14, d14, d14 * 2)
        val zoom = SeekBar(this); zoom.max = 100
        zoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { camera?.cameraControl?.setLinearZoom(p / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        bot.addView(zoom, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d14 })
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        pauseBtn = Ui.btn(this, t("Pause", "Jeda")) { togglePause() }; pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f
        recBtn = Ui.btn(this, t("● Record", "● Rakam"), true) { if (recording == null) startRec() else stopRec() }
        val vidBtn = Ui.btn(this, t("Videos", "Video")) { startActivity(Intent(this, VideosActivity::class.java)) }
        row.addView(pauseBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(recBtn, LinearLayout.LayoutParams(0, -2, 1.6f).apply { leftMargin = d8; rightMargin = d8 })
        row.addView(vidBtn, LinearLayout.LayoutParams(0, -2, 1f))
        bot.addView(row)
        root.addView(bot, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return root
    }
    private fun gap(l: Int) = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = l }

    /* ---------- camera ---------- */
    private fun makeOverlay(): OverlayEffect {
        val ov = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, Handler(ovThread.looper)) { }
        ov.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = frame.size.width; val h = frame.size.height; val r = frame.rotationDegrees
            c.save()
            when (r) {
                90 -> { c.translate(0f, h.toFloat()); c.rotate(-90f) }
                180 -> { c.translate(w.toFloat(), h.toFloat()); c.rotate(180f) }
                270 -> { c.translate(w.toFloat(), 0f); c.rotate(90f) }
            }
            val uw = if (r % 180 == 0) w else h; val uh = if (r % 180 == 0) h else w
            if (brand.on) brand.draw(c, uw, uh)
            c.restore()
            true
        }
        return ov
    }

    private fun bind() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val rs = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(rs).build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val q = if (brand.hd) Quality.FHD else Quality.HD
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
            val cap = VideoCapture.withOutput(recorder); vc = cap
            if (overlay == null) overlay = makeOverlay()
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(cap).addEffect(overlay!!).build()
            val sel = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, sel, group)
            } catch (e: Exception) {
                // some phones cannot run the effect: fall back to plain recording (no brand)
                try {
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(this, sel, UseCaseGroup.Builder().addUseCase(preview).addUseCase(cap).build())
                    toast(t("Brand not supported on this phone", "Jenama tidak disokong di telefon ini"))
                } catch (e2: Exception) { toast(t("Camera error", "Ralat kamera")) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /* ---------- recording ---------- */
    @SuppressLint("MissingPermission")
    private fun startRec() {
        val cap = vc ?: return
        val out = androidx.camera.video.FileOutputOptions.Builder(Store.newFile(this, brand.title.ifEmpty { "Video" })).build()
        var pr = cap.output.prepareRecording(this, out)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pr = pr.withAudioEnabled()
        paused = false
        recording = pr.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> { recBtn.text = t("■ Stop", "■ Tamat"); pauseBtn.isEnabled = true; pauseBtn.alpha = 1f }
                is VideoRecordEvent.Status -> { val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000L; timeTx.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60) }
                is VideoRecordEvent.Finalize -> {
                    recording = null; recBtn.text = t("● Record", "● Rakam"); pauseBtn.isEnabled = false; pauseBtn.alpha = 0.5f; pauseBtn.text = t("Pause", "Jeda"); timeTx.text = ""
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) toast(t("Recording failed", "Rakaman gagal"))
                    else { lastUri = ev.outputResults.outputUri; toast(t("✅ Saved in Videos. Tap Save to Phone for your gallery.", "✅ Disimpan dalam Video. Tekan Simpan ke Telefon untuk galeri.")) }
                }
            }
        }
    }
    private fun stopRec() { recording?.stop() }
    private fun togglePause() {
        val r = recording ?: return
        if (paused) { r.resume(); pauseBtn.text = t("Pause", "Jeda") } else { r.pause(); pauseBtn.text = t("Resume", "Sambung") }
        paused = !paused
    }
    private fun openLast() {
        val u = lastUri ?: run { toast(t("No video yet", "Belum ada video")); return }
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /* ---------- brand settings ---------- */
    private fun brandDialog() {
        val d14 = Ui.dp(this, 14f).toInt()
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(d14 * 2, d14, d14 * 2, 0)
        val on = CheckBox(this); on.text = t("Brand in the video", "Jenama dalam video"); on.isChecked = brand.on; col.addView(on)
        fun field(hint: String, v: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText { val e = EditText(this); e.hint = hint; e.setText(v); e.inputType = type; col.addView(e); return e }
        val ti = field(t("Title", "Tajuk"), brand.title)
        val pr = field(t("Price / tagline", "Harga / slogan"), brand.price)
        val ph = field(t("Phone / contact", "Telefon / hubungi"), brand.phone)
        val lb = Ui.btn(this, t("Choose logo", "Pilih logo")) { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        col.addView(lb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d14 })
        val rm = Ui.btn(this, t("Remove logo", "Buang logo")) { brand.clearLogo(); toast(t("Logo removed", "Logo dibuang")) }
        col.addView(rm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d14 / 2 })
        AlertDialog.Builder(this).setTitle(t("Brand", "Jenama")).setView(col)
            .setPositiveButton(t("Save", "Simpan")) { _, _ ->
                brand.on = on.isChecked; brand.title = ti.text.toString(); brand.price = pr.text.toString(); brand.phone = ph.text.toString(); brand.refresh()
            }.setNegativeButton(t("Cancel", "Batal"), null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
