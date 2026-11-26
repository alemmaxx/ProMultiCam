@file:Suppress("UnsafeOptInUsageError")
package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.common.collect.ImmutableList
import com.promulticam.app.Ui.t
import java.io.File
import java.util.Locale

/* ---------- the project: clips one after another + text on top ---------- */
class Clip(val file: File, var inUs: Long, var outUs: Long, val still: Boolean) { val dur get() = outUs - inUs; fun copy() = Clip(file, inUs, outUs, still) }
class Txt(var text: String, var s: Long, var e: Long, var font: String = "bricolage", var col: Int = Color.WHITE, var box: Boolean = true, var pos: String = "b", var size: Int = 1) {
    fun copy() = Txt(text, s, e, font, col, box, pos, size)
}

/* draws the text of one moment on a clear picture the size of the video (the same for preview and saving) */
class TextArt(private val c: Context) {
    fun key(list: List<Txt>, t: Long) = list.filter { t >= it.s && t < it.e }.joinToString("|") { "${it.text}/${it.font}/${it.col}/${it.box}/${it.pos}/${it.size}" }
    fun render(list: List<Txt>, t: Long, W: Int, H: Int): Bitmap {
        val on = list.filter { t >= it.s && t < it.e }
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        if (on.isEmpty()) return b
        val cv = Canvas(b); val m = minOf(W, H).toFloat()
        on.forEach { x ->
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG); tp.typeface = Overlay.face(c, x.font); tp.textSize = m * floatArrayOf(0.06f, 0.085f, 0.115f)[x.size.coerceIn(0, 2)]
            tp.color = x.col
            val txt = if (Overlay.FONTS[x.font]?.caps == true) x.text.uppercase() else x.text
            val w = (W * 0.86f).toInt()
            val sl = StaticLayout.Builder.obtain(txt, 0, txt.length, tp, w).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
            val cy = H * when (x.pos) { "t" -> 0.16f; "m" -> 0.5f; else -> 0.8f }
            val top = cy - sl.height / 2f; val left = (W - w) / 2f
            if (x.box) {
                var lw = 0f; for (i in 0 until sl.lineCount) lw = maxOf(lw, sl.getLineWidth(i))
                val pad = tp.textSize * 0.35f
                val bp = Paint(Paint.ANTI_ALIAS_FLAG); bp.color = if (x.col == Color.BLACK || x.col == 0xFF111111.toInt()) 0xE6FFFFFF.toInt() else 0xB3000000.toInt()
                cv.drawRoundRect(RectF(W / 2f - lw / 2 - pad, top - pad * 0.6f, W / 2f + lw / 2 + pad, top + sl.height + pad * 0.6f), pad, pad, bp)
            } else tp.setShadowLayer(tp.textSize * 0.12f, 0f, tp.textSize * 0.05f, 0xCC000000.toInt())
            cv.save(); cv.translate(left, top); sl.draw(cv); cv.restore()
        }
        return b
    }
}

/* a photo becomes a short video clip (with silent sound), so it sits on the timeline like any video */
object StillClip {
    fun make(src: Bitmap, W0: Int, H0: Int, durUs: Long, out: File) {
        var W = W0; var H = H0; val big = maxOf(W, H); if (big > 1920) { W = W * 1920 / big; H = H * 1920 / big }
        W = W / 2 * 2; H = H / 2 * 2
        val fps = 30; val n = maxOf(1, (durUs * fps / 1_000_000).toInt())
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) }
        val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = vc.createInputSurface(); vc.start()
        val vs = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>(); var vFmt: MediaFormat? = null; var k = 0
        val info = MediaCodec.BufferInfo()
        fun drainV(end: Boolean) {
            val until = System.currentTimeMillis() + 5000
            while (true) {
                val i = vc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end || System.currentTimeMillis() > until) return else continue }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { vFmt = vc.outputFormat; continue }
                if (i < 0) continue
                val bb = vc.getOutputBuffer(i)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val a = ByteArray(info.size); bb.position(info.offset); bb.get(a)
                    val bi = MediaCodec.BufferInfo(); bi.set(0, info.size, k * 1_000_000L / fps, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME); k++; vs.add(a to bi)
                }
                vc.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        val sc = minOf(W.toFloat() / src.width, H.toFloat() / src.height); val dw = src.width * sc; val dh = src.height * sc
        val dst = RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2); val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (f in 0 until n) { val c = surf.lockHardwareCanvas(); c.drawColor(Color.BLACK); c.drawBitmap(src, null, dst, p); surf.unlockCanvasAndPost(c); drainV(false) }
        vc.signalEndOfInputStream(); drainV(true); vc.stop(); vc.release(); surf.release()

        // silent sound of the same length
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply { setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 64000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192) }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); ac.start()
        val asamp = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>(); var aFmt: MediaFormat? = null
        val total = durUs * 48000 / 1_000_000; var fed = 0L; var inEnd = false; var outEnd = false
        val until = System.currentTimeMillis() + 8000
        while (!outEnd && System.currentTimeMillis() < until) {
            if (!inEnd) { val i = ac.dequeueInputBuffer(5000); if (i >= 0) { val bb = ac.getInputBuffer(i)!!; bb.clear()
                if (fed >= total) { ac.queueInputBuffer(i, 0, 0, fed * 1_000_000 / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true }
                else { val ns = minOf(1024L, total - fed).toInt(); bb.put(ByteArray(ns * 2)); ac.queueInputBuffer(i, 0, ns * 2, fed * 1_000_000 / 48000, 0); fed += ns } } }
            val o = ac.dequeueOutputBuffer(info, 5000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) aFmt = ac.outputFormat
            else if (o >= 0) { val bb = ac.getOutputBuffer(o)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) { val a = ByteArray(info.size); bb.position(info.offset); bb.get(a); val bi = MediaCodec.BufferInfo(); bi.set(0, info.size, info.presentationTimeUs, 0); asamp.add(a to bi) }
                ac.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEnd = true }
        }
        ac.stop(); ac.release()
        val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vt = mx.addTrack(vFmt ?: throw Exception("no video")); val at = aFmt?.let { mx.addTrack(it) } ?: -1
        mx.start()
        vs.forEach { (a, bi) -> mx.writeSampleData(vt, java.nio.ByteBuffer.wrap(a), bi) }
        if (at >= 0) asamp.forEach { (a, bi) -> mx.writeSampleData(at, java.nio.ByteBuffer.wrap(a), bi) }
        mx.stop(); mx.release()
    }
}

/* ---------- the timeline: clips (with pictures) and text, scroll it to move the play line ---------- */
class Timeline(c: Context) : View(c) {
    var clips: List<Clip> = emptyList(); var texts: List<Txt> = emptyList()
    var time = 0L; var selClip = -1; var selTxt = -1
    var onSeek: (Long) -> Unit = {}; var onPick: (Int, Int) -> Unit = { _, _ -> }
    var thumbs: (Clip, Long) -> Bitmap? = { _, _ -> null }
    private val dp = resources.displayMetrics.density
    var pps = 56 * dp   // pixels per second
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * dp; color = Ui.SUB }
    val total get() = clips.sumOf { it.dur }
    private val ruleH get() = 18 * dp; private val clipTop get() = ruleH + 4 * dp; private val clipH get() = 54 * dp
    private val txtTop get() = clipTop + clipH + 8 * dp; private val txtH get() = 30 * dp
    private fun x(us: Long) = width / 2f + (us - time) / 1e6f * pps
    private fun us(x: Float) = time + ((x - width / 2f) / pps * 1e6f).toLong()
    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            time = (time + (dx / pps * 1e6f).toLong()).coerceIn(0, maxOf(0, total - 1)); onSeek(time); invalidate(); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val u = us(e.x)
            if (e.y >= txtTop && e.y <= txtTop + txtH) { val i = texts.indexOfFirst { u >= it.s && u < it.e }; onPick(-1, i); return true }
            if (e.y >= clipTop && e.y <= clipTop + clipH) { var o = 0L; for ((i, cl) in clips.withIndex()) { if (u >= o && u < o + cl.dur) { onPick(i, -1); return true }; o += cl.dur } }
            onPick(-1, -1); return true
        }
    })
    override fun onTouchEvent(e: MotionEvent): Boolean { parent?.requestDisallowInterceptTouchEvent(true); return gd.onTouchEvent(e) }
    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF0F0E0C.toInt())
        // ruler
        val step = if (pps < 30 * dp) 5 else if (pps < 60 * dp) 2 else 1
        val firstS = maxOf(0, (us(0f) / 1_000_000).toInt() - 1); val lastS = (us(width.toFloat()) / 1_000_000).toInt() + 1
        p.color = Ui.LINE
        for (s in firstS..lastS) { if (s % step != 0) continue; val xx = x(s * 1_000_000L); cv.drawRect(xx, ruleH - 6 * dp, xx + dp, ruleH, p)
            cv.drawText(String.format(Locale.US, "%d:%02d", s / 60, s % 60), xx + 3 * dp, ruleH - 7 * dp, tp) }
        // clips
        var o = 0L
        clips.forEachIndexed { i, cl ->
            val l = x(o); val r = x(o + cl.dur)
            if (r > 0 && l < width) {
                val rect = RectF(l + dp, clipTop, r - dp, clipTop + clipH)
                p.color = if (cl.still) 0xFF2A2A3A.toInt() else 0xFF24221D.toInt(); cv.drawRoundRect(rect, 8 * dp, 8 * dp, p)
                cv.save(); cv.clipRect(rect)
                val tw = clipH * 0.62f; var xx = l
                while (xx < r) { if (xx + tw > 0 && xx < width) { val at = cl.inUs + ((xx - l) / pps * 1e6f).toLong()
                    thumbs(cl, (at / 1_000_000) * 1_000_000)?.let { cv.drawBitmap(it, null, RectF(xx, clipTop, xx + tw, clipTop + clipH), null) } }
                    xx += tw }
                cv.restore()
                if (i == selClip) { p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = Ui.GOLD; cv.drawRoundRect(rect, 8 * dp, 8 * dp, p); p.style = Paint.Style.FILL }
            }
            o += cl.dur
        }
        // text lane
        texts.forEachIndexed { i, tx ->
            val rect = RectF(x(tx.s) + dp, txtTop, x(tx.e) - dp, txtTop + txtH)
            p.color = 0xFF3A2F17.toInt(); cv.drawRoundRect(rect, 7 * dp, 7 * dp, p)
            if (i == selTxt) { p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = Ui.GOLD; cv.drawRoundRect(rect, 7 * dp, 7 * dp, p); p.style = Paint.Style.FILL }
            cv.save(); cv.clipRect(rect); val q = TextPaint(tp); q.color = Ui.TEXT; q.textSize = 12 * dp; cv.drawText("T  " + tx.text.replace('\n', ' '), rect.left + 6 * dp, rect.centerY() + 4 * dp, q); cv.restore()
        }
        // play line
        p.color = Color.WHITE; cv.drawRect(width / 2f - 1.5f * dp, 0f, width / 2f + 1.5f * dp, height.toFloat(), p)
    }
}

/* ---------- the editor page ---------- */
class EditorActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var clips = ArrayList<Clip>(); private var texts = ArrayList<Txt>()
    private val undo = ArrayList<Pair<List<Clip>, List<Txt>>>()
    private var orig: File? = null
    private var W = 1080; private var H = 1920
    private lateinit var player: ExoPlayer
    private lateinit var tl: Timeline
    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var ovView: ImageView
    private lateinit var timeTx: TextView
    private lateinit var playBtn: Button
    private lateinit var tools: LinearLayout
    private lateinit var tabs: List<Button>
    private var tab = 0
    private val art by lazy { TextArt(this) }
    private var ovKey = "?"
    private var dragging = false
    private var busy = false
    private val work by lazy { File(cacheDir, "edit").apply { mkdirs() } }
    private val thumbCache = HashMap<String, Bitmap?>()
    private val thumbQ = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var stillSec = 3

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) addVideo(u) }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) addPhoto(u) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this); Auth.init(this)
        val d = Ui.px(this, 10)
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(Ui.BG); root.setPadding(d, d * 2, d, d)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.iconBtn(this, R.drawable.ic_back) { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 46)))
        top.addView(Ui.label(this, "Edit", 20f, Ui.TEXT, true).apply { setPadding(d, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(sbtn("↶ " + t("Undo", "Undo")) { doUndo() }, LinearLayout.LayoutParams(-2, Ui.px(this, 46)).apply { rightMargin = d / 2 })
        top.addView(sbtn(t("Save", "Simpan"), true) { save() }, LinearLayout.LayoutParams(-2, Ui.px(this, 46)))
        root.addView(top)

        // preview
        val stage = FrameLayout(this); stage.setBackgroundColor(Color.BLACK)
        frame = AspectRatioFrameLayout(this); frame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        val sv = SurfaceView(this); frame.addView(sv, FrameLayout.LayoutParams(-1, -1))
        ovView = ImageView(this); ovView.scaleType = ImageView.ScaleType.FIT_XY; frame.addView(ovView, FrameLayout.LayoutParams(-1, -1))
        stage.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        stage.setOnClickListener { togglePlay() }; frame.setOnClickListener { togglePlay() }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d })

        val pr = LinearLayout(this); pr.orientation = LinearLayout.HORIZONTAL; pr.gravity = Gravity.CENTER_VERTICAL
        playBtn = sbtn("▶") { togglePlay() }; pr.addView(playBtn, LinearLayout.LayoutParams(Ui.px(this, 60), Ui.px(this, 44)))
        timeTx = Ui.label(this, "0:00 / 0:00", 14f, Ui.TEXT, true); timeTx.setPadding(d, 0, 0, 0); pr.addView(timeTx, LinearLayout.LayoutParams(0, -2, 1f))
        pr.addView(sbtn("−") { tl.pps = maxOf(tl.pps / 1.5f, 8f * resources.displayMetrics.density); tl.invalidate() }, LinearLayout.LayoutParams(Ui.px(this, 48), Ui.px(this, 44)).apply { rightMargin = d / 2 })
        pr.addView(sbtn("+") { tl.pps = minOf(tl.pps * 1.5f, 300f * resources.displayMetrics.density); tl.invalidate() }, LinearLayout.LayoutParams(Ui.px(this, 48), Ui.px(this, 44)))
        root.addView(pr, Ui.lp(this, 6))

        tl = Timeline(this)
        tl.thumbs = { c, at -> thumb(c, at) }
        tl.onSeek = { u -> dragging = true; if (player.isPlaying) player.pause(); seekTo(u); showTime(u); main.removeCallbacks(endDrag); main.postDelayed(endDrag, 250) }
        tl.onPick = { ci, ti -> tl.selClip = ci; tl.selTxt = ti; if (ti >= 0) setTab(2) else if (ci >= 0 && tab == 2) setTab(1); tl.invalidate(); paintTools() }
        root.addView(tl, LinearLayout.LayoutParams(-1, Ui.px(this, 130)).apply { topMargin = d / 2 })

        val tr = LinearLayout(this); tr.orientation = LinearLayout.HORIZONTAL
        tabs = listOf(t("Media", "Media"), t("Cut", "Potong"), t("Text", "Teks")).mapIndexed { i, s -> Ui.pill(this, s, i == 0) { setTab(i) }.also { tr.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 }) } }
        root.addView(tr, Ui.lp(this, 8))
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
        tools = LinearLayout(this); tools.orientation = LinearLayout.HORIZONTAL; hs.addView(tools)
        root.addView(hs, LinearLayout.LayoutParams(-1, Ui.px(this, 58)).apply { topMargin = d / 2 })
        setContentView(root)

        player = ExoPlayer.Builder(this).build()
        player.setVideoSurfaceView(sv)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { playBtn.text = if (p) "‖" else "▶"; if (p) loop() }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) { player.pause(); player.seekTo(0, 0) } }
        })
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) { toast(t("Please wait, saving…", "Sila tunggu, sedang simpan…")); return }
                if (undo.isEmpty()) { finish(); return }
                AlertDialog.Builder(this@EditorActivity).setMessage(t("Leave without saving?", "Keluar tanpa simpan?"))
                    .setPositiveButton(t("Leave", "Keluar")) { _, _ -> finish() }.setNegativeButton(t("Stay", "Kekal"), null).show()
            }
        })

        val fn = intent.getStringExtra("f") ?: ""
        val f = if (fn.isNotEmpty()) File(Store.dir(this), File(fn).name) else null
        if (f != null && f.exists()) { orig = f; val (w, h, du) = probe(f); setSize(w, h); clips.add(Clip(f, 0, du, false)); rebuild(0) }
        else { paintTools(); main.postDelayed({ askAdd() }, 300) }
        setTab(0)
    }
    override fun onDestroy() { player.release(); thumbQ.shutdownNow(); if (!busy) work.listFiles()?.forEach { it.delete() }; super.onDestroy() }
    override fun onPause() { super.onPause(); if (player.isPlaying) player.pause() }

    private fun sbtn(l: String, gold: Boolean = false, f: () -> Unit): Button { val b = Ui.btn(this, l, gold, f); b.textSize = 14f; b.maxLines = 1; b.setPadding(Ui.px(this, 12), Ui.px(this, 4), Ui.px(this, 12), Ui.px(this, 8)); b.minHeight = 0; b.minimumHeight = 0; return b }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private val endDrag = Runnable { dragging = false }

    private fun probe(f: File): Triple<Int, Int, Long> = try {
        val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080; var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0; if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
        val du = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) * 1000; r.release(); Triple(w, h, du)
    } catch (e: Exception) { Triple(1080, 1920, 0L) }
    private fun setSize(w: Int, h: Int) {
        var a = w; var b = h; val big = maxOf(a, b); if (big > 1920) { a = a * 1920 / big; b = b * 1920 / big }
        W = a / 2 * 2; H = b / 2 * 2; frame.setAspectRatio(W.toFloat() / H)
    }
    private fun thumb(c: Clip, at: Long): Bitmap? {
        val k = c.file.name + "@" + at
        if (thumbCache.containsKey(k)) return thumbCache[k]
        thumbCache[k] = null
        thumbQ.execute {
            val b = try { MediaMetadataRetriever().run { setDataSource(c.file.absolutePath); val x = getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 200); release(); x } } catch (e: Exception) { null }
            main.post { thumbCache[k] = b; tl.invalidate() }
        }
        return null
    }

    /* ---- time: whole video ⇄ which clip ---- */
    private fun total() = clips.sumOf { it.dur }
    private fun now(): Long { var o = 0L; val i = player.currentMediaItemIndex; for (k in 0 until minOf(i, clips.size)) o += clips[k].dur; return o + player.currentPosition * 1000 }
    private fun locate(u: Long): Pair<Int, Long> { var o = 0L; clips.forEachIndexed { i, c -> if (u < o + c.dur) return i to (u - o); o += c.dur }; return maxOf(0, clips.size - 1) to (clips.lastOrNull()?.dur ?: 0) }
    private fun seekTo(u: Long) { if (clips.isEmpty()) return; val (i, l) = locate(u); player.seekTo(i, l / 1000); paintOverlay(u) }
    private fun fmt(u: Long) = String.format(Locale.US, "%d:%02d", u / 60_000_000, (u / 1_000_000) % 60)
    private fun showTime(u: Long) { timeTx.text = fmt(u) + " / " + fmt(total()) }
    private fun loop() {
        if (!player.isPlaying) return
        if (!dragging) { val u = now(); tl.time = u; tl.invalidate(); showTime(u); paintOverlay(u) }
        main.postDelayed({ loop() }, 40)
    }
    private fun togglePlay() { if (clips.isEmpty()) return; if (player.isPlaying) player.pause() else { if (now() >= total() - 50_000) seekTo(0); player.play() } }
    private fun paintOverlay(u: Long) {
        val k = art.key(texts, u); if (k == ovKey) return; ovKey = k
        val s = 540f / maxOf(W, H)   // preview: lighter picture, same layout
        ovView.setImageBitmap(if (k.isEmpty()) null else art.render(texts, u, (W * s).toInt().coerceAtLeast(2), (H * s).toInt().coerceAtLeast(2)))
    }

    /* rebuild the player's list after a change, staying at the same time */
    private fun rebuild(at: Long) {
        val items = clips.map { c -> MediaItem.Builder().setUri(Uri.fromFile(c.file)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inUs / 1000).setEndPositionMs(c.outUs / 1000).build()).build() }
        val u = at.coerceIn(0, maxOf(0, total() - 1))
        if (items.isEmpty()) { player.clearMediaItems() } else { val (i, l) = locate(u); player.setMediaItems(items, i, l / 1000); player.prepare() }
        tl.clips = clips; tl.texts = texts; tl.time = u; if (tl.selClip >= clips.size) tl.selClip = -1; if (tl.selTxt >= texts.size) tl.selTxt = -1
        tl.invalidate(); ovKey = "?"; paintOverlay(u); showTime(u); paintTools()
    }
    private fun snap() { undo.add(clips.map { it.copy() } to texts.map { it.copy() }); if (undo.size > 40) undo.removeAt(0) }
    private fun doUndo() { val s = undo.removeLastOrNull() ?: run { toast(t("Nothing to undo", "Tiada untuk undo")); return }; clips = ArrayList(s.first); texts = ArrayList(s.second); rebuild(tl.time) }

    /* ---- tools ---- */
    private fun setTab(i: Int) { tab = i; tabs.forEachIndexed { j, b -> Ui.setPill(this, b, j == i) }; paintTools() }
    private fun tool(l: String, gold: Boolean = false, f: () -> Unit) { tools.addView(sbtn(l, gold, f), LinearLayout.LayoutParams(-2, Ui.px(this, 50)).apply { rightMargin = Ui.px(this@EditorActivity, 6) }) }
    private fun paintTools() {
        if (!::tools.isInitialized) return
        tools.removeAllViews()
        when (tab) {
            0 -> { tool("+ " + t("Video", "Video"), true) { pickVideo.launch("video/*") }; tool("+ " + t("Photo", "Gambar"), true) { askPhoto() }
                tool(t("Photo time: ", "Masa gambar: ") + "${stillSec}s") { stillSec = when (stillSec) { 2 -> 3; 3 -> 5; 5 -> 8; else -> 2 }; paintTools() } }
            1 -> { tool("✂ " + t("Split", "Belah")) { split() }
                tool("⇤ " + t("Trim start here", "Potong awal")) { trim(true) }; tool(t("Trim end here", "Potong akhir") + " ⇥") { trim(false) }
                tool("🗑 " + t("Delete clip", "Padam klip")) { delClip() }; tool("⧉ " + t("Duplicate", "Salin")) { dupClip() } }
            2 -> { tool("+ " + t("Text", "Teks"), true) { textDialog(null) }
                if (tl.selTxt >= 0) { tool("✎ " + t("Edit", "Ubah")) { textDialog(texts[tl.selTxt]) }
                    tool("⇤ " + t("Start here", "Mula sini")) { val x = texts[tl.selTxt]; if (tl.time < x.e - 200_000) { snap(); x.s = tl.time; rebuild(tl.time) } }
                    tool(t("End here", "Tamat sini") + " ⇥") { val x = texts[tl.selTxt]; if (tl.time > x.s + 200_000) { snap(); x.e = tl.time; rebuild(tl.time) } }
                    tool("🗑 " + t("Delete", "Padam")) { snap(); texts.removeAt(tl.selTxt); tl.selTxt = -1; rebuild(tl.time) } }
                else tools.addView(Ui.label(this, t("Tap a text on the timeline to change it", "Tekan teks di garis masa untuk ubah"), 13f, Ui.SUB).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(-2, -1)) }
        }
    }
    private fun selOrHere(): Int = if (tl.selClip in clips.indices) tl.selClip else locate(tl.time).first
    private fun split() {
        if (clips.isEmpty()) return
        val (i, l) = locate(tl.time); val c = clips[i]
        if (l < 150_000 || c.dur - l < 150_000) { toast(t("Move the line inside a clip", "Gerakkan garis ke dalam klip")); return }
        snap(); val a = Clip(c.file, c.inUs, c.inUs + l, c.still); val b = Clip(c.file, c.inUs + l, c.outUs, c.still)
        clips[i] = a; clips.add(i + 1, b); tl.selClip = i + 1; rebuild(tl.time)
    }
    private fun trim(start: Boolean) {
        if (clips.isEmpty()) return
        val (i, l) = locate(tl.time); val c = clips[i]
        if (l < 150_000 || c.dur - l < 150_000) { toast(t("Move the line inside a clip", "Gerakkan garis ke dalam klip")); return }
        snap(); var o = 0L; for (k in 0 until i) o += clips[k].dur
        if (start) { c.inUs += l; shiftTexts(o, -l); rebuild(o) } else { val cut = c.dur - l; c.outUs = c.inUs + l; shiftTexts(o + l, -cut); rebuild(o + l - 1) }
        tl.selClip = i
    }
    private fun shiftTexts(from: Long, by: Long) {
        texts.forEach { x -> if (x.s >= from) x.s = maxOf(from, x.s + by); if (x.e > from) x.e = maxOf(x.s + 300_000, x.e + by) }
        texts.removeAll { it.s >= total() }
    }
    private fun delClip() {
        if (clips.size <= 1) { toast(t("Keep at least one clip", "Perlu sekurang-kurangnya satu klip")); return }
        val i = selOrHere(); snap(); var o = 0L; for (k in 0 until i) o += clips[k].dur
        val d = clips[i].dur; clips.removeAt(i)
        texts.removeAll { it.s >= o && it.e <= o + d }; shiftTexts(o + d, -d)
        tl.selClip = -1; rebuild(o)
    }
    private fun dupClip() { if (clips.isEmpty()) return; val i = selOrHere(); snap(); clips.add(i + 1, clips[i].copy()); tl.selClip = i + 1; rebuild(tl.time) }

    /* insert a new clip at the play line (splitting a clip if the line is inside it) */
    private fun insertAt(c: Clip) {
        snap()
        if (clips.isEmpty()) { clips.add(c); rebuild(0); return }
        val u = tl.time; var (i, l) = locate(u)
        val cur = clips[i]
        val at: Long
        if (l < 150_000) { at = u - l }
        else if (cur.dur - l < 150_000 || u >= total() - 150_000) { i += 1; var o = 0L; for (k in 0 until i) o += clips[k].dur; at = o }
        else { clips[i] = Clip(cur.file, cur.inUs, cur.inUs + l, cur.still); clips.add(i + 1, Clip(cur.file, cur.inUs + l, cur.outUs, cur.still)); i += 1; at = u }
        clips.add(i, c); shiftTexts(at, c.dur)
        tl.selClip = i; rebuild(at)
    }
    private fun askAdd() {
        AlertDialog.Builder(this).setTitle(t("Add video or photo", "Tambah video atau gambar"))
            .setItems(arrayOf(t("Video", "Video"), t("Photo", "Gambar"))) { _, w -> if (w == 0) pickVideo.launch("video/*") else pickPhoto.launch("image/*") }
            .setNegativeButton(t("Cancel", "Batal")) { _, _ -> if (clips.isEmpty()) finish() }.show()
    }
    private fun askPhoto() { pickPhoto.launch("image/*") }
    private fun wait(s: String): AlertDialog = AlertDialog.Builder(this).setMessage(s).setCancelable(false).show()
    private fun addVideo(u: Uri) {
        val w = wait(t("Loading video…", "Memuatkan video…"))
        Thread {
            val f = File(work, "v_" + System.currentTimeMillis() + ".mp4")
            val ok = try { contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }; true } catch (e: Exception) { false }
            val pr = if (ok) probe(f) else null
            main.post { w.dismiss()
                if (pr == null || pr.third <= 0) { toast(t("This video cannot be opened", "Video ini tidak boleh dibuka")); if (clips.isEmpty()) finish(); return@post }
                if (clips.isEmpty()) setSize(pr.first, pr.second)
                insertAt(Clip(f, 0, pr.third, false)) }
        }.start()
    }
    private fun addPhoto(u: Uri) {
        val w = wait(t("Adding photo…", "Menambah gambar…"))
        val sec = stillSec
        Thread {
            val f = File(work, "p_" + System.currentTimeMillis() + ".mp4")
            val res = try {
                val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
                val bm = contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
                val rot = try { contentResolver.openInputStream(u)!!.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } } catch (e: Exception) { 0 }
                val b2 = if (rot != 0) Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true) else bm
                val first = clips.isEmpty()
                val (pw, ph) = if (first) (if (b2.height >= b2.width) 1080 to 1920 else 1920 to 1080) else W to H
                StillClip.make(b2, pw, ph, sec * 1_000_000L, f); Triple(first, pw, ph)
            } catch (e: Exception) { null }
            main.post { w.dismiss()
                if (res == null || !f.exists()) { toast(t("This photo cannot be added", "Gambar ini tidak boleh ditambah")); if (clips.isEmpty()) finish(); return@post }
                if (res.first) setSize(res.second, res.third)
                val du = probe(f).third.takeIf { it > 0 } ?: (sec * 1_000_000L)
                insertAt(Clip(f, 0, du, true)) }
        }.start()
    }

    /* ---- text ---- */
    private fun textDialog(edit: Txt?) {
        val x = edit?.copy() ?: Txt("", tl.time, minOf(total(), tl.time + 3_000_000))
        if (edit == null && x.e - x.s < 300_000) { toast(t("Move the line earlier", "Gerakkan garis lebih awal")); return }
        val s = Ui.sheet(this, if (edit == null) t("Add text", "Tambah teks") else t("Change text", "Ubah teks"))
        val inp = Ui.input(this, t("Type here…", "Taip di sini…"), x.text); inp.minLines = 2; inp.gravity = Gravity.TOP; s.body.addView(inp)
        fun pills(title: String, opts: List<Pair<String, String>>, cur: () -> String, set: (String) -> Unit) {
            s.body.addView(Ui.label(this, title, 13f, Ui.SUB, true), Ui.lp(this, 12))
            val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false; val r = LinearLayout(this); hs.addView(r); val bs = ArrayList<Button>()
            opts.forEach { (id, l) -> val b = Ui.pill(this, l, cur() == id) { set(id); bs.forEachIndexed { j, q -> Ui.setPill(this, q, opts[j].first == cur()) } }
                if (id.startsWith("#")) { b.text = "●"; b.setTextColor(Color.parseColor(id)) }
                bs.add(b); r.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }) }
            s.body.addView(hs, Ui.lp(this, 4))
        }
        pills(t("Font", "Fon"), Overlay.FONTS.map { (k, f) -> k to (if (Ui.ms) f.ms else f.en).substringBefore(" —") }, { x.font }) { x.font = it }
        val cols = listOf("#FFFFFF", "#111111", "#D9B44A", "#D6453A", "#2F9E5B", "#4A90E2", "#F48FB1")
        pills(t("Colour", "Warna"), cols.map { it to it }, { String.format("#%06X", x.col and 0xFFFFFF) }) { x.col = Color.parseColor(it) }
        pills(t("Style", "Gaya"), listOf("1" to t("With box", "Berkotak"), "0" to t("Shadow", "Bayang")), { if (x.box) "1" else "0" }) { x.box = it == "1" }
        pills(t("Place", "Kedudukan"), listOf("t" to t("Top", "Atas"), "m" to t("Middle", "Tengah"), "b" to t("Bottom", "Bawah")), { x.pos }) { x.pos = it }
        pills(t("Size", "Saiz"), listOf("0" to t("Small", "Kecil"), "1" to t("Medium", "Sederhana"), "2" to t("Big", "Besar")), { x.size.toString() }) { x.size = it.toInt() }
        if (edit == null) pills(t("How long", "Berapa lama"), listOf("2" to "2s", "3" to "3s", "5" to "5s", "all" to t("To the end", "Hingga akhir")), { "3" }) { x.e = if (it == "all") total() else minOf(total(), x.s + it.toLong() * 1_000_000) }
        s.body.addView(Ui.btn(this, t("Done", "Siap"), true) {
            val v = inp.text.toString().trim(); if (v.isEmpty()) { toast(t("Type some text", "Taip teks dahulu")); return@btn }
            x.text = v; snap()
            if (edit == null) { texts.add(x); tl.selTxt = texts.size - 1 } else { val i = texts.indexOf(edit); if (i >= 0) texts[i] = x }
            s.dialog.dismiss(); rebuild(tl.time)
        }, Ui.lp(this, 16))
        s.dialog.show()
    }

    /* ---- save: a new video made from the timeline ---- */
    private fun save() {
        if (clips.isEmpty() || busy) return
        player.pause()
        val o = orig
        if (o == null) { export(null); return }
        AlertDialog.Builder(this).setTitle(t("Save", "Simpan"))
            .setItems(arrayOf(t("Replace the original video", "Ganti video asal"), t("Save as a new video", "Simpan sebagai video baru"))) { _, w -> export(if (w == 0) o else null) }
            .setNegativeButton(t("Cancel", "Batal"), null).show()
    }
    private fun export(replace: File?) {
        busy = true
        val tmp = File(Store.dir(this), "tmp_edit_" + System.currentTimeMillis() + ".mp4")
        val dlg = AlertDialog.Builder(this).setMessage(t("Making the video… 0%", "Membuat video… 0%")).setCancelable(false).show()
        val txs = texts.map { it.copy() }
        var off = 0L
        val items = clips.map { c ->
            val mi = MediaItem.Builder().setUri(Uri.fromFile(c.file)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inUs / 1000).setEndPositionMs(c.outUs / 1000).build()).build()
            val fx = ArrayList<androidx.media3.common.Effect>(); fx.add(Presentation.createForWidthAndHeight(W, H, Presentation.LAYOUT_SCALE_TO_FIT))
            val base = off; val du = c.dur
            if (txs.any { it.e > base && it.s < base + du }) fx.add(OverlayEffect(ImmutableList.of(TextLayer(art, txs, base, W, H))))
            off += du
            EditedMediaItem.Builder(mi).setEffects(Effects(listOf(), fx)).build()
        }
        val comp = Composition.Builder(listOf(EditedMediaItemSequence(items))).experimentalSetForceAudioTrack(true).build()
        val tr = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    busy = false; dlg.dismiss()
                    val out: File = if (replace != null) { replace.delete(); tmp.renameTo(replace); replace } else { val nf = Store.newFile(this@EditorActivity, (orig?.name?.substringBefore("_") ?: "Video") + "_edit"); tmp.renameTo(nf); nf }
                    undo.clear()
                    if (replace != null) { orig = replace; thumbCache.clear(); clips = arrayListOf(Clip(replace, 0, probe(replace).third, false)); texts.clear(); rebuild(0) }
                    AlertDialog.Builder(this@EditorActivity).setMessage(t("✅ Saved in the app's Videos.", "✅ Disimpan dalam Video app."))
                        .setPositiveButton(t("Save to Phone", "Simpan ke Telefon")) { _, _ -> Thread { val ok = Store.saveToPhone(this@EditorActivity, out); main.post { toast(if (ok) t("Saved to gallery: Movies/ProMultiCam", "Disimpan ke galeri: Movies/ProMultiCam") else t("Could not save", "Gagal simpan")) } }.start() }
                        .setNegativeButton(t("Close", "Tutup"), null).show()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    busy = false; dlg.dismiss(); tmp.delete()
                    AlertDialog.Builder(this@EditorActivity).setMessage(t("Could not make the video: ", "Gagal membuat video: ") + (exportException.message ?: "")).setPositiveButton("OK", null).show()
                }
            }).build()
        try { tr.start(comp, tmp.absolutePath) } catch (e: Exception) { busy = false; dlg.dismiss(); toast(t("Could not make the video", "Gagal membuat video")); return }
        val ph = ProgressHolder()
        fun prog() { if (!busy) return; if (tr.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) dlg.setMessage(t("Making the video… ", "Membuat video… ") + ph.progress + "%"); main.postDelayed({ prog() }, 400) }
        prog()
    }

    /* text drawn while saving; time is counted from this clip's first picture */
    class TextLayer(private val art: TextArt, private val list: List<Txt>, private val base: Long, private val W: Int, private val H: Int) : BitmapOverlay() {
        private var first = -1L; private var key = "?"; private var bmp: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (first < 0) first = presentationTimeUs
            val u = base + (presentationTimeUs - first)
            val k = art.key(list, u)
            if (k != key) { key = k; bmp = if (k.isEmpty()) Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) else art.render(list, u, W, H) }
            return bmp
        }
    }
}
