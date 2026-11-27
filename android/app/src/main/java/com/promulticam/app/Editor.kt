@file:Suppress("UnsafeOptInUsageError")
package com.promulticam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
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
import kotlin.math.abs

/* What is selected on the timeline */
sealed class Sel { object None : Sel(); data class C(val i: Int) : Sel(); data class L(val i: Int) : Sel(); data class S(val i: Int) : Sel() }

/* ---------------- timeline, CapCut style: the white line stays in the middle, the tracks move ---------------- */
class Timeline(c: Context, private val ed: EditorActivity) : View(c) {
    var time = 0L; var sel: Sel = Sel.None
    private val dp = resources.displayMetrics.density
    var pps = 60 * dp
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * dp; color = Ui.SUB }
    private val ruleH get() = 16 * dp; private val mainTop get() = ruleH + 6 * dp; private val mainH get() = 50 * dp
    private val laneTop get() = mainTop + mainH + 8 * dp; private val laneH get() = 22 * dp; private val laneGap get() = 4 * dp
    private var layLane = IntArray(0); private var sndLane = IntArray(0); private var nLay = 0
    private fun x(us: Long) = width / 2f + (us - time) / 1e6f * pps
    private fun us(x: Float) = time + ((x - width / 2f) / pps * 1e6f).toLong()
    private fun laneY(l: Int) = laneTop + l * (laneH + laneGap)
    private fun lanes() {
        fun pack(r: List<Pair<Long, Long>>): IntArray { val ends = ArrayList<Long>(); val out = IntArray(r.size)
            r.indices.sortedBy { r[it].first }.forEach { i -> var k = ends.indexOfFirst { it <= r[i].first }; if (k < 0) { ends.add(0); k = ends.size - 1 }; ends[k] = r[i].second; out[i] = k }; return out }
        layLane = pack(ed.layers.map { it.s to it.e }); nLay = (layLane.maxOrNull() ?: -1) + 1
        sndLane = pack(ed.sounds.map { it.s to it.e }).map { it + nLay }.toIntArray()
    }
    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { pps = (pps * d.scaleFactor).coerceIn(6 * dp, 400 * dp); invalidate(); return true } })
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var reorder = false; private var dragIdx = -1; private var dragX = 0f
    private val lp = Runnable { if (dragIdx >= 0) { reorder = true; ed.snap(); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate() } }
    private val sq get() = 56 * dp; private val gap get() = 6 * dp
    private fun rowLeft() = width / 2f - ed.clips.size * (sq + gap) / 2f
    private fun target() = ((dragX - rowLeft()) / (sq + gap)).toInt().coerceIn(0, maxOf(0, ed.clips.size - 1))
    private var mode = 0; private var downX = 0f; private var lastX = 0f; private var moved = false; private var multi = false
    // modes: 0 scrub, 1 clip left, 2 clip right, 3 item move, 4 item left, 5 item right
    private fun clipStart(i: Int): Long { var o = 0L; for (k in 0 until i) o += ed.clips[k].dur; return o }
    private fun itemRange(): Triple<Long, Long, Int>? = when (val s = sel) {
        is Sel.L -> ed.layers.getOrNull(s.i)?.let { Triple(it.s, it.e, layLane.getOrElse(s.i) { 0 }) }
        is Sel.S -> ed.sounds.getOrNull(s.i)?.let { Triple(it.s, it.e, sndLane.getOrElse(s.i) { 0 }) }
        else -> null }
    private fun hitMode(ex: Float, ey: Float): Int {
        val h = 18 * dp
        when (val s = sel) {
            is Sel.C -> { if (s.i < ed.clips.size && ey > mainTop - h / 2 && ey < mainTop + mainH + h / 2) { val l = x(clipStart(s.i)); val r = x(clipStart(s.i) + ed.clips[s.i].dur)
                if (abs(ex - l) < h) return 1; if (abs(ex - r) < h) return 2 } }
            is Sel.L, is Sel.S -> { val (a, b, ln) = itemRange() ?: return 0; val y = laneY(ln)
                if (ey > y - 8 * dp && ey < y + laneH + 8 * dp) { val l = x(a); val r = x(b)
                    if (abs(ex - l) < h) return 4; if (abs(ex - r) < h) return 5; if (ex > l && ex < r) return 3 } }
            else -> {}
        }
        return 0
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDet.onTouchEvent(e)
        if (e.pointerCount > 1) { multi = true; removeCallbacks(lp); return true }
        if (reorder) {
            when (e.actionMasked) { MotionEvent.ACTION_MOVE -> { dragX = e.x; invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { reorder = false; val to = target(); val from = dragIdx; dragIdx = -1; ed.moveClip(from, to); invalidate() } }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { multi = false; downX = e.x; lastX = e.x; moved = false; mode = hitMode(e.x, e.y); if (mode != 0) ed.snap()
                dragIdx = -1
                if (mode == 0 && e.y >= mainTop && e.y <= mainTop + mainH && ed.clips.size > 1) { val u = us(e.x); var o = 0L
                    for ((i, c) in ed.clips.withIndex()) { if (u >= o && u < o + c.dur) { dragIdx = i; dragX = e.x; postDelayed(lp, 380); break }; o += c.dur } } }
            MotionEvent.ACTION_MOVE -> {
                if (multi) return true
                val dx = e.x - lastX; lastX = e.x
                if (!moved && abs(e.x - downX) < slop) return true
                moved = true; removeCallbacks(lp)
                val dUs = (dx / pps * 1e6f).toLong()
                when (mode) {
                    0 -> { time = (time - dUs).coerceIn(0, maxOf(0, ed.total() - 1)); ed.onScrub(time) }
                    1, 2 -> { val c = ed.clips[(sel as Sel.C).i]; val d = (dUs * c.speed).toLong()
                        if (mode == 1) {
                            // the front edge follows the finger: the clip gets shorter/longer at its start and the view moves with it
                            val old = c.inUs; c.inUs = (c.inUs + d).coerceIn(0, c.outUs - 200_000)
                            time -= ((c.inUs - old) / c.speed).toLong()
                        } else c.outUs = (c.outUs + d).coerceIn(c.inUs + 200_000, c.fileDur)
                        ed.trimPeek(c, if (mode == 1) c.inUs else c.outUs - 1) }
                    else -> { val tot = ed.total(); val sl = sel
                        var a: Long; var b: Long
                        if (sl is Sel.L) { a = ed.layers[sl.i].s; b = ed.layers[sl.i].e } else if (sl is Sel.S) { a = ed.sounds[sl.i].s; b = ed.sounds[sl.i].e } else return true
                        when (mode) {
                            3 -> { val dd = dUs.coerceIn(-a, maxOf(0, tot - b)); a += dd; b += dd }
                            4 -> a = (a + dUs).coerceIn(0, b - 300_000)
                            5 -> b = (b + dUs).coerceIn(a + 300_000, maxOf(a + 300_000, tot))
                        }
                        // a video on top can't be longer than its file; trimming its front moves where it starts inside the file
                        (sl as? Sel.L)?.let { ed.layers[it.i] }?.takeIf { it.kind == "pip" && it.vdur > 0 }?.let { l ->
                            if (mode == 4) { a = maxOf(a, l.s - l.vin); l.vin = (l.vin + (a - l.s)).coerceIn(0, l.vdur - 300_000) }
                            b = minOf(b, a + (l.vdur - l.vin)) }
                        if (sl is Sel.L) { ed.layers[sl.i].s = a; ed.layers[sl.i].e = b } else if (sl is Sel.S) { ed.sounds[sl.i].s = a; ed.sounds[sl.i].e = b }
                    }
                }
                if (mode != 0) ed.onLive()
                invalidate()
            }
            MotionEvent.ACTION_UP -> { removeCallbacks(lp); if (!moved && !multi) tap(e.x, e.y) else if (mode != 0) ed.onEdited(); mode = 0 }
        }
        return true
    }
    private fun tap(ex: Float, ey: Float) {
        val u = us(ex)
        if (ey >= mainTop - 4 * dp && ey <= mainTop + mainH + 4 * dp) {
            val tot = ed.total()
            if (ex > x(tot) + 6 * dp && ex < x(tot) + 46 * dp) { ed.onAddEnd(); return }
            var o = 0L
            for (i in ed.clips.indices) { if (i > 0 && abs(ex - x(o)) < 13 * dp && ey > mainTop + mainH / 2 - 14 * dp && ey < mainTop + mainH / 2 + 14 * dp) { ed.onTrans(i); return }; o += ed.clips[i].dur }
            o = 0L; for ((i, c) in ed.clips.withIndex()) { if (u >= o && u < o + c.dur) { ed.onSelect(Sel.C(i)); return }; o += c.dur }
        } else if (ey >= laneTop) {
            val ln = ((ey - laneTop) / (laneH + laneGap)).toInt()
            ed.layers.forEachIndexed { i, l -> if (layLane.getOrElse(i) { -1 } == ln && u >= l.s && u < l.e) { ed.onSelect(Sel.L(i)); return } }
            ed.sounds.forEachIndexed { i, l -> if (sndLane.getOrElse(i) { -1 } == ln && u >= l.s && u < l.e) { ed.onSelect(Sel.S(i)); return } }
        }
        ed.onSelect(Sel.None)
    }
    private fun handles(cv: Canvas, r: RectF) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * dp; p.color = Color.WHITE; cv.drawRoundRect(r, 6 * dp, 6 * dp, p); p.style = Paint.Style.FILL
        val hw = 9 * dp
        for (hx in floatArrayOf(r.left - hw + 2 * dp, r.right - 2 * dp)) { p.color = Color.WHITE; cv.drawRoundRect(RectF(hx, r.top, hx + hw, r.bottom), 3 * dp, 3 * dp, p)
            p.color = 0xFF555555.toInt(); cv.drawRect(hx + hw / 2 - dp, r.centerY() - 6 * dp, hx + hw / 2 + dp, r.centerY() + 6 * dp, p) }
    }
    override fun onDraw(cv: Canvas) {
        lanes()
        cv.drawColor(0xFF0F0E0C.toInt())
        if (reorder) {   // clips as squares; drag one to its new place
            val n = ed.clips.size; val tg = target(); val l0 = rowLeft(); val y = mainTop
            cv.drawText(Ui.t("Drag to a new place, then let go", "Seret ke tempat baru, kemudian lepaskan"), 10 * dp, ruleH, tp)
            fun sqAt(c: Clip, x: Float, lift: Boolean) { val r = RectF(x, y - (if (lift) 8 * dp else 0f), x + sq, y + sq - (if (lift) 8 * dp else 0f))
                p.color = 0xFF24221D.toInt(); cv.drawRoundRect(r, 8 * dp, 8 * dp, p)
                ed.thumb(c, (c.inUs / 1_000_000) * 1_000_000)?.let { b -> cv.save(); val path = android.graphics.Path(); path.addRoundRect(r, 8 * dp, 8 * dp, android.graphics.Path.Direction.CW); cv.clipPath(path); cv.drawBitmap(b, null, r, null); cv.restore() }
                if (lift) { p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = Ui.GOLD; cv.drawRoundRect(r, 8 * dp, 8 * dp, p); p.style = Paint.Style.FILL } }
            var j = 0
            ed.clips.forEachIndexed { i, c -> if (i != dragIdx) { val slot = if (j < tg) j else j + 1; sqAt(c, l0 + slot * (sq + gap), false); j++ } }
            p.color = 0x55D9B44A; cv.drawRoundRect(RectF(l0 + tg * (sq + gap), y, l0 + tg * (sq + gap) + sq, y + sq), 8 * dp, 8 * dp, p)
            ed.clips.getOrNull(dragIdx)?.let { sqAt(it, dragX - sq / 2, true) }
            if (n == 0) return
            return
        }
        // ruler
        val sec = pps / dp; val step = when { sec < 10 -> 10; sec < 22 -> 5; sec < 45 -> 2; else -> 1 }
        val a0 = maxOf(0, (us(0f) / 1_000_000).toInt() - 1); val a1 = (us(width.toFloat()) / 1_000_000).toInt() + 1
        p.color = 0xFF3A3830.toInt()
        for (s in a0..a1) { val xx = x(s * 1_000_000L); if (s % step == 0) { cv.drawRect(xx, ruleH - 5 * dp, xx + dp, ruleH, p); cv.drawText(String.format(Locale.US, "%d:%02d", s / 60, s % 60), xx + 3 * dp, ruleH - 6 * dp, tp) }
            else if (step <= 2) cv.drawCircle(xx, ruleH - 3 * dp, dp, p) }
        // main track
        var o = 0L; var selR: RectF? = null
        ed.clips.forEachIndexed { i, cl ->
            val l = x(o); val r = x(o + cl.dur)
            if (r > -50 && l < width + 50) {
                val rect = RectF(l + dp, mainTop, r - dp, mainTop + mainH)
                p.color = if (cl.still) 0xFF26283A.toInt() else 0xFF24221D.toInt(); cv.drawRoundRect(rect, 6 * dp, 6 * dp, p)
                cv.save(); cv.clipRect(rect)
                val tw = mainH * 0.6f; var xx = l
                while (xx < r) { if (xx + tw > 0 && xx < width) { val at = cl.inUs + ((xx - l) / pps * 1e6f * cl.speed).toLong()
                    ed.thumb(cl, (at / 1_000_000) * 1_000_000)?.let { cv.drawBitmap(it, null, RectF(xx, mainTop, xx + tw, mainTop + mainH), null) } }
                    xx += tw }
                if (cl.speed != 1f || cl.vol != 1f || cl.look != "std" || cl.fx != "none") { val q = TextPaint(tp); q.color = Color.WHITE; q.textSize = 10 * dp; q.setShadowLayer(3f, 0f, 1f, Color.BLACK)
                    cv.drawText(listOfNotNull(if (cl.speed != 1f) "${cl.speed}×" else null, if (cl.vol == 0f) "🔇" else null, if (cl.look != "std") "🎨" else null, if (cl.fx != "none") "✨" else null).joinToString(" "), rect.left + 4 * dp, rect.bottom - 4 * dp, q) }
                cv.restore()
                if (cl.fi > 0 || cl.fo > 0) { val a = minOf(rect.width() / 2, cl.fi * pps); val z = minOf(rect.width() / 2, cl.fo * pps); val path = android.graphics.Path()
                    path.moveTo(rect.left, rect.bottom); path.lineTo(rect.left + a, rect.top + 4 * dp); path.lineTo(rect.right - z, rect.top + 4 * dp); path.lineTo(rect.right, rect.bottom)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.color = 0xCCFFFFFF.toInt(); cv.drawPath(path, p); p.style = Paint.Style.FILL }
                if ((sel as? Sel.C)?.i == i) selR = rect
            }
            o += cl.dur
        }
        // switch effects between clips
        o = 0L
        ed.clips.forEachIndexed { i, cl -> if (i > 0) { val xx = x(o); val r = RectF(xx - 10 * dp, mainTop + mainH / 2 - 10 * dp, xx + 10 * dp, mainTop + mainH / 2 + 10 * dp)
            p.color = if (cl.trans != "none") Ui.GOLD else 0xFFEEEEEE.toInt(); cv.drawRoundRect(r, 4 * dp, 4 * dp, p)
            p.color = 0xFF111111.toInt(); p.strokeWidth = 1.6f * dp; cv.drawLine(r.left + 6 * dp, r.centerY(), r.right - 6 * dp, r.centerY(), p); if (cl.trans == "none") cv.drawLine(r.centerX(), r.top + 6 * dp, r.centerX(), r.bottom - 6 * dp, p) }
            o += cl.dur }
        // add at the end
        val ex = x(o) + 26 * dp; p.color = Color.WHITE; cv.drawRoundRect(RectF(ex - 16 * dp, mainTop + mainH / 2 - 16 * dp, ex + 16 * dp, mainTop + mainH / 2 + 16 * dp), 6 * dp, 6 * dp, p)
        p.color = 0xFF111111.toInt(); p.strokeWidth = 2.4f * dp; cv.drawLine(ex - 7 * dp, mainTop + mainH / 2, ex + 7 * dp, mainTop + mainH / 2, p); cv.drawLine(ex, mainTop + mainH / 2 - 7 * dp, ex, mainTop + mainH / 2 + 7 * dp, p)
        selR?.let { handles(cv, it) }
        // other tracks
        fun item(a: Long, b: Long, lane: Int, col: Int, label: String, chosen: Boolean) {
            val y = laneY(lane); val r = RectF(x(a) + dp, y, x(b) - dp, y + laneH); if (r.right < 0 || r.left > width) return
            p.color = col; cv.drawRoundRect(r, 5 * dp, 5 * dp, p)
            cv.save(); cv.clipRect(r); val q = TextPaint(tp); q.color = Color.WHITE; q.textSize = 11 * dp; cv.drawText(label, r.left + 6 * dp, r.centerY() + 4 * dp, q); cv.restore()
            if (chosen) handles(cv, r)
        }
        ed.layers.forEachIndexed { i, l -> item(l.s, l.e, layLane.getOrElse(i) { 0 }, when (l.kind) { "text" -> 0xFF6B4E12.toInt(); "sticker" -> 0xFF6A2A5A.toInt(); "photo" -> 0xFF1F4A66.toInt(); "pip" -> 0xFF5A1F3A.toInt(); else -> 0xFF3F5A22.toInt() },
            when (l.kind) { "text" -> "T  " + l.text.replace('\n', ' '); "sticker" -> l.text; "photo" -> "🖼  " + t("Photo", "Gambar"); "pip" -> "🎞  " + t("Video", "Video"); else -> "🏷  " + t("Brand", "Jenama") }, (sel as? Sel.L)?.i == i) }
        ed.sounds.forEachIndexed { i, s -> item(s.s, s.e, sndLane.getOrElse(i) { 0 }, when (s.kind) { "music" -> 0xFF1E5A3A.toInt(); "sfx" -> 0xFF7A4A12.toInt(); "rec" -> 0xFF6A2A2A.toInt(); else -> 0xFF23407A.toInt() },
            when (s.kind) { "music" -> "♪  " + File(s.file).name.substringAfter('_'); "sfx" -> "🔊 " + s.sfx; "rec" -> "🎙  " + t("Voice", "Suara"); else -> "🗣  " + s.text }, (sel as? Sel.S)?.i == i) }
        // play line
        p.color = Color.WHITE; cv.drawRect(width / 2f - 1.2f * dp, 0f, width / 2f + 1.2f * dp, height.toFloat(), p)
        cv.drawCircle(width / 2f, 3 * dp, 4 * dp, p)
    }
}

/* ---------------- the picture area: what sits on top can be moved with a finger and sized with two ---------------- */
class Stage(c: Context, private val ed: EditorActivity) : View(c) {
    var bmp: Bitmap? = null; var rects: Map<Int, RectF> = emptyMap(); var sel = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density; color = Color.WHITE; pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f) }
    private var drag = -1; private var lx = 0f; private var ly = 0f; private var moved = false; private var dx0 = 0f; private var dy0 = 0f
    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { if (sel >= 0) { ed.scaleLayer(sel, d.scaleFactor); moved = true }; return true } })
    override fun onDraw(cv: Canvas) {
        bmp?.let { cv.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), p) }
        if (sel >= 0) rects[sel]?.let { r -> val k = width.toFloat() / ed.W; cv.drawRect(r.left * k - 6, r.top * k - 6, r.right * k + 6, r.bottom * k + 6, dash) }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        val k = width.toFloat() / ed.W
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; dx0 = e.x; dy0 = e.y; moved = false
                drag = rects.entries.lastOrNull { (i, r) -> ed.layers.getOrNull(i)?.kind != "brand" && RectF(r.left * k - 20, r.top * k - 20, r.right * k + 20, r.bottom * k + 20).contains(e.x, e.y) }?.key ?: -1
                if (drag >= 0) ed.snap() }
            MotionEvent.ACTION_MOVE -> { if (e.pointerCount > 1) return true
                if (drag >= 0 && (moved || abs(e.x - dx0) + abs(e.y - dy0) > 12)) { moved = true; ed.moveLayer(drag, (e.x - lx) / width, (e.y - ly) / height) }
                lx = e.x; ly = e.y }
            MotionEvent.ACTION_UP -> { if (!moved) ed.onStageTap(drag) else ed.onEdited(); drag = -1 }
        }
        return true
    }
}

class EditorActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    var clips = ArrayList<Clip>(); var layers = ArrayList<Layer>(); var sounds = ArrayList<Sound>()
    var W = 1080; var H = 1920; private var origVol = 1f
    private class Snap(val c: List<Clip>, val l: List<Layer>, val s: List<Sound>, val w: Int, val h: Int, val ov: Float)
    private val undo = ArrayList<Snap>(); private val redo = ArrayList<Snap>()
    private var orig: File? = null
    private lateinit var player: ExoPlayer; private lateinit var mplayer: ExoPlayer
    private lateinit var tl: Timeline; private lateinit var stage: Stage
    private lateinit var frame: AspectRatioFrameLayout; private lateinit var box: FrameLayout; private lateinit var tv: TextureView; private lateinit var veil: View
    private lateinit var timeTx: TextView; private lateinit var playBtn: Button; private lateinit var fmtBtn: Button
    private lateinit var tools: LinearLayout
    private val art by lazy { TextArt(this) }
    private var ovKey = "?"; private var scrubbing = false; private var busy = false; private var dirty = false
    private val work by lazy { File(filesDir, "edit").apply { mkdirs() } }   // kept: drafts use these files
    private var draftId = ""
    /* save the unfinished edit (a moment after each change, and when leaving) */
    private fun saveDraft() { if (!dirty || clips.isEmpty() || draftId.isEmpty()) return
        try { Drafts.save(this, draftId, orig?.name?.substringBeforeLast('.') ?: t("New video", "Video baru"), orig?.absolutePath ?: "", W, H, origVol, clips, layers, sounds) } catch (e: Exception) {} }
    private val draftRun = Runnable { saveDraft() }
    private fun openDraft(id: String): Boolean { val d = Drafts.load(this, id) ?: return false
        draftId = id; d.orig.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }?.let { orig = it }
        clips = d.clips; layers = d.layers; sounds = d.sounds; origVol = d.ov; setFormat(d.W, d.H); dirty = true; rebuild(0); layers.filter { it.kind == "pip" && (it.text.isEmpty() || !File(it.text).exists()) }.forEach { makeProxy(it) }; return true }
    private val thumbCache = HashMap<String, Bitmap?>(); private val thumbQ = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val audioCache = HashMap<String, FloatArray?>()
    private var mixFile: File? = null; private var mixGen = 0
    private var vidW = 0; private var vidH = 0
    private var tts: TextToSpeech? = null; private var ttsOk = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) addVideo(u) else if (clips.isEmpty()) finish() }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) addPhoto(u) else if (clips.isEmpty()) finish() }
    private val pickOverlay = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) addOverlayPhoto(u) }
    private val pickMusic = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) addMusic(u) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this); Auth.init(this)
        val d = Ui.px(this, 10)
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(0xFF0B0A09.toInt()); root.setPadding(d, d * 2, d, d / 2)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.addView(sbtn("✕") { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 42)))
        fmtBtn = sbtn("9:16") { formatSheet() }; top.addView(fmtBtn, LinearLayout.LayoutParams(-2, Ui.px(this, 42)).apply { leftMargin = d / 2 })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(sbtn("↶") { doUndo() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 42)).apply { rightMargin = d / 2 })
        top.addView(sbtn("↷") { doRedo() }, LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 42)).apply { rightMargin = d / 2 })
        top.addView(sbtn(t("Export", "Eksport"), true) { save() }, LinearLayout.LayoutParams(-2, Ui.px(this, 42)))
        root.addView(top)

        // picture
        val area = FrameLayout(this)
        frame = AspectRatioFrameLayout(this); frame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        box = FrameLayout(this); box.setBackgroundColor(Color.BLACK); box.clipChildren = true
        bgBlur = android.widget.ImageView(this); bgBlur.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; bgBlur.visibility = View.INVISIBLE
        bgBlur.setColorFilter(0x66000000, android.graphics.PorterDuff.Mode.SRC_ATOP); box.addView(bgBlur, FrameLayout.LayoutParams(-1, -1))
        tv = TextureView(this); box.addView(tv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        repeat(PIPS) { val v = TextureView(this); v.visibility = View.INVISIBLE; box.addView(v, FrameLayout.LayoutParams(10, 10)); pipTvs.add(v) }
        mask = MaskView(this); box.addView(mask, FrameLayout.LayoutParams(-1, -1))
        veil = View(this); veil.alpha = 0f; veil.setBackgroundColor(Color.BLACK); box.addView(veil, FrameLayout.LayoutParams(-1, -1))
        stage = Stage(this, this); box.addView(stage, FrameLayout.LayoutParams(-1, -1))
        frame.addView(box, FrameLayout.LayoutParams(-1, -1))
        area.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        root.addView(area, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d / 2 })

        val pr = FrameLayout(this)
        timeTx = Ui.label(this, "00:00 / 00:00", 13f, Ui.TEXT, true); pr.addView(timeTx, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))
        playBtn = sbtn("▶") { togglePlay() }; pr.addView(playBtn, FrameLayout.LayoutParams(Ui.px(this, 64), Ui.px(this, 42), Gravity.CENTER))
        root.addView(pr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d / 2 })

        tl = Timeline(this, this)
        root.addView(tl, LinearLayout.LayoutParams(-1, Ui.px(this, 196)).apply { topMargin = d / 2 })
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
        tools = LinearLayout(this); tools.orientation = LinearLayout.HORIZONTAL; tools.gravity = Gravity.CENTER_VERTICAL; hs.addView(tools)
        root.addView(hs, LinearLayout.LayoutParams(-1, Ui.px(this, 68)).apply { topMargin = d / 2 })
        setContentView(root)

        player = ExoPlayer.Builder(this).build(); player.setVideoTextureView(tv)
        mplayer = ExoPlayer.Builder(this).build()
        pipTvs.forEach { v -> val pp = ExoPlayer.Builder(this).build(); pp.setVideoTextureView(v); pp.volume = 0f; pplayers.add(pp) }; art.pipFrame = true
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { playBtn.text = if (p) "‖" else "▶"; if (p) loop() else mplayer.pause() }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) { player.pause(); seekTo(0); tl.time = 0; tl.invalidate() } }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { clipLook() }
            override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0) { vidW = (v.width * v.pixelWidthHeightRatio).toInt(); vidH = v.height; fitVideo() } }
        })
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) { toast(t("Please wait, saving…", "Sila tunggu, sedang simpan…")); return }
                if (dirty) { saveDraft(); toast(t("Draft saved. Open it again from Videos ▸ Drafts.", "Draf disimpan. Buka semula dari Video ▸ Draf.")) }
                finish()
            }
        })
        val fn = intent.getStringExtra("f") ?: ""
        val f = if (fn.isNotEmpty()) File(Store.dir(this), File(fn).name) else null
        val dId = intent.getStringExtra("draft") ?: ""
        if (dId.isNotEmpty()) { if (!openDraft(dId)) { toast(t("This draft cannot be opened", "Draf ini tidak boleh dibuka")); finish() } }
        else if (f != null && f.exists()) { orig = f; draftId = "f_" + f.nameWithoutExtension; val (w, h, du) = probe(f); setFormat(w, h); clips.add(Clip(f, 0, du, false).also { it.fileDur = du }); rebuild(0)
            if (Drafts.exists(this, draftId)) AlertDialog.Builder(this).setMessage(t("You have an unfinished edit of this video. Continue it?", "Ada suntingan belum siap untuk video ini. Sambung?"))
                .setPositiveButton(t("Continue", "Sambung")) { _, _ -> openDraft(draftId) }.setNegativeButton(t("Start again", "Mula semula")) { _, _ -> Drafts.delete(this, draftId) }.show() }
        else { draftId = "n_" + System.currentTimeMillis(); main.postDelayed({ askAdd(true) }, 300) }
        mainTools()
    }
    override fun onDestroy() { player.release(); mplayer.release(); pplayers.forEach { it.release() }; tts?.shutdown(); thumbQ.shutdownNow(); readers.values.forEach { try { it.release() } catch (e: Exception) {} }; saveDraft()
        if (!busy) { val keep = Drafts.used(this); work.listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() } }; super.onDestroy() }
    override fun onPause() { super.onPause(); if (player.isPlaying) player.pause(); saveDraft() }

    private fun sbtn(l: String, gold: Boolean = false, f: () -> Unit): Button { val b = Ui.btn(this, l, gold, f); b.textSize = 14f; b.maxLines = 1; b.setPadding(Ui.px(this, 12), Ui.px(this, 2), Ui.px(this, 12), Ui.px(this, 6)); b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0; return b }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun probe(f: File): Triple<Int, Int, Long> = try {
        val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080; var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0; if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
        val du = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) * 1000; r.release(); Triple(w, h, du)
    } catch (e: Exception) { Triple(1080, 1920, 0L) }
    /* the video size from a picture size, like 9:16 → 1080×1920 */
    private fun setFormat(w: Int, h: Int) {
        val ar = w.toFloat() / h
        val (a, b) = when { abs(ar - 9f / 16) < 0.06f -> 1080 to 1920; abs(ar - 16f / 9) < 0.1f -> 1920 to 1080; abs(ar - 1f) < 0.05f -> 1080 to 1080; abs(ar - 0.8f) < 0.04f -> 1080 to 1350
            else -> { var x = w; var y = h; val big = maxOf(x, y); if (big > 1920) { x = x * 1920 / big; y = y * 1920 / big }; x / 2 * 2 to y / 2 * 2 } }
        W = a; H = b; frame.setAspectRatio(W.toFloat() / H)
        fmtBtn.text = when { W * 16 == H * 9 -> "9:16"; W * 9 == H * 16 -> "16:9"; W == H -> "1:1"; W * 5 == H * 4 -> "4:5"; else -> "${W}×$H" } + " ▾"
        ovKey = "?"
    }
    /* small pictures for the timeline; made only while paused, one reader per file (so playing stays smooth, also when sped up) */
    private val readers = HashMap<String, MediaMetadataRetriever>()
    private val thumbWant = LinkedHashSet<String>()
    fun thumb(c: Clip, at: Long): Bitmap? {
        val k = c.file.absolutePath + "@" + at
        if (thumbCache.containsKey(k)) return thumbCache[k]
        if (player.isPlaying || !thumbWant.add(k)) return null
        thumbQ.execute {
            val b = try { val r = readers.getOrPut(c.file.absolutePath) { MediaMetadataRetriever().also { it.setDataSource(c.file.absolutePath) } }
                r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 160) } catch (e: Exception) { null }
            main.post { thumbCache[c.file.absolutePath + "@" + at] = b; thumbWant.remove(k); tl.invalidate() }
        }
        return null
    }
    /* ---------------- time ---------------- */
    fun total() = clips.sumOf { it.dur }
    private fun start(i: Int): Long { var o = 0L; for (k in 0 until minOf(i, clips.size)) o += clips[k].dur; return o }
    private fun now(): Long { val i = player.currentMediaItemIndex; val c = clips.getOrNull(i) ?: return 0; return start(i) + (player.currentPosition * 1000 / c.speed).toLong() }
    private fun locate(u: Long): Pair<Int, Long> { var o = 0L; clips.forEachIndexed { i, c -> if (u < o + c.dur) return i to (u - o); o += c.dur }; return maxOf(0, clips.size - 1) to (clips.lastOrNull()?.dur ?: 0) }
    private fun seekTo(u: Long) { if (clips.isEmpty()) return; val (i, l) = locate(u); player.seekTo(i, (l * clips[i].speed / 1000).toLong()); syncMusic(u, true); paintOverlay(u); veilAt(u); pipSync(u, true) }
    private fun fmt(u: Long) = String.format(Locale.US, "%02d:%02d", u / 60_000_000, (u / 1_000_000) % 60)
    private fun showTime(u: Long) { timeTx.text = fmt(u) + " / " + fmt(total()) }
    private fun loop() {
        if (!player.isPlaying) return
        if (!scrubbing) { val u = now(); tl.time = u; if (++tick % 2 == 0) tl.invalidate(); showTime(u); paintOverlay(u); veilAt(u); syncMusic(u, false); pipSync(u, false) }
        main.postDelayed({ loop() }, 33)
    }
    /* ---------------- video over video ---------------- */
    private val PIPS = 3
    private val pipTvs = ArrayList<TextureView>(); private val pplayers = ArrayList<ExoPlayer>(); private val pipFiles = arrayOfNulls<String>(3)
    private val pickPip = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) addPip(u) }
    private fun addPip(u: Uri) {
        if (clips.isEmpty()) return
        val w = wait(t("Loading video…", "Memuatkan video…"))
        Thread { val f = copyIn(u, "pip_", ".mp4"); val pr = f?.let { probe(it) }
            main.post { w.dismiss(); if (f == null || pr == null || pr.third <= 0) { toast(t("This video cannot be opened", "Video ini tidak boleh dibuka")); return@post }
                snap(); val l = Layer("pip", tl.time, minOf(total(), tl.time + pr.third)); l.file = f.absolutePath; l.ar = pr.first.toFloat() / pr.second; l.vdur = pr.third; l.x = 0.5f; l.y = 0.32f; l.anim = "none"
                if (l.e - l.s < 500_000) { l.s = maxOf(0, total() - minOf(pr.third, total())); l.e = total() }
                layers.add(l); onSelect(Sel.L(layers.size - 1)); rebuild(tl.time); toast(t("Drag to move, two fingers to resize", "Seret untuk alih, dua jari untuk ubah saiz")); makeProxy(l) } }.start()
    }
    /* up to 3 videos on top play at the same time, each in its own small player */
    /* a small 360p copy of a video on top, so the preview stays smooth (saving still uses the real video) */
    private fun makeProxy(l: Layer) {
        val src = File(l.file); val out = File(work, "px_" + src.nameWithoutExtension + ".mp4"); if (out.exists()) { l.text = out.absolutePath; return }
        val h = 360; val w = ((h * l.ar).toInt() / 2 * 2).coerceAtLeast(2)
        val e = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src))).setRemoveAudio(true).setEffects(Effects(listOf(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)))).build()
        val tmp = File(work, "px_tmp_" + System.currentTimeMillis() + ".mp4")
        try { Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { tmp.renameTo(out); layers.filter { it.file == l.file }.forEach { it.text = out.absolutePath }; pipFiles.fill(null); pipSync(tl.time, true) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { tmp.delete() }
            }).build().start(e, tmp.absolutePath) } catch (x: Exception) {}
    }
    private fun pipSrc(l: Layer) = l.text.takeIf { it.isNotEmpty() && File(it).exists() } ?: l.file
    private fun pipSync(u: Long, hard: Boolean) {
        val act = layers.filter { it.kind == "pip" && u >= it.s && u < it.e }.take(PIPS)
        for (k in 0 until PIPS) {
            val tv = pipTvs[k]; val pp = pplayers[k]; val l = act.getOrNull(k)
            if (l == null || box.width <= 0) { if (tv.visibility == View.VISIBLE) { tv.visibility = View.INVISIBLE; pp.pause() }; continue }
            val l0 = l; val sf = pipSrc(l0); if (pipFiles[k] != sf) { pipFiles[k] = sf; pp.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(sf)))); pp.prepare() }
            val e = l0.at(u)
            val kk = box.width.toFloat() / W; val m = minOf(W, H).toFloat(); val w = m * 0.42f * e.scale; val h = w / l0.ar
            val lp = tv.layoutParams as FrameLayout.LayoutParams; val nw = (w * kk).toInt(); val nh = (h * kk).toInt(); val nl = ((e.x * W - w / 2) * kk).toInt(); val nt = ((e.y * H - h / 2) * kk).toInt()
            if (lp.width != nw || lp.height != nh || lp.leftMargin != nl || lp.topMargin != nt) { lp.width = nw; lp.height = nh; lp.leftMargin = nl; lp.topMargin = nt; lp.gravity = Gravity.TOP or Gravity.START; tv.layoutParams = lp }
            tv.visibility = View.VISIBLE
            val target = (u - l.s + l.vin) / 1000
            if (hard || abs(pp.currentPosition - target) > 250) pp.seekTo(target)
            if (player.isPlaying && !pp.isPlaying) pp.play() else if (!player.isPlaying && pp.isPlaying) pp.pause()
        }
    }
    private var tick = 0
    private fun togglePlay() { if (clips.isEmpty()) return; if (player.isPlaying) player.pause() else { if (tl.time >= total() - 60_000) seekTo(0); player.play() } }
    fun onScrub(u: Long) { scrubbing = true; if (player.isPlaying) player.pause(); seekTo(u); showTime(u); veilAt(u); main.removeCallbacks(endScrub); main.postDelayed(endScrub, 250) }
    private val endScrub = Runnable { scrubbing = false }
    private fun syncMusic(u: Long, hard: Boolean) {
        if (sounds.isEmpty() || mixFile == null) { if (mplayer.isPlaying) mplayer.pause(); return }
        if (hard || abs(mplayer.currentPosition * 1000 - u) > 150_000) mplayer.seekTo(u / 1000)
        if (player.isPlaying && !mplayer.isPlaying) mplayer.play()
    }
    /* switch effects and clip effects in the preview (the same maths as when saving) */
    private fun veilAt(u: Long) {
        if (clips.isEmpty()) return
        val (i, l) = locate(u); val c = clips[i]
        val st = Fx.state(c.fx, if (i > 0) c.trans else "none", clips.getOrNull(i + 1)?.trans ?: "none", l, c.dur, if (c.fadeVid) c.fi else 0f, if (c.fadeVid) c.fo else 0f)
        if (i == player.currentMediaItemIndex && (c.fi > 0 || c.fo > 0)) player.volume = (c.vol * origVol * c.env(l)).coerceIn(0f, 1f)
        val white = st.white > st.dark; veil.setBackgroundColor(if (white) Color.WHITE else Color.BLACK); veil.alpha = maxOf(st.dark, st.white)
        mask.st = st; mask.invalidate()
        if (i == player.currentMediaItemIndex) {
            val k = if (c.kf.isBlank()) null else Keys.at(Keys.parse(c.kf), l)
            val z = if (c.stab) Stab.ZOOM else 1f; val ks = k?.s ?: 1f
            val (cx, cy) = if (c.stab) Stab.at(Stab.load(this, c.file), c.inUs + (l * c.speed).toLong()) else (0f to 0f)
            tv.scaleX = (if (c.mirror) -1f else 1f) * st.s * z * ks; tv.scaleY = st.s * z * ks; tv.rotation = c.rot + st.rot
            tv.translationX = st.tx * box.width / 2 + (k?.x ?: 0f) * box.width + cx * tv.width * z * ks
            tv.translationY = -st.ty * box.height / 2 + (k?.y ?: 0f) * box.height + cy * tv.height * z * ks
            blurBg(c) }
    }
    /* blurred sides in the preview: a tiny copy of the picture, stretched behind it */
    private lateinit var bgBlur: android.widget.ImageView; private var blurAt = 0L
    private fun blurBg(c: Clip) {
        val on = c.blur && !c.fill
        bgBlur.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (!on) return
        val now = android.os.SystemClock.uptimeMillis(); if (now - blurAt < 250) return; blurAt = now
        try { tv.getBitmap(24, maxOf(8, 24 * tv.height / maxOf(1, tv.width)))?.let { bgBlur.setImageBitmap(it) } } catch (e: Exception) {}
    }
    class MaskView(c: Context) : View(c) { var st: Fx.St? = null; override fun onDraw(cv: Canvas) { st?.let { Fx.drawMask(cv, it, width.toFloat(), height.toFloat()) } } }
    private lateinit var mask: MaskView
    fun moveClip(from: Int, to: Int) {
        if (from < 0 || from >= clips.size) return
        if (from != to) { val c = clips.removeAt(from); clips.add(to, c) } else undo.removeLastOrNull()
        tl.sel = Sel.C(to); rebuild(start(to))
    }
    private fun paintOverlay(u: Long, force: Boolean = false) {
        val k = art.key(layers, u) + "#" + W + "x" + H; if (k == ovKey && !force) return; ovKey = k
        val s = minOf(1f, 720f / maxOf(W, H)); val rr = HashMap<Int, RectF>()
        val b = art.render(layers, u, (W * s).toInt().coerceAtLeast(2), (H * s).toInt().coerceAtLeast(2), rr)
        stage.bmp = b; stage.rects = rr.mapValues { RectF(it.value.left / s, it.value.top / s, it.value.right / s, it.value.bottom / s) }; stage.invalidate()
    }
    /* the clip now playing: speed, sound level, colours, turn */
    private fun clipLook() {
        val c = clips.getOrNull(player.currentMediaItemIndex) ?: return
        player.playbackParameters = PlaybackParameters(c.speed); player.volume = (c.vol * origVol).coerceIn(0f, 1f)
        val (m, o) = Looks.matrix(c.filter())
        val cm = ColorMatrix(floatArrayOf(m[0], m[4], m[8], 0f, o[0] * 255, m[1], m[5], m[9], 0f, o[1] * 255, m[2], m[6], m[10], 0f, o[2] * 255, 0f, 0f, 0f, 1f, 0f))
        if (c.filter() == "brightness(1.0) contrast(1.0) saturate(1.0)") tv.setLayerType(View.LAYER_TYPE_NONE, null) else tv.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        fitVideo()
    }
    private fun fitVideo() {
        if (vidW <= 0 || box.width <= 0) return
        val c = clips.getOrNull(player.currentMediaItemIndex)
        val rot = c?.rot ?: 0; val side = rot % 180 != 0
        val ew = if (side) vidH else vidW; val eh = if (side) vidW else vidH
        val k = if (c?.fill == true) maxOf(box.width.toFloat() / ew, box.height.toFloat() / eh) else minOf(box.width.toFloat() / ew, box.height.toFloat() / eh)
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        val nw = (vidW * k).toInt(); val nh = (vidH * k).toInt()
        if (lp.width != nw || lp.height != nh) { lp.width = nw; lp.height = nh; lp.gravity = Gravity.CENTER; tv.layoutParams = lp }
        tv.rotation = rot.toFloat(); tv.scaleX = if (c?.mirror == true) -1f else 1f
        veilAt(tl.time)
    }

    /* rebuild the player after a change, staying at the same time */
    private fun rebuild(at: Long) {
        val items = clips.map { c -> MediaItem.Builder().setUri(Uri.fromFile(c.file)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inUs / 1000).setEndPositionMs(c.outUs / 1000).build()).build() }
        val tot = total(); val u = at.coerceIn(0, maxOf(0, tot - 1))
        layers.forEach { if (it.e > tot) it.e = tot; if (it.kind == "brand") it.e = tot }; layers.removeAll { it.s >= tot - 100_000 && tot > 0 }
        sounds.forEach { if (it.e > tot) it.e = tot }; sounds.removeAll { it.s >= tot - 100_000 && tot > 0 }
        builtIn = clips.map { it.inUs }
        if (items.isEmpty()) player.clearMediaItems() else { val (i, l) = locate(u); player.setMediaItems(items, i, (l * clips[i].speed / 1000).toLong()); player.prepare() }
        when (val s = tl.sel) { is Sel.C -> if (s.i >= clips.size) tl.sel = Sel.None; is Sel.L -> if (s.i >= layers.size) tl.sel = Sel.None; is Sel.S -> if (s.i >= sounds.size) tl.sel = Sel.None; else -> {} }
        tl.time = u; tl.invalidate(); ovKey = "?"; paintOverlay(u); showTime(u); clipLook(); refreshTools(); mixLater()
        main.removeCallbacks(draftRun); main.postDelayed(draftRun, 800)
    }
    fun snap() { undo.add(Snap(clips.map { it.copy() }, layers.map { it.copy() }, sounds.map { it.copy() }, W, H, origVol)); if (undo.size > 50) undo.removeAt(0); redo.clear(); dirty = true }
    private fun restore(s: Snap) { clips = ArrayList(s.c); layers = ArrayList(s.l); sounds = ArrayList(s.s); origVol = s.ov; setFormat(s.w, s.h); tl.sel = Sel.None; rebuild(tl.time) }
    private fun doUndo() { val s = undo.removeLastOrNull() ?: run { toast(t("Nothing to undo", "Tiada untuk undo")); return }; redo.add(Snap(clips.map { it.copy() }, layers.map { it.copy() }, sounds.map { it.copy() }, W, H, origVol)); restore(s) }
    private fun doRedo() { val s = redo.removeLastOrNull() ?: return; undo.add(Snap(clips.map { it.copy() }, layers.map { it.copy() }, sounds.map { it.copy() }, W, H, origVol)); restore(s) }
    private var peek: Pair<Clip, Long>? = null
    private var builtIn: List<Long> = emptyList()
    fun trimPeek(c: Clip, mediaUs: Long) {
        val i = clips.indexOf(c); if (i < 0) return
        if (player.isPlaying) player.pause()
        peek = c to mediaUs; main.removeCallbacks(peekRun); main.postDelayed(peekRun, 60)
    }
    private val peekRun = Runnable { val (c, m) = peek ?: return@Runnable; val i = clips.indexOf(c); if (i < 0) return@Runnable
        // the player still has the old cut: show the edge picture from the whole file position
        player.seekTo(i, maxOf(0L, (m - (builtIn.getOrNull(i) ?: c.inUs)) / 1000)) }
    fun onLive() { ovKey = "?"; paintOverlay(tl.time); showTime(tl.time) }
    fun onEdited() { rebuild(tl.time) }
    fun onSelect(s: Sel) { tl.sel = s; stage.sel = (s as? Sel.L)?.i ?: -1; tl.invalidate(); stage.invalidate(); refreshTools() }
    fun onStageTap(i: Int) { if (i >= 0) { onSelect(Sel.L(i)); if (layers[i].kind == "text") textSheet(layers[i]) } else if (tl.sel is Sel.L) onSelect(Sel.None) else togglePlay() }
    fun moveLayer(i: Int, dx: Float, dy: Float) { val l = layers.getOrNull(i) ?: return
        if (l.kf.isNotBlank()) { val e = l.at(tl.time); l.kf = Keys.put(l.kf, Kf(tl.time - l.s, e.scale, (e.x + dx).coerceIn(0.05f, 0.95f) - 0.5f, (e.y + dy).coerceIn(0.05f, 0.95f) - 0.5f)) }
        else { l.x = (l.x + dx).coerceIn(0.05f, 0.95f); l.y = (l.y + dy).coerceIn(0.05f, 0.95f) } onSelect(Sel.L(i)); paintOverlay(tl.time, true); pipSync(tl.time, false) }
    fun scaleLayer(i: Int, f: Float) { val l = layers.getOrNull(i) ?: return; val mx = if (l.kind == "pip") 2.4f else 4f
        if (l.kf.isNotBlank()) { val e = l.at(tl.time); l.kf = Keys.put(l.kf, Kf(tl.time - l.s, (e.scale * f).coerceIn(0.3f, mx), e.x - 0.5f, e.y - 0.5f)) } else l.scale = (l.scale * f).coerceIn(0.3f, mx); paintOverlay(tl.time, true); pipSync(tl.time, false) }
    fun onAddEnd() { tl.time = maxOf(0, total() - 1); askAdd(false) }
    fun onTrans(i: Int) {
        val c = clips[i]; val opts = Fx.TRANS.map { it to Fx.transName(it) }
        AlertDialog.Builder(this).setTitle(t("Switch effect", "Kesan tukar"))
            .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == c.trans }) { dl, w -> snap(); c.trans = opts[w].first; dl.dismiss(); val at = maxOf(0, start(i) - 700_000); rebuild(at); main.postDelayed({ if (!player.isPlaying) player.play() }, 150) }
            .setNeutralButton(t("Use for all", "Guna untuk semua")) { _, _ -> snap(); val v = c.trans.takeIf { it != "none" } ?: "fade"; clips.forEachIndexed { k, q -> if (k > 0) q.trans = v }; rebuild(tl.time) }
            .show()
    }

    /* ---------------- tool bars ---------------- */
    private fun tool(icon: String, label: String, on: Boolean = false, f: () -> Unit) {
        val v = LinearLayout(this); v.orientation = LinearLayout.VERTICAL; v.gravity = Gravity.CENTER
        v.addView(Ui.center(Ui.label(this, icon, 19f, if (on) Ui.GOLD else Ui.TEXT)))
        v.addView(Ui.center(Ui.label(this, label, 11f, if (on) Ui.GOLD else Ui.SUB, true)).apply { maxLines = 1 })
        v.setPadding(Ui.px(this, 8), 0, Ui.px(this, 8), 0); v.setOnClickListener { f() }
        v.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33D9B44A), null, null)
        tools.addView(v, LinearLayout.LayoutParams(Ui.px(this, 66), -1))
    }
    private fun back() = tool("‹", t("Back", "Kembali")) { onSelect(Sel.None) }
    private var page = "main"
    private fun refreshTools() { when (val s = tl.sel) { is Sel.C -> clipTools(s.i); is Sel.L -> layerTools(s.i); is Sel.S -> soundTools(s.i); else -> if (page == "audio") audioTools() else mainTools() } }
    private fun mainTools() {
        page = "main"; tools.removeAllViews()
        tool("📋", t("Templates", "Templat")) { templateSheet() }
        tool("✂", t("Edit", "Edit")) { if (clips.isNotEmpty()) onSelect(Sel.C(locate(tl.time).first)) }
        tool("♪", t("Audio", "Audio")) { audioTools() }
        tool("T", t("Text", "Teks")) { newText() }
        tool("😊", t("Stickers", "Stiker")) { stickerSheet() }
        tool("🖼", t("Overlay", "Tindih")) { pickOverlay.launch("image/*") }
        tool("🎞", t("Video on top", "Video atas")) { pickPip.launch("video/*") }
        tool("✨", t("Effects", "Efek")) { if (clips.isNotEmpty()) fxSheet(locate(tl.time).first) }
        tool("🎨", t("Filters", "Filter")) { if (clips.isNotEmpty()) filterSheet(locate(tl.time).first) }
        tool("☀", t("Adjust", "Laras")) { if (clips.isNotEmpty()) adjustSheet(locate(tl.time).first) }
        tool("🏷", t("Brand", "Jenama")) { brand() }
        tool("⬚", t("Format", "Format")) { formatSheet() }
        tool("＋", t("Add clip", "Tambah klip")) { askAdd(false) }
    }
    private fun audioTools() {
        page = "audio"; tools.removeAllViews()
        tool("‹", t("Back", "Kembali")) { mainTools() }
        tool("♪", t("Music", "Muzik")) { musicSheet() }
        tool("🔊", t("Sound FX", "Kesan bunyi")) { sfxSheet() }
        tool("🗣", t("AI voice", "Suara AI")) { voiceSheet(null) }
        tool("🎙", t("Record voice", "Rakam suara")) { recSheet() }
        tool(if (origVol == 0f) "🔇" else "🔈", t("Original", "Bunyi asal"), origVol != 1f) { volSheet(t("Original sound (all clips)", "Bunyi asal (semua klip)"), origVol) { v -> origVol = v; clipLook() } }
    }
    private fun clipTools(i: Int) {
        page = "clip"; tools.removeAllViews(); val c = clips.getOrNull(i) ?: return; back()
        tool("✂", t("Split", "Belah")) { split() }
        tool("⏩", t("Speed", "Laju"), c.speed != 1f) { speedSheet(i) }
        tool("〰", t("Fade", "Pudar"), c.fi > 0 || c.fo > 0) { fadeSheet(c, null) }
        tool("🔈", t("Volume", "Volum"), c.vol != 1f) { volSheet(t("Clip volume", "Volum klip"), c.vol) { v -> c.vol = v; clipLook() } }
        tool("✨", t("Effects", "Efek"), c.fx != "none") { fxSheet(i) }
        tool("🎨", t("Filters", "Filter"), c.look != "std") { filterSheet(i) }
        tool("☀", t("Adjust", "Laras"), c.bri != 0f || c.con != 0f || c.sat != 0f) { adjustSheet(i) }
        tool("◆", "Keyframe", c.kf.isNotBlank()) { kfSheet(i) }
        if (!c.still) tool("🫨", t("Steady", "Stabil"), c.stab) { stabilise(i) }
        tool("🌫", t("Blur sides", "Latar kabur"), c.blur) { snap(); c.blur = !c.blur; if (c.blur) c.fill = false; rebuild(tl.time); toast(if (c.blur) t("Blurred sides instead of black bars", "Tepi kabur ganti jalur hitam") else t("Black bars", "Jalur hitam")) }
        if (!c.still) tool("🎧", t("Clean sound", "Buang bising"), c.dn) { snap(); c.dn = !c.dn; refreshTools(); toast(if (c.dn) t("Noise cut (you hear it in the saved video)", "Bising dibuang (dengar dalam video yang disimpan)") else t("Off", "Tutup")) }
        tool("⛶", t("Fill frame", "Isi bingkai"), c.fill) { snap(); c.fill = !c.fill; rebuild(tl.time); toast(if (c.fill) t("Fills the frame (edges cut)", "Isi bingkai (tepi dipotong)") else t("Whole picture (bars if needed)", "Gambar penuh (jalur jika perlu)")) }
        tool("⟳", t("Rotate", "Putar"), c.rot != 0) { snap(); c.rot = (c.rot + 90) % 360; rebuild(tl.time) }
        tool("⇋", t("Mirror", "Cermin"), c.mirror) { snap(); c.mirror = !c.mirror; rebuild(tl.time) }
        if (!c.still) tool("⏪", t("Reverse", "Terbalik")) { reverse(i) }
        tool("❄", t("Freeze", "Beku")) { freeze() }
        if (c.still) tool("⏱", t("Duration", "Tempoh")) { stillDur(i) }
        tool("⧉", t("Duplicate", "Salin")) { snap(); clips.add(i + 1, c.copy()); rebuild(tl.time) }
        tool("🗑", t("Delete", "Padam")) { delClip(i) }
    }
    private fun layerTools(i: Int) {
        page = "layer"; tools.removeAllViews(); val l = layers.getOrNull(i) ?: return; back()
        if (l.kind == "text") { tool("✎", t("Edit", "Ubah")) { textSheet(l) }; tool("Aa", t("Style", "Gaya")) { textSheet(l) } }
        if (l.kind != "brand") { tool("＋", t("Bigger", "Besar")) { snap(); l.scale = minOf(4f, l.scale * 1.15f); paintOverlay(tl.time, true) }
            tool("－", t("Smaller", "Kecil")) { snap(); l.scale = maxOf(0.3f, l.scale / 1.15f); paintOverlay(tl.time, true) }
            tool("✨", t("Animation", "Animasi"), l.anim != "none") { animSheet(l) }
            tool("◆", "Keyframe", l.kf.isNotBlank()) { layerKf(l) } }
        if (l.kind == "pip") tool("🟩", t("Green screen", "Skrin hijau"), l.key != 0) { keySheet(l) }
        tool("⇤", t("Start here", "Mula sini")) { if (tl.time < l.e - 300_000) { snap(); l.s = tl.time; rebuild(tl.time) } }
        tool("⇥", t("End here", "Tamat sini")) { if (tl.time > l.s + 300_000) { snap(); l.e = tl.time; rebuild(tl.time) } }
        if (l.kind != "brand") tool("⧉", t("Duplicate", "Salin")) { snap(); layers.add(l.copy().also { it.y = minOf(0.95f, it.y + 0.06f) }); onSelect(Sel.L(layers.size - 1)); rebuild(tl.time) }
        tool("🗑", t("Delete", "Padam")) { snap(); layers.removeAt(i); onSelect(Sel.None); rebuild(tl.time) }
    }
    private fun soundTools(i: Int) {
        page = "sound"; tools.removeAllViews(); val s = sounds.getOrNull(i) ?: return; back()
        tool("🔈", t("Volume", "Volum"), s.vol != 1f) { volSheet(t("Volume", "Volum"), s.vol) { v -> s.vol = v; mixLater() } }
        if (s.kind == "music") tool("〰", t("Fade", "Pudar"), s.fade && (s.fi > 0 || s.fo > 0)) { fadeSheet(null, s) }
        if (s.kind == "music") tool("🥁", t("Cut to beat", "Ikut rentak")) { beatSheet(s) }
        if (s.kind == "voice") tool("✎", t("Edit", "Ubah")) { voiceSheet(s) }
        tool("⇤", t("Start here", "Mula sini")) { if (tl.time < s.e - 300_000) { snap(); val d = tl.time - s.s; s.s = tl.time; if (s.kind != "music") s.e = minOf(total(), s.e + d); rebuild(tl.time) } }
        tool("⇥", t("End here", "Tamat sini")) { if (tl.time > s.s + 300_000) { snap(); s.e = tl.time; rebuild(tl.time) } }
        tool("⧉", t("Duplicate", "Salin")) { snap(); val d = s.e - s.s; sounds.add(s.copy().also { it.s = minOf(total() - 300_000, tl.time); it.e = minOf(total(), it.s + d) }); rebuild(tl.time) }
        tool("🗑", t("Delete", "Padam")) { snap(); sounds.removeAt(i); onSelect(Sel.None); rebuild(tl.time) }
    }

    /* ---------------- clip actions ---------------- */
    private fun split() {
        if (clips.isEmpty()) return
        val (i, l) = locate(tl.time); val c = clips[i]; val lm = (l * c.speed).toLong()
        if (l < 150_000 || c.dur - l < 150_000) { toast(t("Move the line inside a clip", "Gerakkan garis ke dalam klip")); return }
        snap(); val a = c.copy().also { it.outUs = c.inUs + lm }; val b = c.copy().also { it.inUs = c.inUs + lm; it.trans = "none" }
        clips[i] = a; clips.add(i + 1, b); tl.sel = Sel.C(i + 1); rebuild(tl.time)
    }
    private fun delClip(i: Int) {
        if (clips.size <= 1) { toast(t("Keep at least one clip", "Perlu sekurang-kurangnya satu klip")); return }
        snap(); val o = start(i); val d = clips[i].dur; clips.removeAt(i)
        layers.removeAll { it.kind != "brand" && it.s >= o && it.e <= o + d }; sounds.removeAll { it.s >= o && it.e <= o + d }
        layers.forEach { if (it.s >= o + d) { it.s -= d; it.e -= d } }; sounds.forEach { if (it.s >= o + d) { it.s -= d; it.e -= d } }
        onSelect(Sel.None); rebuild(o)
    }
    private fun insertAt(c: Clip, atEnd: Boolean) {
        snap()
        if (clips.isEmpty()) { clips.add(c); rebuild(0); return }
        var (i, l) = locate(tl.time); val cur = clips[i]; val at: Long
        if (atEnd || tl.time >= total() - 150_000 || cur.dur - l < 150_000) { i = if (atEnd) clips.size else i + 1; at = start(i) }
        else if (l < 150_000) at = start(i)
        else { val lm = (l * cur.speed).toLong(); clips[i] = cur.copy().also { it.outUs = cur.inUs + lm }; clips.add(i + 1, cur.copy().also { it.inUs = cur.inUs + lm; it.trans = "none" }); i += 1; at = start(i) }
        clips.add(i, c)
        layers.forEach { if (it.kind != "brand" && it.s >= at) { it.s += c.dur; it.e += c.dur } }; sounds.forEach { if (it.s >= at) { it.s += c.dur; it.e += c.dur } }
        tl.sel = Sel.C(i); rebuild(at)
    }
    private var addEnd = false
    /* ---------------- templates ---------------- */
    private var tplPending: Templates.Item? = null
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> val tp = tplPending; if (tp != null && us.isNotEmpty()) applyTemplate(tp, us) else if (clips.isEmpty()) finish() }
    private fun templateSheet() {
        val s = Ui.sheet(this, t("Templates", "Templat")); val b = s.body
        b.addView(Ui.hint(this, t("Pick a template, then choose your videos and photos. Everything else is done for you.", "Pilih templat, kemudian pilih video dan gambar anda. Yang lain siap sendiri.")))
        fun row(it: Templates.Item, parent: LinearLayout) {
            val c = Ui.card(this); val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            val tx = LinearLayout(this); tx.orientation = LinearLayout.VERTICAL
            tx.addView(Ui.label(this, it.name, 15f, Ui.TEXT, true))
            val song = MusicLib.ALL.firstOrNull { m -> m.id == it.music }
            tx.addView(Ui.label(this, (if (song != null) "🎵 " + t(song.en, song.ms) + " · " else "") + "${it.slots} " + t("clips", "klip") + " · " + String.format(Locale.US, "%.0fs", it.secs), 12f, Ui.SUB))
            r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            if (it.kind == "mine" || (it.kind == "server" && Auth.isAdmin)) r.addView(Ui.btn(this, "🗑") { AlertDialog.Builder(this).setMessage(t("Delete this template?", "Padam templat ini?"))
                .setPositiveButton(t("Delete", "Padam")) { _, _ -> if (it.kind == "mine") { Templates.deleteMine(this, it.id); parent.removeView(c) } else Thread { val ok = Templates.unpublish(it.id); main.post { if (ok) parent.removeView(c) } }.start() }
                .setNegativeButton(t("Cancel", "Batal"), null).show() }, LinearLayout.LayoutParams(Ui.px(this, 52), -2))
            c.addView(r); c.setOnClickListener { _ -> s.dialog.dismiss(); useTemplate(it) }
            parent.addView(c, Ui.lp(this, 6))
        }
        // save / publish the current edit
        if (clips.isNotEmpty()) {
            b.addView(Ui.btn(this, "💾 " + t("Save this edit as a template", "Simpan suntingan ini sebagai templat")) { askName { n -> Templates.saveMine(this, Templates.fromProject(n, W, H, clips, layers, sounds)); toast(t("Template saved", "Templat disimpan")); s.dialog.dismiss() } }, Ui.lp(this, 8))
            if (Auth.isAdmin) b.addView(Ui.btn(this, "📢 " + t("Publish to everyone", "Terbitkan kepada semua"), true) { askName { n -> Thread { val ok = Templates.publish(Templates.fromProject(n, W, H, clips, layers, sounds)); main.post { toast(if (ok) t("Published", "Diterbitkan") else t("Failed", "Gagal")); if (ok) s.dialog.dismiss() } }.start() } }, Ui.lp(this, 6))
        }
        val mine = Templates.mine(this)
        if (mine.isNotEmpty()) { b.addView(Ui.section(this, t("My templates", "Templat saya"))); val m = LinearLayout(this); m.orientation = LinearLayout.VERTICAL; mine.forEach { row(it, m) }; b.addView(m) }
        b.addView(Ui.section(this, t("From ProMultiCam", "Dari ProMultiCam"))); val sv = LinearLayout(this); sv.orientation = LinearLayout.VERTICAL; b.addView(sv)
        val wait = Ui.label(this, t("Loading…", "Memuatkan…"), 13f, Ui.SUB); sv.addView(wait)
        Thread { val l = Templates.server(); main.post { sv.removeView(wait); if (l.isEmpty()) sv.addView(Ui.label(this, t("None yet", "Belum ada"), 13f, Ui.SUB)) else l.forEach { row(it, sv) } } }.start()
        // free built-in templates, by group
        b.addView(Ui.section(this, t("Free templates", "Templat percuma"))); val all = Templates.builtin()
        val cf = Flow(this, Ui.px(this, 6)); val cb = ArrayList<Button>(); val bl = LinearLayout(this); bl.orientation = LinearLayout.VERTICAL
        fun show(cat: String) { bl.removeAllViews(); all.filter { cat == "all" || it.cat == cat }.forEach { row(it, bl) }; cb.forEachIndexed { j, q -> Ui.setPill(this, q, Templates.CATS[j] == cat) } }
        Templates.CATS.forEach { k -> val q = Ui.pill(this, Templates.catName(k), false) { show(k) }; q.textSize = 12f; cb.add(q); cf.addView(q) }
        b.addView(cf, Ui.lp(this, 4)); b.addView(bl, Ui.lp(this, 6)); show("all")
        s.dialog.setOnCancelListener { if (clips.isEmpty()) finish() }
        s.dialog.show()
    }
    private fun askName(f: (String) -> Unit) {
        val e = Ui.input(this, t("Template name", "Nama templat"))
        AlertDialog.Builder(this).setTitle(t("Name", "Nama")).setView(LinearLayout(this).apply { setPadding(Ui.px(this@EditorActivity, 20), Ui.px(this@EditorActivity, 8), Ui.px(this@EditorActivity, 20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("OK") { _, _ -> val n = e.text.toString().trim(); if (n.isNotEmpty()) f(n.take(40)) }.setNegativeButton(t("Cancel", "Batal"), null).show()
    }
    private fun useTemplate(it: Templates.Item) {
        tplPending = it
        toast(t("Choose ${it.slots} videos or photos (fewer is fine; they repeat)", "Pilih ${it.slots} video atau gambar (kurang pun boleh; ia diulang)"))
        pickMany.launch(arrayOf("video/*", "image/*"))
    }
    /* fill the template's slots with the chosen media, in order */
    private fun applyTemplate(tp: Templates.Item, us: List<Uri>) {
        val j = tp.j; val w = wait(t("Making your video…", "Membuat video anda…"))
        val tw = j.optInt("W", 1080); val th = j.optInt("H", 1920)
        Thread {
            val media = us.mapIndexedNotNull { i, u -> val ty = contentResolver.getType(u) ?: ""; val isImg = ty.startsWith("image")
                copyIn(u, if (isImg) "img_" else "v_", if (isImg) ".jpg" else ".mp4")?.let { it to isImg } }
            val a = j.optJSONArray("slots") ?: org.json.JSONArray(); val out = ArrayList<Clip>()
            for (k in 0 until a.length()) {
                if (media.isEmpty()) break
                val o = a.getJSONObject(k); val dur = (o.optDouble("dur", 2.0) * 1e6).toLong(); val (f, isImg) = media[k % media.size]
                val c: Clip? = if (isImg) { val bm = loadBitmap(f.absolutePath); if (bm == null) null else { val v = File(work, "p_" + System.currentTimeMillis() + "_" + k + ".mp4")
                        try { StillClip.make(bm, tw, th, dur, v); val du = probe(v).third.takeIf { it > 0 } ?: dur; Clip(v, 0, du, true).also { it.fileDur = du; it.img = f.absolutePath } } catch (e: Exception) { null } } }
                    else { val pr = probe(f); if (pr.third <= 0) null else { val st = if (pr.third > dur * 2) (pr.third - dur) / 3 else 0L; Clip(f, st, minOf(pr.third, st + dur), false).also { it.fileDur = pr.third } } }
                c?.let { it.fx = o.optString("fx", "none"); it.trans = if (k == 0) "none" else o.optString("trans", "none"); it.look = o.optString("look", "std"); it.fi = o.optDouble("fi", 0.0).toFloat(); it.fo = o.optDouble("fo", 0.0).toFloat()
                    it.speed = o.optDouble("speed", 1.0).toFloat(); it.bri = o.optDouble("bri", 0.0).toFloat(); it.con = o.optDouble("con", 0.0).toFloat(); it.sat = o.optDouble("sat", 0.0).toFloat(); out.add(it) }
            }
            // the template's own music (made by the app)
            val mf = j.optString("music").takeIf { it.isNotEmpty() }?.let { id -> try { MusicLib.file(this, id).also { f -> audioCache[f.absolutePath] = Snd.readWav(f) } } catch (e: Exception) { null } }
            main.post { w.dismiss()
                if (out.isEmpty()) { toast(t("These files cannot be used", "Fail ini tidak boleh digunakan")); if (clips.isEmpty()) finish(); return@post }
                if (clips.isNotEmpty()) snap()
                dirty = true; clips = out; layers = ArrayList(Templates.layers(j)); sounds = ArrayList(Templates.sounds(j))
                if (mf != null) { val ms = Sound("music", 0, out.sumOf { it.dur }); ms.file = mf.absolutePath; ms.vol = 0.7f; ms.fi = 0.3f; ms.fo = 1f; sounds.add(0, ms); origVol = 0.35f }
                setFormat(tw, th); onSelect(Sel.None); rebuild(0)
                toast(t("Done! Tap a text to change it.", "Siap! Tekan teks untuk ubah.")); main.postDelayed({ if (!player.isPlaying) player.play() }, 300) }
        }.start()
    }
    private fun askAdd(first: Boolean) {
        addEnd = !first && tl.time >= total() - 1_000
        AlertDialog.Builder(this).setTitle(t("Add video or photo", "Tambah video atau gambar"))
            .setItems(if (first) arrayOf(t("Video", "Video"), t("Photo", "Gambar"), "📋 " + t("Use a template", "Guna templat")) else arrayOf(t("Video", "Video"), t("Photo", "Gambar"))) { _, w ->
                when (w) { 0 -> pickVideo.launch("video/*"); 1 -> pickPhoto.launch("image/*"); else -> templateSheet() } }
            .setNegativeButton(t("Cancel", "Batal")) { _, _ -> if (clips.isEmpty()) finish() }.setOnCancelListener { if (clips.isEmpty()) finish() }.show()
    }
    private fun wait(s: String): AlertDialog = AlertDialog.Builder(this).setMessage(s).setCancelable(false).show()
    private fun copyIn(u: Uri, prefix: String, ext: String): File? { val f = File(work, prefix + System.currentTimeMillis() + ext)
        return try { contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { i.copyTo(it) } }; f } catch (e: Exception) { null } }
    private fun addVideo(u: Uri) {
        val w = wait(t("Loading video…", "Memuatkan video…"))
        Thread { val f = copyIn(u, "v_", ".mp4"); val pr = f?.let { probe(it) }
            main.post { w.dismiss()
                if (f == null || pr == null || pr.third <= 0) { toast(t("This video cannot be opened", "Video ini tidak boleh dibuka")); if (clips.isEmpty()) finish(); return@post }
                if (clips.isEmpty()) setFormat(pr.first, pr.second)
                insertAt(Clip(f, 0, pr.third, false).also { it.fileDur = pr.third }, addEnd) } }.start()
    }
    private fun loadBitmap(path: String): Bitmap? = try {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
        val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })!!
        val rot = try { androidx.exifinterface.media.ExifInterface(path).rotationDegrees } catch (e: Exception) { 0 }
        if (rot != 0) Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true) else bm
    } catch (e: Exception) { null }
    private fun makeStill(img: String, sec: Float, cb: (Clip?) -> Unit) {
        Thread {
            val bm = loadBitmap(img); val f = File(work, "p_" + System.currentTimeMillis() + ".mp4")
            val (pw, ph) = if (clips.isEmpty() && bm != null) (if (bm.height >= bm.width) 1080 to 1920 else 1920 to 1080) else W to H
            val ok = bm != null && try { StillClip.make(bm, pw, ph, (sec * 1_000_000).toLong(), f); true } catch (e: Exception) { false }
            main.post { if (!ok) cb(null) else { if (clips.isEmpty()) setFormat(pw, ph); val du = probe(f).third.takeIf { it > 0 } ?: (sec * 1_000_000).toLong()
                cb(Clip(f, 0, du, true).also { it.fileDur = du; it.img = img }) } }
        }.start()
    }
    private fun addPhoto(u: Uri) {
        val w = wait(t("Adding photo…", "Menambah gambar…"))
        Thread { val img = copyIn(u, "img_", ".jpg"); main.post {
            if (img == null) { w.dismiss(); toast(t("This photo cannot be added", "Gambar ini tidak boleh ditambah")); if (clips.isEmpty()) finish(); return@post }
            makeStill(img.absolutePath, 3f) { c -> w.dismiss(); if (c == null) { toast(t("This photo cannot be added", "Gambar ini tidak boleh ditambah")); if (clips.isEmpty()) finish() } else insertAt(c, addEnd) } } }.start()
    }
    private fun stillDur(i: Int) {
        val c = clips[i]; val opts = listOf(1f, 2f, 3f, 5f, 8f, 10f)
        AlertDialog.Builder(this).setTitle(t("Photo duration", "Tempoh gambar")).setItems(opts.map { "${it.toInt()}s" }.toTypedArray()) { _, w ->
            val dl = wait(t("Please wait…", "Sila tunggu…"))
            makeStill(c.img, opts[w]) { n -> dl.dismiss(); if (n != null) { snap(); n.trans = c.trans; n.look = c.look; n.bri = c.bri; n.con = c.con; n.sat = c.sat; n.rot = c.rot; n.mirror = c.mirror; clips[i] = n; rebuild(tl.time) } } }.show()
    }
    /* the clip plays backwards (pictures and sound); undo brings the normal one back */
    private fun reverse(i: Int) {
        val c = clips.getOrNull(i) ?: return
        if (c.outUs - c.inUs > 60_000_000) { toast(t("Split the clip first: reverse works on up to 60 s.", "Belah klip dulu: terbalik untuk maksimum 60 saat.")); return }
        val dl = wait(t("Reversing… 0%", "Menterbalikkan… 0%"))
        Thread {
            val f = File(work, "rv_" + System.currentTimeMillis() + ".mp4"); val (pw, ph, _) = probe(c.file)
            var last = -1
            val ok = try { Reverse.make(c.file, c.inUs, c.outUs, pw, ph, f) { pc -> if (pc != last) { last = pc; main.post { dl.setMessage(t("Reversing… $pc%", "Menterbalikkan… $pc%")) } } }; true } catch (e: Exception) { f.delete(); false }
            main.post {
                dl.dismiss()
                if (!ok) { toast(t("This clip cannot be reversed", "Klip ini tidak boleh diterbalikkan")); return@post }
                val du = probe(f).third.takeIf { it > 0 } ?: (c.outUs - c.inUs)
                snap()
                val n = Clip(f, 0, du, false).also { it.fileDur = du; it.speed = c.speed; it.vol = c.vol; it.look = c.look; it.bri = c.bri; it.con = c.con; it.sat = c.sat; it.rot = c.rot; it.mirror = c.mirror
                    it.trans = c.trans; it.fx = c.fx; it.fi = c.fi; it.fo = c.fo; it.fadeVid = c.fadeVid; it.fill = c.fill }
                clips[i] = n; rebuild(tl.time); toast(t("Reversed ⏪", "Diterbalikkan ⏪"))
            }
        }.start()
    }
    /* ---------- keyframes on a clip: zoom and move that change over time ---------- */
    private fun kfSheet(i: Int) {
        val c = clips.getOrNull(i) ?: return; snap()
        val sh = Ui.sheet(this, "◆ Keyframe"); val b = sh.body
        fun local() = (tl.time - start(i)).coerceIn(0, c.dur - 1)
        val tx = Ui.label(this, "", 13f, Ui.GOLD, true); b.addView(tx)
        val bars = ArrayList<SeekBar>(); val vals = ArrayList<android.widget.TextView>()
        val defs = listOf(Triple(t("Zoom", "Zum"), 0.5f, 3f), Triple(t("Left / right", "Kiri / kanan"), -0.5f, 0.5f), Triple(t("Up / down", "Atas / bawah"), -0.5f, 0.5f))
        val list = Flow(this, Ui.px(this, 6))
        fun cur(): Kf = Keys.at(Keys.parse(c.kf), local()) ?: Kf(local(), 1f, 0f, 0f)
        fun paint() {
            tx.text = t("At ", "Pada ") + String.format(Locale.US, "%.1fs", local() / 1e6) + t(" in this clip", " dalam klip ini")
            val k = cur(); val v = listOf(k.s, k.x, k.y)
            defs.forEachIndexed { j, d -> bars[j].progress = ((v[j] - d.second) / (d.third - d.second) * 100).toInt(); vals[j].text = Math.round(v[j] * 100).toString() }
            list.removeAllViews()
            Keys.parse(c.kf).forEach { k2 -> val pb = Ui.pill(this, "◆ " + String.format(Locale.US, "%.1fs", k2.t / 1e6), abs(k2.t - local()) <= 120_000) {
                val u = start(i) + k2.t; seekTo(u); tl.time = u; tl.invalidate(); showTime(u); veilAt(u); paint() }; pb.textSize = 12f; list.addView(pb) }
        }
        defs.forEachIndexed { j, d ->
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.label(this, d.first, 13f, Ui.SUB, true), LinearLayout.LayoutParams(Ui.px(this, 96), -2))
            val sb = SeekBar(this); sb.max = 100; sb.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); sb.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
            val vt = Ui.label(this, "", 13f, Ui.TEXT, true)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(x: SeekBar?, p: Int, u: Boolean) { if (!u) return
                    val k = cur(); val nv = d.second + (d.third - d.second) * p / 100f; vt.text = Math.round(nv * 100).toString()
                    c.kf = Keys.put(c.kf, Kf(local(), if (j == 0) nv else k.s, if (j == 1) nv else k.x, if (j == 2) nv else k.y)); veilAt(tl.time) }
                override fun onStartTrackingTouch(x: SeekBar?) {}; override fun onStopTrackingTouch(x: SeekBar?) { paint(); tl.invalidate() } })
            bars.add(sb); vals.add(vt); r.addView(sb, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(vt, LinearLayout.LayoutParams(Ui.px(this, 44), -2))
            b.addView(r, Ui.lp(this, 6))
        }
        // move the time inside the clip
        val nav = LinearLayout(this); nav.orientation = LinearLayout.HORIZONTAL
        fun go(d: Long) { val u = (start(i) + local() + d).coerceIn(start(i), start(i) + c.dur - 1); seekTo(u); tl.time = u; tl.invalidate(); showTime(u); veilAt(u); paint() }
        nav.addView(Ui.btn(this, "◀ 0.5s") { go(-500_000) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
        nav.addView(Ui.btn(this, "◆ " + t("Add", "Tambah"), true) { val k = cur(); c.kf = Keys.put(c.kf, Kf(local(), k.s, k.x, k.y)); paint() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
        nav.addView(Ui.btn(this, "0.5s ▶") { go(500_000) }, LinearLayout.LayoutParams(0, -2, 1f))
        b.addView(nav, Ui.lp(this, 10)); b.addView(list, Ui.lp(this, 8))
        val dr = LinearLayout(this); dr.orientation = LinearLayout.HORIZONTAL
        dr.addView(Ui.btn(this, "✕ " + t("Remove this one", "Buang yang ini")) { c.kf = Keys.drop(c.kf, local()); veilAt(tl.time); paint() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
        dr.addView(Ui.btn(this, "🗑 " + t("Remove all", "Buang semua")) { c.kf = ""; veilAt(tl.time); paint() }, LinearLayout.LayoutParams(0, -2, 1f))
        b.addView(dr, Ui.lp(this, 8))
        b.addView(Ui.hint(this, t("Add 2 or more points at different times; the picture moves smoothly between them.", "Tambah 2 titik atau lebih pada masa berbeza; gambar bergerak lancar di antaranya.")))
        sh.dialog.setOnDismissListener { refreshTools(); tl.invalidate(); dirty = true }
        paint(); sh.dialog.show()
    }
    /* keyframes on text, sticker, photo or video on top: after the first point, moving or sizing it makes a new point at that time */
    private fun layerKf(l: Layer) {
        val t0 = (tl.time - l.s).coerceIn(0, l.e - l.s)
        if (l.kf.isBlank()) { snap(); l.kf = Keys.put("", Kf(t0, l.scale, l.x - 0.5f, l.y - 0.5f)); refreshTools()
            toast(t("Point added. Move the time, then drag or pinch it: a new point is made.", "Titik ditambah. Alih masa, kemudian seret atau cubit: titik baru dibuat sendiri.")); return }
        AlertDialog.Builder(this).setTitle("◆ Keyframe").setItems(arrayOf("◆ " + t("Add point here", "Tambah titik di sini"), "✕ " + t("Remove point here", "Buang titik di sini"), "🗑 " + t("Remove all", "Buang semua"))) { _, w ->
            snap(); val e = l.at(tl.time)
            when (w) { 0 -> l.kf = Keys.put(l.kf, Kf(t0, e.scale, e.x - 0.5f, e.y - 0.5f)); 1 -> l.kf = Keys.drop(l.kf, t0); else -> { l.x = e.x; l.y = e.y; l.scale = e.scale; l.kf = "" } }
            paintOverlay(tl.time, true); pipSync(tl.time, true); refreshTools() }.show()
    }
    /* steady a shaky video: look at it once, then it is held still (a little zoom hides the edges) */
    private fun stabilise(i: Int) {
        val c = clips.getOrNull(i) ?: return
        if (c.stab) { snap(); c.stab = false; rebuild(tl.time); toast(t("Steady: off", "Stabil: tutup")); return }
        if (Stab.has(this, c.file)) { snap(); c.stab = true; rebuild(tl.time); toast(t("Steady: on", "Stabil: hidup")); return }
        val dl = wait(t("Checking the shake… 0%", "Menyemak goyangan… 0%")); val (pw, ph, _) = probe(c.file)
        Thread { var last = -1
            val ok = try { Stab.analyse(this, c.file, pw, ph) { pc -> if (pc != last) { last = pc; main.post { dl.setMessage(t("Checking the shake… $pc%", "Menyemak goyangan… $pc%")) } } } } catch (e: Exception) { false }
            main.post { dl.dismiss(); if (!ok) { toast(t("Could not steady this video", "Video ini tidak dapat distabilkan")); return@post }
                snap(); c.stab = true; rebuild(tl.time); toast(t("Steady: on 🫨", "Stabil: hidup 🫨")) } }.start()
    }
    /* green screen on a video on top */
    private fun keySheet(l: Layer) {
        snap(); val sh = Ui.sheet(this, "🟩 " + t("Green screen", "Skrin hijau")); val b = sh.body
        val opts = listOf(0 to t("Off", "Tiada"), 0xFF00B140.toInt() to t("Green", "Hijau"), 0xFF0047BB.toInt() to t("Blue", "Biru"), 1 to t("Corner colour", "Warna penjuru"))
        val r = Flow(this, Ui.px(this, 6)); val pb = ArrayList<Button>()
        fun paint() { pb.forEachIndexed { j, q -> val k = opts[j].first; Ui.setPill(this, q, if (k == 1) l.key != 0 && opts.none { it.first == l.key } else l.key == k) } }
        opts.forEach { (k, n) -> val q = Ui.pill(this, n, false) {
                l.key = if (k == 1) { val tvp = pipTvs.firstOrNull { it.visibility == View.VISIBLE }; val bm = try { tvp?.getBitmap(32, 32) } catch (e: Exception) { null }
                    bm?.getPixel(1, 1)?.or(0xFF000000.toInt()) ?: 0xFF00B140.toInt() } else k
                paint() }
            pb.add(q); r.addView(q) }
        b.addView(r)
        slider(b, t("Strength", "Kekuatan"), l.keyTol, 0.04f, 0.3f) { v -> l.keyTol = v }
        b.addView(Ui.hint(this, t("Film the person in front of one plain colour. The colour turns see-through in the saved video.", "Rakam orang di depan satu warna kosong. Warna itu jadi lutsinar dalam video yang disimpan.")))
        sh.dialog.setOnDismissListener { refreshTools() }
        paint(); sh.dialog.show()
    }
    /* record your own voice while the video plays */
    private var rec: android.media.MediaRecorder? = null
    private fun recSheet() {
        if (clips.isEmpty()) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 77); toast(t("Allow the microphone, then tap again", "Benarkan mikrofon, kemudian tekan semula")); return }
        val sh = Ui.sheet(this, "🎙 " + t("Record voice", "Rakam suara")); val b = sh.body
        var mute = true; var startAt = 0L; var file: File? = null
        b.addView(Ui.check(this, t("Mute the video while recording", "Senyapkan video semasa rakam"), true) { v -> mute = v })
        val st = Ui.label(this, t("Recording starts from ", "Rakaman mula dari ") + fmt(tl.time), 13f, Ui.SUB, true); b.addView(st, Ui.lp(this, 8))
        lateinit var go: Button
        fun stop(keep: Boolean) {
            val r = rec ?: return; rec = null
            try { r.stop() } catch (e: Exception) { file?.delete(); file = null }; r.release()
            if (player.isPlaying) player.pause(); clipLook(); mplayer.volume = 1f
            val f = file ?: return; if (!keep) { f.delete(); return }
            val w = wait(t("Adding your voice…", "Menambah suara anda…"))
            Thread { val a = Snd.decode(f.absolutePath); audioCache[f.absolutePath] = a
                main.post { w.dismiss(); if (a == null || a.isEmpty()) { toast(t("Nothing was recorded", "Tiada yang dirakam")); return@post }
                    snap(); val s = Sound("rec", startAt, minOf(total(), startAt + Snd.durUs(a))); s.file = f.absolutePath; s.vol = 1f
                    sounds.add(s); onSelect(Sel.S(sounds.size - 1)); rebuild(tl.time); toast(t("Voice added 🎙", "Suara ditambah 🎙")) } }.start()
        }
        go = Ui.btn(this, "⏺  " + t("Start recording", "Mula rakam"), true) {
            if (rec != null) { go.text = "⏺  " + t("Start recording", "Mula rakam"); stop(true); sh.dialog.dismiss(); return@btn }
            val f = File(work, "rec_" + System.currentTimeMillis() + ".m4a"); file = f; startAt = tl.time
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else @Suppress("DEPRECATION") android.media.MediaRecorder()
            try { r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC); r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48000); r.setAudioEncodingBitRate(128000); r.setAudioChannels(1); r.setOutputFile(f.absolutePath); r.prepare(); r.start() }
            catch (e: Exception) { r.release(); toast(t("The microphone is busy", "Mikrofon sedang digunakan")); return@btn }
            rec = r; if (mute) { player.volume = 0f; mplayer.volume = 0f }
            if (!player.isPlaying) player.play()
            go.text = "⏹  " + t("Stop", "Berhenti"); st.text = "🔴 " + t("Recording… tap Stop when done", "Merakam… tekan Berhenti bila siap")
        }
        b.addView(go, LinearLayout.LayoutParams(-1, Ui.px(this, 64)).apply { topMargin = Ui.px(this@EditorActivity, 10) })
        b.addView(Ui.hint(this, t("Tip: use earphones with a mic for the clearest voice.", "Tip: guna earphone bermikrofon untuk suara paling jelas.")))
        sh.dialog.setOnDismissListener { stop(true) }
        sh.dialog.show()
    }
    /* cut the clips so each change falls on a beat of the music */
    private fun beatSheet(s: Sound) {
        val w = wait(t("Listening to the beat…", "Mendengar rentak…"))
        Thread { val a = audioCache.getOrPut(s.file) { Snd.decode(s.file) }; val r = a?.let { Beats.find(it) }
            main.post { w.dismiss()
                if (r == null) { toast(t("No steady beat found", "Rentak tidak dijumpai")); return@post }
                val sh = Ui.sheet(this, "🥁 " + t("Cut to beat", "Ikut rentak")); val b = sh.body
                b.addView(Ui.label(this, t("Tempo: ", "Tempo: ") + Math.round(r.bpm) + " BPM", 15f, Ui.GOLD, true))
                b.addView(Ui.label(this, t("Change clip every", "Tukar klip setiap"), 13f, Ui.SUB, true), Ui.lp(this, 12))
                val f = Flow(this, Ui.px(this, 6))
                listOf(1, 2, 4, 8).forEach { n -> f.addView(Ui.pill(this, "$n " + t(if (n == 1) "beat" else "beats", "rentak"), n == 2) { sh.dialog.dismiss(); cutToBeat(s, r, n) }) }
                b.addView(f, Ui.lp(this, 6))
                b.addView(Ui.hint(this, t("Each clip is cut so the change lands on the beat. Undo brings it back.", "Setiap klip dipotong supaya pertukaran jatuh tepat pada rentak. Undo boleh kembalikan.")))
                sh.dialog.show() } }.start()
    }
    private fun cutToBeat(s: Sound, r: Beats.Res, n: Int) {
        snap(); val T = r.period * 1e6; val base = s.s + r.phase * 1e6
        fun beat(x: Double) = base + Math.round((x - base) / T) * T   // the beat nearest to time x
        var cur = 0.0; var changed = 0
        clips.forEach { c ->
            val maxDur = (if (c.still) c.fileDur else minOf(c.fileDur, Long.MAX_VALUE / 4) - c.inUs) / c.speed.toDouble()
            var e = beat(cur + n * T)
            if (e - cur > maxDur) { e = base + Math.floor((cur + maxDur - base) / T) * T }
            if (e - cur >= T * 0.5) { c.outUs = c.inUs + ((e - cur) * c.speed).toLong(); changed++ }
            cur += c.dur
        }
        rebuild(0); toast(t("$changed clips cut to the beat 🥁", "$changed klip dipotong ikut rentak 🥁"))
    }
    private fun freeze() {
        if (clips.isEmpty()) return
        val (i, l) = locate(tl.time); val c = clips[i]; val at = c.inUs + (l * c.speed).toLong()
        val dl = wait(t("Please wait…", "Sila tunggu…"))
        Thread {
            val bm = try { MediaMetadataRetriever().run { setDataSource(c.file.absolutePath); val b = getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST); release(); b } } catch (e: Exception) { null }
            val img = File(work, "fr_" + System.currentTimeMillis() + ".jpg"); bm?.let { b -> img.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
            main.post { if (bm == null) { dl.dismiss(); toast(t("Could not take the picture", "Gagal ambil gambar")); return@post }
                makeStill(img.absolutePath, 2f) { n -> dl.dismiss(); if (n != null) { n.look = c.look; n.bri = c.bri; n.con = c.con; n.sat = c.sat; insertAt(n, false) } } }
        }.start()
    }

    /* ---------------- sheets ---------------- */
    private fun pills(body: LinearLayout, title: String, opts: List<Pair<String, String>>, cur: () -> String, set: (String) -> Unit) {
        if (title.isNotEmpty()) body.addView(Ui.label(this, title, 13f, Ui.SUB, true), Ui.lp(this, 12))
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false; val r = LinearLayout(this); hs.addView(r); val bs = ArrayList<Button>()
        opts.forEach { (id, l) -> val b = Ui.pill(this, l, cur() == id) { set(id); bs.forEachIndexed { j, q -> Ui.setPill(this, q, opts[j].first == cur()) } }; bs.add(b); r.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8 }) }
        body.addView(hs, Ui.lp(this, 4))
    }
    private fun slider(body: LinearLayout, label: String, v: Float, min: Float, max: Float, set: (Float) -> Unit) {
        val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
        val lb = Ui.label(this, label, 13f, Ui.SUB, true); r.addView(lb, LinearLayout.LayoutParams(Ui.px(this, 96), -2))
        val vt = Ui.label(this, "", 13f, Ui.TEXT, true)
        val sb = SeekBar(this); sb.max = 100; sb.progress = ((v - min) / (max - min) * 100).toInt(); sb.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); sb.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
        fun show(x: Float) { vt.text = String.format(Locale.US, "%d", Math.round(x * 100)) }
        show(v)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (!u) return; val x = min + (max - min) * p / 100f; show(x); set(x) }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
        r.addView(sb, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(vt, LinearLayout.LayoutParams(Ui.px(this, 40), -2)); body.addView(r, Ui.lp(this, 8))
    }
    private fun speedSheet(i: Int) {
        val c = clips[i]; val s = Ui.sheet(this, t("Speed", "Laju"))
        pills(s.body, "", listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).map { it.toString() to "${it}×" }, { c.speed.toString() }) { v -> snap(); c.speed = v.toFloat(); rebuild(tl.time) }
        s.body.addView(Ui.hint(this, t("The sound changes speed too.", "Bunyi juga ikut laju.")))
        s.dialog.window?.setLayout(-1, Ui.px(this, 260)); s.dialog.show()
    }
    private fun volSheet(title: String, v: Float, set: (Float) -> Unit) {
        val s = Ui.sheet(this, title); snap()
        slider(s.body, t("Volume", "Volum"), v, 0f, 2f) { x -> set(if (x < 0.03f) 0f else x) }
        s.body.addView(Ui.btn(this, "🔇 " + t("Mute", "Senyap")) { set(0f); s.dialog.dismiss() }, Ui.lp(this, 10))
        s.dialog.setOnDismissListener { refreshTools(); tl.invalidate(); mixLater() }
        s.dialog.window?.setLayout(-1, Ui.px(this, 300)); s.dialog.show()
    }
    private fun filterSheet(i: Int) {
        val c = clips[i]; val s = Ui.sheet(this, t("Filters", "Filter")); snap()
        pills(s.body, t("Clip ", "Klip ") + (i + 1), Looks.ALL.map { it.id to (if (Ui.ms) it.ms else it.en) }, { c.look }) { v -> c.look = v; clipLook(); tl.invalidate() }
        s.body.addView(Ui.btn(this, t("Use for all clips", "Guna untuk semua klip")) { clips.forEach { it.look = c.look }; clipLook(); tl.invalidate(); toast("✓") }, Ui.lp(this, 12))
        s.dialog.window?.setLayout(-1, Ui.px(this, 300)); s.dialog.show()
    }
    /* fade in / out at the start and end, like a hill: start only, end only, or both */
    private fun fadeSheet(c: Clip?, snd: Sound?) {
        snap()
        var fi = c?.fi ?: (if (snd!!.fade) snd.fi else 0f); var fo = c?.fo ?: (if (snd!!.fade) snd.fo else 0f)
        val s = Ui.sheet(this, t("Fade in / out", "Pudar masuk / keluar"))
        val pic = android.widget.ImageView(this); s.body.addView(pic, LinearLayout.LayoutParams(-1, Ui.px(this, 70)))
        fun hill() { val w = 600; val h = 120; val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val tot = 6f; val a = (fi / tot * w).coerceAtMost(w / 2f); val z = (fo / tot * w).coerceAtMost(w / 2f)
            val path = android.graphics.Path(); path.moveTo(0f, h.toFloat()); path.lineTo(a, 10f); path.lineTo(w - z, 10f); path.lineTo(w.toFloat(), h.toFloat()); path.close()
            p.color = 0x55D9B44A; cv.drawPath(path, p); p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Ui.GOLD; cv.drawPath(path, p); pic.setImageBitmap(b) }
        val rows = LinearLayout(this); rows.orientation = LinearLayout.VERTICAL
        fun apply() { if (c != null) { c.fi = fi; c.fo = fo; veilAt(tl.time) } else snd?.let { it.fi = fi; it.fo = fo; it.fade = fi > 0 || fo > 0; mixLater() }; hill(); tl.invalidate() }
        fun secRow(label: String, get: () -> Float, set: (Float) -> Unit): View {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.label(this, label, 13f, Ui.SUB, true), LinearLayout.LayoutParams(Ui.px(this, 96), -2))
            val vt = Ui.label(this, String.format(Locale.US, "%.1fs", get()), 13f, Ui.TEXT, true)
            val sb = SeekBar(this); sb.max = 28; sb.progress = ((get() - 0.2f) * 10).toInt().coerceIn(0, 28); sb.progressTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD); sb.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.GOLD)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(x: SeekBar?, p: Int, u: Boolean) { if (!u) return; val v = 0.2f + p / 10f; vt.text = String.format(Locale.US, "%.1fs", v); set(v); apply() }
                override fun onStartTrackingTouch(x: SeekBar?) {}; override fun onStopTrackingTouch(x: SeekBar?) {} })
            r.addView(sb, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(vt, LinearLayout.LayoutParams(Ui.px(this, 48), -2)); return r }
        val inRow = secRow(t("Fade in", "Pudar masuk"), { if (fi > 0) fi else 1f }) { fi = it }
        val outRow = secRow(t("Fade out", "Pudar keluar"), { if (fo > 0) fo else 1f }) { fo = it }
        fun mode() = when { fi > 0 && fo > 0 -> "both"; fi > 0 -> "in"; fo > 0 -> "out"; else -> "none" }
        fun rowsVis() { inRow.visibility = if (fi > 0) View.VISIBLE else View.GONE; outRow.visibility = if (fo > 0) View.VISIBLE else View.GONE }
        pills(s.body, "", listOf("none" to t("None", "Tiada"), "in" to t("Start only", "Awal sahaja"), "out" to t("End only", "Akhir sahaja"), "both" to t("Start & end", "Awal & akhir")), { mode() }) { m ->
            val dfi = if (fi > 0) fi else 1f; val dfo = if (fo > 0) fo else 1f
            when (m) { "none" -> { fi = 0f; fo = 0f }; "in" -> { fi = dfi; fo = 0f }; "out" -> { fi = 0f; fo = dfo }; else -> { fi = dfi; fo = dfo } }
            rowsVis(); apply() }
        rows.addView(inRow, Ui.lp(this, 10)); rows.addView(outRow, Ui.lp(this, 6)); s.body.addView(rows); rowsVis()
        if (c != null) {
            s.body.addView(Ui.check(this, t("Picture fades too (to black)", "Gambar juga pudar (ke hitam)"), c.fadeVid) { v -> c.fadeVid = v; veilAt(tl.time) }, Ui.lp(this, 10))
            s.body.addView(Ui.hint(this, t("The sound always fades.", "Bunyi sentiasa pudar.")))
            s.body.addView(Ui.btn(this, t("Use for all clips", "Guna untuk semua klip")) { clips.forEach { it.fi = c.fi; it.fo = c.fo; it.fadeVid = c.fadeVid }; tl.invalidate(); toast("✓") }, Ui.lp(this, 12))
        }
        s.dialog.setOnDismissListener { refreshTools(); tl.invalidate() }
        hill(); s.dialog.show()
    }
    private fun fxSheet(i: Int) {
        val c = clips[i]; val s = Ui.sheet(this, t("Effects", "Efek")); snap()
        pills(s.body, (if (c.still) t("Photo ", "Gambar ") else t("Clip ", "Klip ")) + (i + 1), Fx.EFFECTS.map { it to Fx.fxName(it) }, { c.fx }) { v -> c.fx = v; tl.invalidate(); rebuild(start(i)); main.postDelayed({ if (!player.isPlaying) player.play() }, 150) }
        s.body.addView(Ui.btn(this, t("Use for all photos", "Guna untuk semua gambar")) { clips.forEach { if (it.still) it.fx = c.fx }; tl.invalidate(); toast("✓") }, Ui.lp(this, 12))
        s.body.addView(Ui.btn(this, t("Use for all clips", "Guna untuk semua klip")) { clips.forEach { it.fx = c.fx }; tl.invalidate(); toast("✓") }, Ui.lp(this, 8))
        s.body.addView(Ui.hint(this, t("Switch effects: tap the small square between two clips.", "Kesan tukar: tekan petak kecil antara dua klip.")))
        s.dialog.window?.setLayout(-1, Ui.px(this, 360)); s.dialog.show()
    }
    private fun adjustSheet(i: Int) {
        val c = clips[i]; val s = Ui.sheet(this, t("Adjust", "Laras")); snap()
        slider(s.body, t("Brightness", "Cerah"), c.bri, -0.5f, 0.5f) { c.bri = it; clipLook() }
        slider(s.body, t("Contrast", "Kontras"), c.con, -0.5f, 0.5f) { c.con = it; clipLook() }
        slider(s.body, t("Colour", "Warna"), c.sat, -1f, 1f) { c.sat = it; clipLook() }
        s.body.addView(Ui.btn(this, t("Use for all clips", "Guna untuk semua klip")) { clips.forEach { it.bri = c.bri; it.con = c.con; it.sat = c.sat }; toast("✓") }, Ui.lp(this, 12))
        s.body.addView(Ui.btn(this, t("Reset", "Set semula")) { c.bri = 0f; c.con = 0f; c.sat = 0f; clipLook(); s.dialog.dismiss() }, Ui.lp(this, 8))
        s.dialog.setOnDismissListener { tl.invalidate(); refreshTools() }
        s.dialog.window?.setLayout(-1, Ui.px(this, 400)); s.dialog.show()
    }
    private fun formatSheet() {
        val s = Ui.sheet(this, "Format")
        pills(s.body, "", listOf("9:16" to "9:16", "16:9" to "16:9", "1:1" to "1:1", "4:5" to "4:5"), { fmtBtn.text.toString().substringBefore(" ") }) { v ->
            snap(); val (a, b) = when (v) { "16:9" -> 1920 to 1080; "1:1" -> 1080 to 1080; "4:5" -> 1080 to 1350; else -> 1080 to 1920 }; setFormat(a, b); rebuild(tl.time) }
        s.body.addView(Ui.hint(this, t("Clips fit inside with black bars where needed.", "Klip dimuatkan dengan jalur hitam jika perlu.")))
        s.dialog.window?.setLayout(-1, Ui.px(this, 260)); s.dialog.show()
    }
    private fun newText() {
        if (clips.isEmpty()) return
        val l = Layer("text", tl.time, minOf(total(), tl.time + 3_000_000)); if (l.e - l.s < 400_000) { l.s = maxOf(0, total() - 3_000_000); l.e = total() }
        textSheet(l, true)
    }
    private fun textSheet(l: Layer, isNew: Boolean = false) {
        val x = l.copy()
        val s = Ui.sheet(this, if (isNew) t("Add text", "Tambah teks") else t("Text", "Teks"))
        val inp = Ui.input(this, t("Type here…", "Taip di sini…"), x.text); inp.minLines = 2; inp.gravity = Gravity.TOP; s.body.addView(inp)
        val prev = android.widget.ImageView(this); prev.setBackgroundColor(0xFF2A2A2A.toInt()); s.body.addView(prev, LinearLayout.LayoutParams(-1, Ui.px(this, 110)).apply { topMargin = Ui.px(this@EditorActivity, 8) })
        fun redraw() { x.text = inp.text.toString().ifEmpty { t("Your text", "Teks anda") }; val y = x.copy().also { it.y = 0.5f; it.x = 0.5f; it.anim = "none"; it.s = 0; it.e = 1_000_000; it.scale = 1.4f }
            prev.setImageBitmap(art.render(listOf(y), 500_000, 720, 220)); prev.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        inp.addTextChangedListener(object : android.text.TextWatcher { override fun afterTextChanged(e: android.text.Editable?) { redraw() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}; override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {} })
        val sn = mapOf("boxword" to t("Box", "Kotak"), "outline" to t("Outline", "Garis"), "plain" to t("Plain", "Biasa"), "shadow" to t("Shadow", "Bayang"), "glow" to t("Glow", "Bercahaya"), "neon" to "Neon",
            "gradient" to "Gradient", "d3" to "3D", "bubble" to t("Bubble", "Gelembung"), "darkbox" to t("Dark box", "Kotak gelap"), "highlight" to t("Highlight", "Sorot"), "retro" to "Retro")
        pills(s.body, t("Style", "Gaya"), TextArt.STYLES.map { it to (sn[it] ?: it) }, { x.style }) { x.style = it; redraw() }
        pills(s.body, t("Font", "Fon"), Overlay.FONTS.map { (k, f) -> k to (if (Ui.ms) f.ms else f.en).substringBefore(" —") }, { x.font }) { x.font = it; redraw() }
        // colours as round swatches
        s.body.addView(Ui.label(this, t("Colour", "Warna"), 13f, Ui.SUB, true), Ui.lp(this, 12))
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false; val cr = LinearLayout(this); hs.addView(cr); val sw = ArrayList<View>()
        TextArt.COLS.forEach { (k, v) -> val b = View(this); fun paint() { b.background = Ui.round(v, Ui.dp(this, 99f), if (x.col == k) Ui.GOLD else 0xFF444444.toInt(), Ui.px(this, if (x.col == k) 3 else 1)) }
            paint()
            sw.add(b); cr.addView(b, LinearLayout.LayoutParams(Ui.px(this, 36), Ui.px(this, 36)).apply { rightMargin = Ui.px(this@EditorActivity, 8) }) }
        // clicking a swatch must not call the others: replace listeners after building
        sw.forEachIndexed { j, b -> val k = TextArt.COLS.keys.elementAt(j); b.setOnClickListener { x.col = k; redraw()
            sw.forEachIndexed { jj, q -> val kk = TextArt.COLS.keys.elementAt(jj); q.background = Ui.round(TextArt.COLS[kk]!!, Ui.dp(this, 99f), if (x.col == kk) Ui.GOLD else 0xFF444444.toInt(), Ui.px(this, if (x.col == kk) 3 else 1)) } } }
        s.body.addView(hs, Ui.lp(this, 4))
        pills(s.body, t("Animation", "Animasi"), animNames(), { x.anim }) { x.anim = it }
        if (isNew) pills(s.body, t("How long", "Berapa lama"), listOf("2" to "2s", "3" to "3s", "5" to "5s", "all" to t("To the end", "Hingga akhir")), { "3" }) { x.e = if (it == "all") total() else minOf(total(), x.s + it.toLong() * 1_000_000) }
        s.body.addView(Ui.btn(this, t("Done", "Siap"), true) {
            val v = inp.text.toString().trim(); if (v.isEmpty()) { toast(t("Type some text", "Taip teks dahulu")); return@btn }
            x.text = v; snap()
            if (isNew) { layers.add(x); onSelect(Sel.L(layers.size - 1)) } else { val i = layers.indexOf(l); if (i >= 0) layers[i] = x }
            s.dialog.dismiss(); rebuild(tl.time)
        }, Ui.lp(this, 16))
        redraw(); s.dialog.show()
    }
    private fun animNames() = listOf("none" to t("None", "Tiada"), "fade" to t("Fade", "Pudar"), "pop" to "Pop", "slide" to t("Slide up", "Naik"), "type" to t("Typing", "Taip"), "shake" to t("Shake", "Gegar"))
    private fun animSheet(l: Layer) {
        val s = Ui.sheet(this, t("Animation", "Animasi")); snap()
        pills(s.body, "", animNames(), { l.anim }) { l.anim = it; rebuild(l.s) }
        s.dialog.window?.setLayout(-1, Ui.px(this, 240)); s.dialog.show()
    }
    private fun stickerSheet() {
        if (clips.isEmpty()) return
        val s = Ui.sheet(this, t("Stickers", "Stiker"))
        val g = android.widget.GridLayout(this); g.columnCount = 6
        TextArt.STICKERS.forEachIndexed { i, e -> val b = Ui.center(Ui.label(this, e, 28f)); b.setPadding(0, Ui.px(this, 8), 0, Ui.px(this, 8))
            b.setOnClickListener { snap(); val l = Layer("sticker", tl.time, minOf(total(), tl.time + 3_000_000)); l.text = e; l.x = 0.5f; l.y = 0.4f; l.anim = "pop"
                if (l.e - l.s < 400_000) { l.s = maxOf(0, total() - 3_000_000); l.e = total() }
                layers.add(l); s.dialog.dismiss(); onSelect(Sel.L(layers.size - 1)); rebuild(tl.time) }
            val p = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / 6), android.widget.GridLayout.spec(i % 6, 1f)); p.width = 0; g.addView(b, p) }
        s.body.addView(g); s.body.addView(Ui.hint(this, t("Drag on the picture to move; use two fingers to resize.", "Seret pada gambar untuk alih; dua jari untuk ubah saiz.")))
        s.dialog.show()
    }
    private fun addOverlayPhoto(u: Uri) {
        if (clips.isEmpty()) return
        Thread { val f = copyIn(u, "ov_", ".png"); val bm = f?.let { loadBitmap(it.absolutePath) }
            if (f != null && bm != null) { val k = minOf(1f, 900f / maxOf(bm.width, bm.height)); val sm = Bitmap.createScaledBitmap(bm, (bm.width * k).toInt().coerceAtLeast(1), (bm.height * k).toInt().coerceAtLeast(1), true)
                f.outputStream().use { sm.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            main.post { if (f == null || bm == null) { toast(t("This photo cannot be added", "Gambar ini tidak boleh ditambah")); return@post }
                snap(); val l = Layer("photo", tl.time, minOf(total(), tl.time + 4_000_000)); l.file = f.absolutePath; l.x = 0.5f; l.y = 0.4f; l.anim = "pop"
                if (l.e - l.s < 400_000) { l.s = maxOf(0, total() - 4_000_000); l.e = total() }
                layers.add(l); onSelect(Sel.L(layers.size - 1)); rebuild(tl.time) } }.start()
    }
    private fun brand() {
        if (clips.isEmpty()) return
        val i = layers.indexOfFirst { it.kind == "brand" }
        if (i >= 0) { snap(); layers.removeAt(i); onSelect(Sel.None); rebuild(tl.time); toast(t("Brand removed", "Jenama dibuang")); return }
        val b = Brand(this)
        if (b.title.isEmpty() && b.price.isEmpty() && b.phone.isEmpty() && !b.logoFile.exists()) { toast(t("Set your brand first: Director ▸ Settings", "Tetapkan jenama dahulu: Pengarah ▸ Tetapan")); return }
        snap(); layers.add(0, Layer("brand", 0, total()).also { it.anim = "none" }); rebuild(tl.time); toast(t("Brand added", "Jenama ditambah"))
    }

    /* ---------------- sound ---------------- */
    private fun addMusic(u: Uri) {
        if (clips.isEmpty()) return
        val name = try { contentResolver.query(u, null, null, null, null)?.use { c -> val k = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && k >= 0) c.getString(k) else null } } catch (e: Exception) { null } ?: "music"
        val w = wait(t("Loading music…", "Memuatkan muzik…"))
        Thread { val f = copyIn(u, "m_", "_" + name.replace(Regex("[^\\w.]+"), "_")); val a = f?.let { Snd.decode(it.absolutePath) }; if (f != null) audioCache[f.absolutePath] = a
            main.post { w.dismiss(); if (f == null || a == null || a.isEmpty()) { toast(t("This sound cannot be used", "Bunyi ini tidak boleh digunakan")); return@post }
                snap(); val s = Sound("music", tl.time.coerceAtMost(maxOf(0, total() - 1_000_000)), total()); s.file = f.absolutePath; s.vol = 0.6f
                sounds.add(s); onSelect(Sel.S(sounds.size - 1)); rebuild(tl.time); toast(t("Tip: lower the original sound if needed", "Tip: perlahankan bunyi asal jika perlu")) } }.start()
    }
    /* music: our own built-in songs, or a song from the phone */
    private fun musicSheet() {
        if (clips.isEmpty()) return
        val s = Ui.sheet(this, t("Music", "Muzik")); var mp: android.media.MediaPlayer? = null
        fun stop() { try { mp?.release() } catch (e: Exception) {}; mp = null }
        s.dialog.setOnDismissListener { stop() }
        s.body.addView(Ui.btn(this, "📁  " + t("From my phone", "Dari telefon saya"), true) { s.dialog.dismiss(); pickMusic.launch("audio/*") })
        s.body.addView(Ui.label(this, t("Built-in music (free to use)", "Muzik terbina (bebas guna)"), 13f, Ui.SUB, true), Ui.lp(this, 14))
        MusicLib.ALL.forEach { song -> val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
            val name = t(song.en, song.ms) + "  ·  ${song.bpm} BPM"
            r.addView(Ui.btn(this, "▶  $name") {
                stop(); val w = wait(t("Preparing…", "Menyediakan…"))
                Thread { val f = try { MusicLib.file(this, song.id) } catch (e: Exception) { null }
                    main.post { w.dismiss(); if (f == null || !s.dialog.isShowing) return@post
                        try { mp = android.media.MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() } } catch (e: Exception) {} } }.start()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 })
            r.addView(Ui.btn(this, "+ " + t("Add", "Tambah"), true) {
                stop(); val w = wait(t("Preparing…", "Menyediakan…"))
                Thread { val f = try { MusicLib.file(this, song.id) } catch (e: Exception) { null }; val a = f?.let { Snd.readWav(it) }; if (f != null) audioCache[f.absolutePath] = a
                    main.post { w.dismiss(); s.dialog.dismiss(); if (f == null || a == null) { toast(t("Could not make the music", "Gagal sediakan muzik")); return@post }
                        snap(); val ss = Sound("music", 0, total()); ss.file = f.absolutePath; ss.vol = 0.5f
                        sounds.add(ss); onSelect(Sel.S(sounds.size - 1)); rebuild(tl.time); toast(t("Music added for the whole video", "Muzik ditambah untuk seluruh video")) } }.start()
            })
            s.body.addView(r, Ui.lp(this, 8)) }
        s.body.addView(Ui.hint(this, t("Made by the app, so it is safe to post anywhere.", "Dibuat oleh app sendiri, jadi selamat untuk dimuat naik di mana-mana.")))
        s.dialog.show()
    }
    private fun sfxSheet() {
        if (clips.isEmpty()) return
        val s = Ui.sheet(this, t("Sound FX", "Kesan bunyi"))
        val names = mapOf("whoosh" to "Whoosh", "ding" to "Ding", "pop" to "Pop", "clap" to t("Clap", "Tepuk"), "cash" to t("Cash", "Duit"), "drum" to t("Drum", "Dram"))
        Snd.SFX.forEach { k -> val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL
            r.addView(Ui.btn(this, "▶  " + names[k]) { playSfx(k) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 8 })
            r.addView(Ui.btn(this, "+ " + t("Add", "Tambah"), true) { snap(); val a = Snd.sfx(k); val ss = Sound("sfx", tl.time, minOf(total(), tl.time + maxOf(300_000L, Snd.durUs(a)))); ss.sfx = k; sounds.add(ss); s.dialog.dismiss(); onSelect(Sel.S(sounds.size - 1)); rebuild(tl.time) })
            s.body.addView(r, Ui.lp(this, 8)) }
        s.dialog.show()
    }
    private fun playSfx(k: String) {
        Thread { try { val a = Snd.sfx(k); val sh = ShortArray(a.size) { (a[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
            val at = android.media.AudioTrack.Builder().setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(Snd.RATE).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(sh.size * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
            at.write(sh, 0, sh.size); at.play(); Thread.sleep(Snd.durUs(a) / 1000 + 100); at.release() } catch (e: Exception) {} }.start()
    }
    private fun voiceSheet(edit: Sound?) {
        if (clips.isEmpty()) return
        val s = Ui.sheet(this, t("AI voice", "Suara AI"))
        val inp = Ui.input(this, t("What should the voice say?", "Apa yang suara perlu sebut?"), edit?.text ?: ""); inp.minLines = 3; inp.gravity = Gravity.TOP; s.body.addView(inp)
        var pitch = "n"; var rate = "n"
        pills(s.body, t("Voice", "Suara"), listOf("d" to t("Deep", "Garau"), "n" to t("Normal", "Biasa"), "h" to t("High", "Tinggi")), { pitch }) { pitch = it }
        pills(s.body, t("Speed", "Laju"), listOf("s" to t("Slow", "Perlahan"), "n" to t("Normal", "Biasa"), "f" to t("Fast", "Laju")), { rate }) { rate = it }
        s.body.addView(Ui.btn(this, if (edit == null) t("Add voice here", "Tambah suara di sini") else t("Update voice", "Kemas kini suara"), true) {
            val txt = inp.text.toString().trim(); if (txt.isEmpty()) return@btn
            s.dialog.dismiss(); speak(txt, when (pitch) { "d" -> 0.8f; "h" -> 1.25f; else -> 1f }, when (rate) { "s" -> 0.8f; "f" -> 1.25f; else -> 1f }, edit)
        }, Ui.lp(this, 14))
        s.body.addView(Ui.hint(this, t("Uses the phone's own voice (Malay if installed).", "Guna suara telefon sendiri (Bahasa Melayu jika ada).")))
        s.dialog.show()
    }
    private fun speak(txt: String, pitch: Float, rate: Float, edit: Sound?) {
        val w = wait(t("Making the voice…", "Membuat suara…"))
        fun go() {
            val e = tts ?: return
            val loc = if (Ui.ms) Locale("ms", "MY") else Locale.US
            if (e.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) e.language = loc
            e.setPitch(pitch); e.setSpeechRate(rate)
            val f = File(work, "tts_" + System.currentTimeMillis() + ".wav"); val id = f.name
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                @Deprecated("old") override fun onError(u: String?) { main.post { w.dismiss(); toast(t("Voice failed", "Suara gagal")) } }
                override fun onDone(u: String?) {
                    val a = Snd.readWav(f); audioCache[f.absolutePath] = a
                    main.post { w.dismiss(); if (a == null || a.isEmpty()) { toast(t("Voice failed", "Suara gagal")); return@post }
                        snap(); val st = edit?.s ?: tl.time
                        val s = edit ?: Sound("voice", st, st).also { sounds.add(it) }
                        s.file = f.absolutePath; s.text = txt; s.e = minOf(total(), s.s + Snd.durUs(a))
                        onSelect(Sel.S(sounds.indexOf(s))); rebuild(tl.time) }
                }
            })
            e.synthesizeToFile(txt, null, f, id)
        }
        if (tts != null && ttsOk) go() else tts = TextToSpeech(this) { st -> ttsOk = st == TextToSpeech.SUCCESS; main.post { if (ttsOk) go() else { w.dismiss(); toast(t("No voice engine on this phone", "Tiada enjin suara pada telefon ini")) } } }
    }
    /* make the extra sound track again a moment after a change */
    private fun mixLater() { main.removeCallbacks(mixRun); main.postDelayed(mixRun, 400) }
    private val mixRun = Runnable {
        val gen = ++mixGen; val list = sounds.map { it.copy() }; val tot = total()
        if (list.isEmpty()) { mixFile = null; mplayer.clearMediaItems(); return@Runnable }
        Thread { val f = File(work, "mix_$gen.wav"); val ok = try { Snd.mix(list, tot, f, audioCache); true } catch (e: Exception) { false }
            main.post { if (gen != mixGen || !ok) { f.delete(); return@post }
                val old = mixFile; mixFile = f; mplayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); mplayer.prepare(); mplayer.seekTo(tl.time / 1000)
                if (player.isPlaying) mplayer.play(); if (old != null && old != f) old.delete() } }.start()
    }

    /* ---------------- cover picture ---------------- */
    private fun cover() {
        if (clips.isEmpty()) return
        val (i, l) = locate(tl.time); val c = clips[i]; val at = c.inUs + (l * c.speed).toLong(); val u = tl.time
        Thread {
            val fr = try { MediaMetadataRetriever().run { setDataSource(c.file.absolutePath); val b = getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST); release(); b } } catch (e: Exception) { null }
            val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val cv = Canvas(out); cv.drawColor(Color.BLACK)
            if (fr != null) { val k = minOf(W.toFloat() / fr.width, H.toFloat() / fr.height); val dw = fr.width * k; val dh = fr.height * k
                val (m, o) = Looks.matrix(c.filter()); val pa = Paint(Paint.FILTER_BITMAP_FLAG)
                pa.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(m[0], m[4], m[8], 0f, o[0] * 255, m[1], m[5], m[9], 0f, o[1] * 255, m[2], m[6], m[10], 0f, o[2] * 255, 0f, 0f, 0f, 1f, 0f)))
                cv.drawBitmap(fr, null, RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2), pa) }
            val still = layers.map { it.copy().also { q -> q.anim = "none" } }
            cv.drawBitmap(art.render(still, u, W, H), 0f, 0f, null)
            val ok = try {
                val name = "Cover_" + System.currentTimeMillis() + ".jpg"
                if (Build.VERSION.SDK_INT >= 29) { val cvv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProMultiCam") }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cvv)!!; contentResolver.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) } }
                else { @Suppress("DEPRECATION") val d = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "ProMultiCam"); d.mkdirs(); File(d, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) } }
                true } catch (e: Exception) { false }
            main.post { toast(if (ok) t("Cover saved to gallery: Pictures/ProMultiCam", "Kulit disimpan ke galeri: Pictures/ProMultiCam") else t("Could not save", "Gagal simpan")) }
        }.start()
    }

    /* ---------------- export ---------------- */
    private fun save() {
        if (clips.isEmpty() || busy) return
        player.pause()
        val o = orig; val sp = getSharedPreferences("pmc", MODE_PRIVATE)
        val sh = Ui.sheet(this, t("Export", "Eksport"))
        pills(sh.body, t("Quality", "Kualiti"), listOf("720" to "720p", "1080" to "1080p"), { sp.getString("expRes", "1080") ?: "1080" }) { sp.edit().putString("expRes", it).apply() }
        pills(sh.body, t("Smoothness", "Kelancaran"), listOf("30" to "30 fps", "60" to t("Up to 60 fps", "Sehingga 60 fps")), { sp.getString("expFps", "30") ?: "30" }) { sp.edit().putString("expFps", it).apply() }
        sh.body.addView(Ui.hint(this, t("720p is smaller and quicker to send. 60 fps only if your videos were recorded at 60.", "720p lebih kecil dan cepat dihantar. 60 fps hanya jika video asal dirakam 60.")))
        if (o != null) sh.body.addView(Ui.btn(this, t("Replace the original video", "Ganti video asal")) { sh.dialog.dismiss(); export(o) }, Ui.lp(this, 14))
        sh.body.addView(Ui.btn(this, if (o != null) t("Save as a new video", "Simpan sebagai video baru") else t("Make the video", "Buat video"), true) { sh.dialog.dismiss(); export(null) }, Ui.lp(this, 8))
        sh.dialog.show()
    }
    private class Speed(val s: Float) : SpeedProvider { override fun getSpeed(timeUs: Long) = s; override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET }
    private fun export(replace: File?, noPip: Boolean = false) {
        busy = true
        // quality chosen in the export box: 720p is two thirds of the full size; 30 fps drops extra pictures
        val q720 = getSharedPreferences("pmc", MODE_PRIVATE).getString("expRes", "1080") == "720"; val fps30 = getSharedPreferences("pmc", MODE_PRIVATE).getString("expFps", "30") == "30"
        val ow = if (q720) (W * 2 / 3) / 2 * 2 else W; val oh = if (q720) (H * 2 / 3) / 2 * 2 else H
        val tmp = File(Store.dir(this), "tmp_edit_" + System.currentTimeMillis() + ".mp4")
        val dlg = AlertDialog.Builder(this).setMessage(t("Making the video… 0%", "Membuat video… 0%")).setCancelable(false).show()
        val lays = layers.map { it.copy() }
        val wantMix = sounds.isNotEmpty() && mixFile?.exists() == true
        var off = 0L
        val items = clips.mapIndexed { i, c ->
            val mi = MediaItem.Builder().setUri(Uri.fromFile(c.file)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inUs / 1000).setEndPositionMs(c.outUs / 1000).build()).build()
            val fx = ArrayList<androidx.media3.common.Effect>()
            if (c.rot != 0 || c.mirror) fx.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-c.rot.toFloat()).setScale(if (c.mirror) -1f else 1f, 1f).build())
            if (c.speed != 1f) fx.add(SpeedChangeEffect(c.speed))
            if (fps30) fx.add(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(30f))
            if (c.blur && !c.fill) fx.add(BlurFit(ow, oh)) else fx.add(Presentation.createForWidthAndHeight(ow, oh, if (c.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT))
            if (c.kf.isNotBlank() || c.stab) fx.add(KfFx(Keys.parse(c.kf), if (c.stab) Stab.load(this, c.file) else null, c.inUs, c.speed))
            val inT = c.trans.takeIf { i > 0 } ?: "none"; val outT = clips.getOrNull(i + 1)?.trans ?: "none"
            val moving = c.fx != "none" || inT != "none" || outT != "none"
            if (moving) fx.add(MotionFx(c.fx, inT, outT, c.dur, ow, oh))
            val vfi = if (c.fadeVid) c.fi else 0f; val vfo = if (c.fadeVid) c.fo else 0f
            if (moving || vfi > 0 || vfo > 0 || c.filter() != "brightness(1.0) contrast(1.0) saturate(1.0)") { val (m, o) = Looks.matrix(c.filter()); fx.add(ColorFx(m, o, c.fx, inT, outT, c.dur, vfi, vfo)) }
            val base = off; val du = c.dur
            val ov = ArrayList<androidx.media3.effect.TextureOverlay>()
            if (inT in listOf("wipe", "circle", "doors") || outT in listOf("wipe", "circle", "doors")) ov.add(MaskFx(c.fx, inT, outT, c.dur, ow, oh))
            if (lays.any { it.e > base && it.s < base + du }) ov.add(LayerFx(art, lays, base, ow, oh))
            if (ov.isNotEmpty()) fx.add(OverlayEffect(ImmutableList.copyOf(ov)))
            off += du
            val au = ArrayList<AudioProcessor>()
            if (c.dn) au.add(Denoise())
            if (c.speed != 1f) au.add(SpeedChangingAudioProcessor(Speed(c.speed)))
            val v = c.vol * origVol
            if (v != 1f || c.fi > 0 || c.fo > 0) au.add(Gain(v, c.fi, c.fo, c.dur))
            EditedMediaItem.Builder(mi).setEffects(Effects(au, fx)).build()
        }
        val seqs = ArrayList<EditedMediaItemSequence>(); seqs.add(EditedMediaItemSequence(items))
        // videos on top are added afterwards, one lane per pass (the phone only needs to open 2 videos at a time)
        val lanes = ArrayList<ArrayList<Layer>>()
        if (!noPip) layers.filter { it.kind == "pip" }.sortedBy { it.s }.forEach { l -> val ln = lanes.firstOrNull { it.last().e <= l.s }; if (ln != null) ln.add(l.copy()) else if (lanes.size < PIPS) lanes.add(arrayListOf(l.copy())) }
        if (wantMix) seqs.add(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(mixFile!!))).build())))
        val comp = Composition.Builder(seqs).experimentalSetForceAudioTrack(true).build()
        val total = total()
        fun finish(fin: File) {
            busy = false; dlg.dismiss()
            val out: File = if (replace != null) { replace.delete(); fin.renameTo(replace); replace } else { val nf = Store.newFile(this@EditorActivity, (orig?.name?.substringBefore("_") ?: "Video") + "_edit"); fin.renameTo(nf); nf }
            dirty = false; Drafts.delete(this@EditorActivity, draftId)
            if (replace != null) { orig = replace; thumbCache.clear(); thumbWant.clear(); undo.clear(); redo.clear(); val du = probe(replace).third; clips = arrayListOf(Clip(replace, 0, du, false).also { it.fileDur = du }); layers.clear(); sounds.clear(); origVol = 1f; onSelect(Sel.None); rebuild(0) }
            AlertDialog.Builder(this@EditorActivity).setMessage(t("✅ Saved in the app's Videos.", "✅ Disimpan dalam Video app."))
                .setPositiveButton(t("Save to Phone", "Simpan ke Telefon")) { _, _ -> Thread { val ok = Store.saveToPhone(this@EditorActivity, out); main.post { toast(if (ok) t("Saved to gallery: Movies/ProMultiCam", "Disimpan ke galeri: Movies/ProMultiCam") else t("Could not save", "Gagal simpan")) } }.start() }
                .setNegativeButton(t("Close", "Tutup"), null).show()
        }
        fun fail(e: ExportException, during: File?) {
            busy = false; dlg.dismiss(); tmp.delete(); during?.delete()
            if (lanes.isNotEmpty()) { AlertDialog.Builder(this@EditorActivity).setMessage(t("This phone could not put a video on top of another. Export without it?", "Telefon ini tidak dapat letak video atas video. Eksport tanpa video atas?") + "\n\n" + (e.message ?: ""))
                .setPositiveButton(t("Export without", "Eksport tanpa")) { _, _ -> export(replace, true) }.setNegativeButton(t("Cancel", "Batal"), null).show(); return }
            AlertDialog.Builder(this@EditorActivity).setMessage(t("Could not make the video: ", "Gagal membuat video: ") + (e.message ?: "")).setPositiveButton("OK", null).show()
        }
        var stepTxt = ""
        fun run(c: Composition, out: File, done: () -> Unit, during: File?) {
            val tr = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { done() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { fail(exportException, during) }
                }).build()
            try { tr.start(c, out.absolutePath) } catch (e: Exception) { busy = false; dlg.dismiss(); toast(t("Could not make the video", "Gagal membuat video") + ": " + e.message); return }
            val ph = ProgressHolder()
            fun prog() { if (!busy) return; if (tr.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) dlg.setMessage(t("Making the video… ", "Membuat video… ") + stepTxt + ph.progress + "%"); main.postDelayed({ prog() }, 400) }
            prog()
        }
        /* one pass: the video so far + one lane of videos on top */
        fun lanePass(k: Int, input: File) {
            if (k >= lanes.size) { finish(input); return }
            stepTxt = "(${k + 2}/${lanes.size + 1}) "
            val lane = lanes[k]; val ww = ow; val hh = oh; val m = minOf(ow, oh)
            Thread {
                val pi = ArrayList<EditedMediaItem>(); var at = 0L
                fun gap(us: Long) { if (us < 50_000) return; val g = File(work, "gap_" + us + ".mp4"); if (!g.exists()) StillClip.make(Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }, 320, 180, us, g)
                    pi.add(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(g))).setRemoveAudio(true).build()) }
                try {
                    lane.forEach { l -> gap(l.s - at)
                        val len = minOf(l.e - l.s, if (l.vdur > 0) l.vdur - l.vin else Long.MAX_VALUE)
                        val pw = (m * 0.42f * l.scale).toInt() / 2 * 2; val ph = (pw / l.ar).toInt() / 2 * 2
                        val mi = MediaItem.Builder().setUri(Uri.fromFile(File(l.file))).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(l.vin / 1000).setEndPositionMs((l.vin + len) / 1000).build()).build()
                        val pfx = ArrayList<androidx.media3.common.Effect>(); pfx.add(Presentation.createForWidthAndHeight(pw, ph, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                        if (l.key != 0) pfx.add(ChromaKey(l.key, l.keyTol))   // green screen
                        pi.add(EditedMediaItem.Builder(mi).setRemoveAudio(true).setEffects(Effects(listOf(), pfx)).build()); at = l.s + len }
                    gap(total - at)
                } catch (e: Exception) {}
                main.post {
                    val set = object : androidx.media3.effect.VideoCompositorSettings {
                        override fun getOutputSize(inputSizes: MutableList<androidx.media3.common.util.Size>) = androidx.media3.common.util.Size(ww, hh)
                        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): androidx.media3.effect.OverlaySettings {
                            if (inputId == 0) return androidx.media3.effect.OverlaySettings.Builder().build()
                            val l = lane.firstOrNull { presentationTimeUs >= it.s && presentationTimeUs < it.e }
                            return if (l == null) androidx.media3.effect.OverlaySettings.Builder().setAlphaScale(0f).build()
                            else { val e = l.at(presentationTimeUs); val k = e.scale / l.scale
                                androidx.media3.effect.OverlaySettings.Builder().setBackgroundFrameAnchor(e.x * 2 - 1, 1 - e.y * 2).setScale(k, k).build() }
                        }
                    }
                    val c = Composition.Builder(listOf(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).build())), EditedMediaItemSequence(pi)))
                        .experimentalSetForceAudioTrack(true).setVideoCompositorSettings(set).build()
                    val out = File(Store.dir(this), "tmp_edit_" + System.currentTimeMillis() + ".mp4")
                    run(c, out, { input.delete(); lanePass(k + 1, out) }, input)
                }
            }.start()
        }
        stepTxt = if (lanes.isEmpty()) "" else "(1/${lanes.size + 1}) "
        run(comp, tmp, { lanePass(0, tmp) }, null)
    }

    /* colours of a clip, plus darkening / whitening of the switch effects (time counted from the clip's first picture) */
    /* sound level of a clip with its fade in / out (the "hill") */
    class Gain(private val vol: Float, private val fi: Float, private val fo: Float, private val dur: Long) : androidx.media3.common.audio.BaseAudioProcessor() {
        private var frames = 0L
        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat { if (f.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f); return f }
        override fun queueInput(b: java.nio.ByteBuffer) {
            val ch = inputAudioFormat.channelCount; val rate = inputAudioFormat.sampleRate
            val out = replaceOutputBuffer(b.remaining()); b.order(java.nio.ByteOrder.nativeOrder())
            while (b.remaining() >= 2 * ch) {
                val t = frames * 1_000_000L / rate; var g = vol
                if (fi > 0) g *= (t / (fi * 1e6f)).coerceIn(0f, 1f); if (fo > 0) g *= ((dur - t) / (fo * 1e6f)).coerceIn(0f, 1f)
                for (k in 0 until ch) out.putShort((b.short * g).toInt().coerceIn(-32768, 32767).toShort())
                frames++
            }
            out.flip()
        }
        override fun onFlush() { frames = 0 }
        override fun onReset() { frames = 0 }
    }
    class ColorFx(private val m: FloatArray, private val o: FloatArray, private val eff: String, private val inT: String, private val outT: String, private val dur: Long, private val fi: Float = 0f, private val fo: Float = 0f) : RgbMatrix {
        private var first = -1L
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            if (first < 0) first = presentationTimeUs
            val st = Fx.state(eff, inT, outT, presentationTimeUs - first, dur, fi, fo)
            val f = (1 - st.dark) * (1 - st.white); val w = st.white
            val r = FloatArray(16); for (i in 0..2) { for (j in 0..2) r[j * 4 + i] = m[j * 4 + i] * f; r[12 + i] = o[i] * f + w }; r[15] = 1f
            return r
        }
    }
    /* zoom / move / turn of a clip and of the slide, zoom and whip switches */
    class MotionFx(private val eff: String, private val inT: String, private val outT: String, private val dur: Long, private val W: Int, private val H: Int) : MatrixTransformation {
        private var first = -1L
        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
            if (first < 0) first = presentationTimeUs
            val st = Fx.state(eff, inT, outT, presentationTimeUs - first, dur)
            val mm = android.graphics.Matrix(); mm.setScale(W / 2f, H / 2f); mm.postRotate(-st.rot); mm.postScale(st.s, st.s); mm.postScale(2f / W, 2f / H); mm.postTranslate(st.tx, st.ty)
            return mm
        }
    }
    /* the black shapes of the wipe, circle and doors switches */
    class MaskFx(private val eff: String, private val inT: String, private val outT: String, private val dur: Long, private val W: Int, private val H: Int) : BitmapOverlay() {
        private var first = -1L; private val clear = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); private var last: Bitmap = clear
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (first < 0) first = presentationTimeUs
            val st = Fx.state(eff, inT, outT, presentationTimeUs - first, dur)
            if (st.mask.isEmpty() || st.vis >= 1f) { last = clear; return clear }
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); Fx.drawMask(Canvas(b), st, W.toFloat(), H.toFloat()); last = b; return b
        }
    }
    /* what sits on top, drawn while saving */
    class LayerFx(private val art: TextArt, private val list: List<Layer>, private val base: Long, private val W: Int, private val H: Int) : BitmapOverlay() {
        private var first = -1L; private var key = "?"; private var bmp: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (first < 0) first = presentationTimeUs
            val u = base + (presentationTimeUs - first)
            val k = art.key(list, u)
            if (k != key) { key = k; bmp = art.render(list, u, W, H) }
            return bmp
        }
    }
}
