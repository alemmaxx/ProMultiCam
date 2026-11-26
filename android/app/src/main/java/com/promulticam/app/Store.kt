package com.promulticam.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Like the web app: videos are kept inside the app first; "Save to Phone" puts a copy in the gallery */
object Store {
    fun dir(c: Context): File = File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: c.filesDir, "ProMultiCam").apply { mkdirs() }
    fun list(c: Context): List<File> = dir(c).listFiles { f -> f.isFile && f.name.endsWith(".mp4") && !f.name.startsWith("tmp_") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /* the same naming as the web: Title_001_YYYY-MM-DD_HHMM.mp4 */
    fun newFile(c: Context, title: String): File {
        val sp = c.getSharedPreferences("pmc", Context.MODE_PRIVATE)
        val n = sp.getInt("seq", 0) + 1; sp.edit().putInt("seq", n).apply()
        val t = title.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(32).ifEmpty { "Video" }
        return File(dir(c), String.format(Locale.US, "%s_%03d_%s.mp4", t, n, SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())))
    }
    fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    fun saveToPhone(c: Context, f: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, f.name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProMultiCam"); put(MediaStore.Video.Media.IS_PENDING, 1) }
            val u = c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
            c.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            c.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null); true
        } else {
            @Suppress("DEPRECATION") val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ProMultiCam"); d.mkdirs()
            val o = File(d, f.name); f.copyTo(o, true); MediaScannerConnection.scanFile(c, arrayOf(o.absolutePath), arrayOf("video/mp4"), null); true
        }
    } catch (e: Exception) { false }
}
