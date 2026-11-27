package com.promulticam.app

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.util.Range
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.webrtc.CameraVideoCapturer

/* Tap to focus (and set brightness) at a point of the picture, like a phone camera.
   x, y are 0..1 of the picture as it is shown (upright). auto = back to continuous focus. */
object Focus {
    /* torch and brightness stay on when focus is changed later */
    var torch = false; var ev = 0
    fun light(cap: CameraVideoCapturer, front: Boolean, torchOn: Boolean, evStep: Int): Boolean { torch = torchOn; ev = evStep; return tap(cap, 0.5f, 0.5f, front, true) }
    private fun session(cap: Any): Any? {
        var c: Class<*>? = cap.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) { try { f.isAccessible = true; val v = f.get(cap); if (v != null && v.javaClass.simpleName == "Camera2Session") return v } catch (e: Exception) {} }
            c = c.superclass
        }
        return null
    }
    private fun <T> field(o: Any, type: Class<T>): T? {
        for (f in o.javaClass.declaredFields) { try { f.isAccessible = true; val v = f.get(o); if (v != null && type.isInstance(v)) return type.cast(v) } catch (e: Exception) {} }
        return null
    }
    fun tap(cap: CameraVideoCapturer, x0: Float, y0: Float, front: Boolean, auto: Boolean = false): Boolean {
        val s = session(cap) ?: return false
        val dev = field(s, CameraDevice::class.java) ?: return false
        val cs = field(s, CameraCaptureSession::class.java) ?: return false
        val sf = field(s, Surface::class.java) ?: return false
        val h = field(s, Handler::class.java) ?: return false
        val ch = field(s, CameraCharacteristics::class.java) ?: return false
        h.post {
            try {
                val rb = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD); rb.addTarget(sf)
                val fr = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                (fr?.filter { it.upper == 30 }?.minByOrNull { it.upper - it.lower } ?: fr?.maxByOrNull { it.upper })?.let { rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it as Range<Int>) }
                if (ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true)
                    rb.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                rb.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (torch && ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) rb.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { r -> val st = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f
                    // ev = -2..+2 (in half stops of light)
                    rb.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.round(ev * 0.5f / maxOf(0.05f, st)).coerceIn(r.lower, r.upper)) }
                val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if (auto) {
                    if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) rb.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    cs.setRepeatingRequest(rb.build(), null, h); return@post
                }
                // shown picture → sensor: undo the mirror (front) and the turn
                var u = x0.coerceIn(0f, 1f); val v = y0.coerceIn(0f, 1f); if (front) u = 1 - u
                val rot = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val (sx, sy) = when (rot) { 90 -> v to 1 - u; 270 -> 1 - v to u; 180 -> 1 - u to 1 - v; else -> u to v }
                val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
                val half = (minOf(a.width(), a.height()) * 0.08f).toInt()
                val cx = (a.left + sx * a.width()).toInt(); val cy = (a.top + sy * a.height()).toInt()
                val r = Rect((cx - half).coerceAtLeast(a.left), (cy - half).coerceAtLeast(a.top), (cx + half).coerceAtMost(a.right - 1), (cy + half).coerceAtMost(a.bottom - 1))
                val m = arrayOf(MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1))
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) rb.set(CaptureRequest.CONTROL_AE_REGIONS, m)
                val canAf = (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0 && afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
                if (canAf) {
                    rb.set(CaptureRequest.CONTROL_AF_REGIONS, m); rb.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    rb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL); cs.capture(rb.build(), null, h)
                    rb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE); cs.setRepeatingRequest(rb.build(), null, h)
                    rb.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START); cs.capture(rb.build(), null, h)
                } else cs.setRepeatingRequest(rb.build(), null, h)
            } catch (e: Exception) {}
        }
        return true
    }

    /* the gold focus square where the finger touched */
    fun ring(parent: FrameLayout, x: Float, y: Float, locked: Boolean = false) {
        val c: Context = parent.context; val sz = Ui.px(c, 76)
        val v = View(c); v.background = Ui.round(0x00000000, Ui.dp(c, 8f), Ui.GOLD, Ui.px(c, 2))
        parent.addView(v, FrameLayout.LayoutParams(sz, sz).apply { leftMargin = (x - sz / 2).toInt(); topMargin = (y - sz / 2).toInt() })
        v.scaleX = 1.5f; v.scaleY = 1.5f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).withEndAction {
            v.animate().alpha(if (locked) 0.9f else 0f).setStartDelay(900).setDuration(400).withEndAction { if (!locked) (v.parent as? ViewGroup)?.removeView(v) }.start()
        }.start()
    }
}
