package com.promulticam.app

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.promulticam.app.Ui.t
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Buttons that wrap onto the next line instead of scrolling sideways */
class Flow(c: Context, private val gap: Int = Ui.px(c, 8)) : ViewGroup(c) {
    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) { val v = getChildAt(i); if (v.visibility == GONE) continue
            measureChild(v, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += v.measuredWidth + gap; rowH = maxOf(rowH, v.measuredHeight) }
        setMeasuredDimension(MeasureSpec.getSize(ws), y + rowH + paddingTop + paddingBottom)
    }
    override fun onLayout(ch: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) { val v = getChildAt(i); if (v.visibility == GONE) continue
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            v.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + v.measuredWidth, paddingTop + y + v.measuredHeight)
            x += v.measuredWidth + gap; rowH = maxOf(rowH, v.measuredHeight) }
    }
}

/* Support chat pieces shared by the customer page and the admin page */
object Chat {
    const val BASE = "projects/kukusanmy/databases/promulticam/documents"
    /* a photo (proof) made small enough to keep inside the message */
    fun photo(c: Context, u: Uri): String? = try {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 2000) s *= 2
        var bm = c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
        val rot = try { c.contentResolver.openInputStream(u)!!.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } } catch (e: Exception) { 0 }
        if (rot != 0) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
        var side = 1100; var q = 75; var out: String
        do { val k = minOf(1f, side.toFloat() / maxOf(bm.width, bm.height)); val sm = if (k < 1f) Bitmap.createScaledBitmap(bm, (bm.width * k).toInt(), (bm.height * k).toInt(), true) else bm
            val bo = ByteArrayOutputStream(); sm.compress(Bitmap.CompressFormat.JPEG, q, bo); out = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP); side -= 200; q -= 8
        } while (out.length > 600_000 && side > 300)
        out
    } catch (e: Exception) { null }
    private fun bitmap(b64: String): Bitmap? = try { val a = Base64.decode(b64, Base64.DEFAULT); BitmapFactory.decodeByteArray(a, 0, a.size) } catch (e: Exception) { null }
    /* one message bubble; a picture opens big when tapped */
    fun bubble(c: Context, list: LinearLayout, me: Boolean, text: String, img: String, sub: String) {
        val d = Ui.px(c, 12)
        val b = LinearLayout(c); b.orientation = LinearLayout.VERTICAL; b.setPadding(d, d * 3 / 4, d, d * 3 / 4)
        b.background = Ui.round(if (me) 0xFF262117.toInt() else Ui.CARD2, Ui.dp(c, 14f), if (me) Ui.GOLD else Ui.LINE, 1)
        if (img.isNotEmpty()) bitmap(img)?.let { bm -> val iv = ImageView(c); iv.setImageBitmap(bm); iv.adjustViewBounds = true; iv.maxHeight = Ui.px(c, 260); iv.scaleType = ImageView.ScaleType.FIT_START
            iv.setOnClickListener { big(c, bm) }; b.addView(iv, LinearLayout.LayoutParams(Ui.px(c, 220), -2)) }
        if (text.isNotEmpty()) b.addView(Ui.label(c, text, 15f, Ui.TEXT).apply { setTextIsSelectable(true) }, Ui.lp(c, if (img.isNotEmpty()) 6 else 0))
        if (sub.isNotEmpty()) b.addView(Ui.label(c, sub, 11f, Ui.SUB))
        val wrap = LinearLayout(c); wrap.gravity = if (me) Gravity.END else Gravity.START
        wrap.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (me) leftMargin = d * 4 else rightMargin = d * 4 })
        list.addView(wrap, Ui.lp(c, 8))
    }
    private fun big(c: Context, bm: Bitmap) {
        val dl = Dialog(c, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(c); iv.setImageBitmap(bm); iv.setBackgroundColor(Color.BLACK); iv.scaleType = ImageView.ScaleType.FIT_CENTER; iv.setOnClickListener { dl.dismiss() }
        dl.setContentView(iv); dl.show()
    }
    fun show(c: Context, list: LinearLayout, msgs: List<Fs.Doc>, mine: String, welcome: String?) {
        list.removeAllViews(); val f = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        if (msgs.isEmpty() && welcome != null) bubble(c, list, false, welcome, "", "")
        msgs.forEach { m -> val me = m.s("from") == mine
            bubble(c, list, me, m.s("text"), m.s("img"), (if (m.s("from") == "admin" && mine == "user") "ProMultiCam · " else "") + if (m.t("at") > 0) f.format(Date(m.t("at"))) else "") }
        (list.parent as? ScrollView)?.post { (list.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }
    /* send one message (text and/or photo) and update the conversation */
    fun send(uid: String, from: String, text: String, img: String?, topic: String = ""): Boolean {
        val mf = JSONObject().put("from", Fs.enc(from)).put("text", Fs.enc(text)); if (!img.isNullOrEmpty()) mf.put("img", Fs.enc(img))
        val w1 = JSONObject().put("update", JSONObject().put("name", "$BASE/supportChats/$uid/messages/" + Fs.newId()).put("fields", mf))
            .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "at").put("setToServerValue", "REQUEST_TIME"))).put("currentDocument", JSONObject().put("exists", false))
        val last = text.ifEmpty { t("📷 Photo", "📷 Gambar") }.take(300)
        val tf = JSONObject().put("lastMsg", Fs.enc(last)).put("lastFrom", Fs.enc(from)).put("unreadUser", Fs.enc(from == "admin")).put("unreadAdmin", Fs.enc(from == "user"))
        val mask = arrayListOf("lastMsg", "lastFrom", "unreadUser", "unreadAdmin")
        if (from == "user") { tf.put("uid", Fs.enc(uid)).put("email", Fs.enc(Auth.email ?: "")); mask.add("uid"); mask.add("email"); if (topic.isNotEmpty()) { tf.put("topic", Fs.enc(topic)); mask.add("topic") } }
        val w2 = JSONObject().put("update", JSONObject().put("name", "$BASE/supportChats/$uid").put("fields", tf)).put("updateMask", JSONObject().put("fieldPaths", JSONArray(mask)))
            .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "lastAt").put("setToServerValue", "REQUEST_TIME")))
        return Fs.commit(listOf(w1, w2))
    }
}
