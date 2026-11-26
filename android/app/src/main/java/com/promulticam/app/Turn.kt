package com.promulticam.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.OrientationEventListener
import kotlin.math.abs

/* Follows how the phone is held, like the web app: it turns only after the phone has stayed
   in the new position for a moment, so a wobble or a quick tilt does not flip the screen. */
class Turn(private val a: Activity, private val onTurn: (Int) -> Unit) : OrientationEventListener(a) {
    var cur = 0; private var cand = -1; private var candAt = 0L
    override fun onOrientationChanged(o: Int) {
        if (o == ORIENTATION_UNKNOWN) return
        var near = -1
        for (c in intArrayOf(0, 90, 270, 360)) { if (abs(o - c) < 28) { near = c % 360; break } }
        val now = SystemClock.uptimeMillis()
        if (near < 0 || near == cur) { cand = -1; return }
        if (near != cand) { cand = near; candAt = now; return }
        if (now - candAt >= 1300) { cur = near; cand = -1; apply(); onTurn(near) }
    }
    fun apply() {
        a.requestedOrientation = when (cur) { 90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; 270 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    val side get() = cur == 90 || cur == 270
}
