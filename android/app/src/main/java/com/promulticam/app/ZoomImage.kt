package com.promulticam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/* A picture you can zoom: two fingers to zoom, drag to move, double-tap to zoom in / back out.
   When not zoomed, a sideways swipe calls onSwipe (-1 = next, +1 = previous). */
class ZoomImage(c: Context) : ImageView(c) {
    var onSwipe: ((Int) -> Unit)? = null
    private val m = Matrix(); private var base = 1f; private var zoom = 1f
    private var bw = 0; private var bh = 0
    private var downX = 0f; private var multi = false

    init { scaleType = ScaleType.MATRIX }

    fun show(b: Bitmap?) { setImageBitmap(b); bw = b?.width ?: 0; bh = b?.height ?: 0; fit() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun fit() {
        if (bw == 0 || width == 0) return
        base = minOf(width.toFloat() / bw, height.toFloat() / bh); zoom = 1f
        m.reset(); m.postScale(base, base); m.postTranslate((width - bw * base) / 2f, (height - bh * base) / 2f); imageMatrix = m
    }
    private fun keepInside() {
        val r = RectF(0f, 0f, bw.toFloat(), bh.toFloat()); m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy); imageMatrix = m
    }
    private fun zoomTo(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(1f, 6f); val k = nz / zoom; zoom = nz
        m.postScale(k, k, fx, fy); keepInside()
    }

    private val sg = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY); return true }
    })
    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { if (zoom > 1.05f) fit() else zoomTo(2.5f, e.x, e.y); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom > 1.01f && !sg.isInProgress) { m.postTranslate(-dx, -dy); keepInside() }; return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e); gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_UP -> if (!multi && zoom <= 1.01f) { val dx = e.x - downX; if (dx < -90) onSwipe?.invoke(-1) else if (dx > 90) onSwipe?.invoke(1) }
        }
        return true
    }
}
