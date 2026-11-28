package com.promulticam.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Photos: a still from the Director's picture, or a full-size HD photo from a camera phone */
object Photo {
    /* the camera's own full-size photo: the live video pauses for about a second, then carries on */
    @SuppressLint("MissingPermission")
    fun still(ctx: Context, cam: Rtc.Cam, vw: Int, vh: Int, done: (ByteArray?) -> Unit) {
        val th = HandlerThread("pmc-photo").apply { start() }; val h = Handler(th.looper)
        val main = Handler(ctx.mainLooper)
        var sent = false
        fun finish(b: ByteArray?) {
            if (sent) return; sent = true
            main.post { try { cam.capturer.startCapture(vw, vh, 30); cam.lock30() } catch (e: Exception) {}; done(b) }
            h.postDelayed({ th.quitSafely() }, 500)
        }
        Thread {
            try { cam.capturer.stopCapture() } catch (e: Exception) {}
            try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { (cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) == cam.front }
                    ?: cm.cameraIdList.first()
                val ch = cm.getCameraCharacteristics(id)
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val size = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
                val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                @Suppress("DEPRECATION") val rot = when ((ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation) {
                    Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                val jpegRot = if (cam.front) (sensor + rot) % 360 else (sensor - rot + 360) % 360
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
                reader.setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage()
                    val b = img?.planes?.get(0)?.buffer?.let { bb -> ByteArray(bb.remaining()).also { bb.get(it) } }
                    img?.close(); finish(b)
                }, h)
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(dev: CameraDevice) {
                        @Suppress("DEPRECATION")
                        dev.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    // a short warm-up so focus and brightness settle, then the photo
                                    val pre = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) }
                                    val shot = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.JPEG_ORIENTATION, jpegRot); set(CaptureRequest.JPEG_QUALITY, 95.toByte()) }
                                    // the warm-up frames are dropped: only the last picture is kept
                                    reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, h)
                                    s.setRepeatingRequest(pre.build(), null, h)
                                    h.postDelayed({
                                        try {
                                            s.stopRepeating()
                                            reader.setOnImageAvailableListener({ r ->
                                                val img = r.acquireLatestImage()
                                                val b = img?.planes?.get(0)?.buffer?.let { bb -> ByteArray(bb.remaining()).also { bb.get(it) } }
                                                img?.close(); try { dev.close() } catch (e: Exception) {}; reader.close(); finish(b)
                                            }, h)
                                            s.capture(shot.build(), null, h)
                                        } catch (e: Exception) { dev.close(); finish(null) }
                                    }, 900)
                                } catch (e: Exception) { dev.close(); finish(null) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { dev.close(); finish(null) }
                        }, h)
                    }
                    override fun onDisconnected(dev: CameraDevice) { dev.close(); finish(null) }
                    override fun onError(dev: CameraDevice, e: Int) { dev.close(); finish(null) }
                }, h)
                h.postDelayed({ finish(null) }, 8000)
            } catch (e: Exception) { finish(null) }
        }.start()
    }

    /* a JPEG turned upright, with the look's colours and (optional) the brand on top */
    fun styled(jpeg: ByteArray, lookF: String, ov: Bitmap?, progW: Int, progH: Int): ByteArray {
        var b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val o = try { ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (e: Exception) { 1 }
        val deg = when (o) { ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 }
        if (deg != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        // with the brand on, the photo is cut to the same shape as the video so the brand sits right
        var w = b.width; var hh = b.height
        if (ov != null && progW > 0 && progH > 0) {
            val want = progW.toFloat() / progH
            if (w.toFloat() / hh > want) w = (hh * want).toInt() else hh = (w / want).toInt()
        }
        val out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG); p.colorFilter = ColorMatrixColorFilter(colour(lookF))
        val sx = (b.width - w) / 2; val sy = (b.height - hh) / 2
        c.drawBitmap(b, Rect(sx, sy, sx + w, sy + hh), Rect(0, 0, w, hh), p)
        ov?.let { c.drawBitmap(it, null, Rect(0, 0, w, hh), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return jpg(out)
    }

    fun colour(lookF: String): ColorMatrix {
        val (m, o) = Looks.matrix(lookF)   // column-major 4x4 + offset (0..1)
        val a = FloatArray(20)
        for (i in 0..2) { for (j in 0..2) a[i * 5 + j] = m[j * 4 + i]; a[i * 5 + 4] = o[i] * 255f }
        a[18] = 1f
        return ColorMatrix(a)
    }

    fun jpg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 94, it) }.toByteArray()

    /* RGBA rows read from the GPU (bottom row first) into an upright picture */
    fun fromGl(px: java.nio.ByteBuffer, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); px.rewind(); b.copyPixelsFromBuffer(px)
        return Bitmap.createBitmap(b, 0, 0, w, h, Matrix().apply { postScale(1f, -1f) }, false)
    }

    /* save into the phone's gallery: Pictures/ProMultiCam */
    fun save(ctx: Context, jpeg: ByteArray, title: String): Boolean = try {
        val t = title.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(32).ifEmpty { "Photo" }
        val name = String.format(Locale.US, "%s_%s.jpg", t, SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()))
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProMultiCam"); put(MediaStore.Images.Media.IS_PENDING, 1) }
            val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            ctx.contentResolver.openOutputStream(u)!!.use { it.write(jpeg) }
            ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null); true
        } else {
            @Suppress("DEPRECATION") val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ProMultiCam"); d.mkdirs()
            val f = File(d, name); f.writeBytes(jpeg); MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null); true
        }
    } catch (e: Exception) { false }
}
