package com.promulticam.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/* The look of the web app: dark, gold, raised "3D" buttons, rounded cards, bottom sheets. BM / EN words. */
object Ui {
    const val GOLD = 0xFFC8A45C.toInt()
    const val GOLD2 = 0xFFDCBD7A.toInt()
    const val GOLD_DARK = 0xFF7A6232.toInt()
    const val BG = 0xFF0B0A08.toInt()
    const val CARD = 0xFF1C1B19.toInt()
    const val CARD2 = 0xFF26241F.toInt()
    const val LINE = 0xFF34312A.toInt()
    const val TEXT = 0xFFF2EFE8.toInt()
    const val SUB = 0xFF9B978E.toInt()
    const val INFO = 0xFF9FB1CC.toInt()
    const val RED = 0xFFD23C3C.toInt()

    private var lang: String? = null
    fun init(c: Context) { if (lang == null) lang = c.getSharedPreferences("pmc", Context.MODE_PRIVATE).getString("lang", null) ?: if (Locale.getDefault().language in listOf("ms", "in")) "ms" else "en" }
    fun setLang(c: Context, l: String) { lang = l; c.getSharedPreferences("pmc", Context.MODE_PRIVATE).edit().putString("lang", l).apply() }
    val ms: Boolean get() = (lang ?: "ms") == "ms"
    fun t(en: String, bm: String) = if (ms) bm else en

    fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
    fun px(c: Context, v: Int) = dp(c, v.toFloat()).toInt()

    fun round(color: Int, r: Float, stroke: Int = 0, sw: Int = 0): GradientDrawable {
        val d = GradientDrawable(); d.cornerRadius = r; d.setColor(color); if (sw > 0) d.setStroke(sw, stroke); return d
    }
    /* a raised button: coloured face on top of a darker "edge" */
    fun raised(c: Context, face: Int, edge: Int, r: Float = dp(c, 14f), stroke: Int = 0): Drawable {
        val e = round(edge, r); val f = if (stroke != 0) round(face, r, stroke, px(c, 1)) else round(face, r)
        val l = LayerDrawable(arrayOf(e, f)); l.setLayerInset(1, 0, 0, 0, px(c, 4)); return l
    }
    fun goldFace(c: Context, r: Float = dp(c, 14f)): Drawable {
        val f = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(GOLD2, GOLD)); f.cornerRadius = r
        val l = LayerDrawable(arrayOf(round(GOLD_DARK, r), f)); l.setLayerInset(1, 0, 0, 0, px(c, 4)); return l
    }

    fun btn(c: Context, label: String, gold: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = label; b.isAllCaps = false; b.textSize = 15f
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        b.setTextColor(if (gold) 0xFF1A1405.toInt() else TEXT)
        b.background = if (gold) goldFace(c) else raised(c, CARD2, 0xFF0E0D0B.toInt(), stroke = LINE)
        b.stateListAnimator = null
        b.minHeight = px(c, 48); b.minimumHeight = px(c, 48)
        b.setPadding(px(c, 14), px(c, 8), px(c, 14), px(c, 12))
        b.setOnClickListener { onClick() }
        return b
    }
    fun dangerBtn(c: Context, label: String, onClick: () -> Unit): Button {
        val b = btn(c, label, false, onClick); b.setTextColor(0xFFE58A7E.toInt()); b.background = raised(c, 0xFF3A1F1C.toInt(), 0xFF140A09.toInt()); return b
    }
    fun iconBtn(c: Context, icon: Int, tint: Int = TEXT, onClick: () -> Unit): ImageButton {
        val b = ImageButton(c); b.setImageResource(icon); b.imageTintList = ColorStateList.valueOf(tint)
        b.background = raised(c, CARD2, 0xFF0E0D0B.toInt(), dp(c, 12f), LINE)
        b.setPadding(px(c, 11), px(c, 9), px(c, 11), px(c, 13)); b.scaleType = ImageView.ScaleType.FIT_CENTER
        b.setOnClickListener { onClick() }
        return b
    }

    fun label(c: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val v = TextView(c); v.text = s; v.textSize = size; v.setTextColor(color)
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return v
    }
    fun input(c: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(c); e.hint = hint; e.setText(value); e.inputType = type
        e.setTextColor(TEXT); e.setHintTextColor(0xFF6E6A62.toInt()); e.textSize = 16f
        e.background = round(0xFF121110.toInt(), dp(c, 12f), LINE, px(c, 1))
        e.setPadding(px(c, 14), px(c, 12), px(c, 14), px(c, 12))
        return e
    }
    fun check(c: Context, text: String, on: Boolean, changed: (Boolean) -> Unit): CheckBox {
        val b = CheckBox(c); b.text = text; b.setTextColor(TEXT); b.textSize = 15f; b.isChecked = on
        b.buttonTintList = ColorStateList.valueOf(GOLD); b.setPadding(px(c, 6), px(c, 6), 0, px(c, 6))
        b.setOnCheckedChangeListener { _, v -> changed(v) }
        return b
    }
    /* a pill that can be "on" (gold) or "off" */
    fun pill(c: Context, text: String, on: Boolean, onClick: () -> Unit): Button {
        val b = Button(c); b.text = text; b.isAllCaps = false; b.textSize = 14f; b.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        b.stateListAnimator = null; b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        b.setPadding(px(c, 16), px(c, 9), px(c, 16), px(c, 12))
        setPill(c, b, on); b.setOnClickListener { onClick() }; return b
    }
    fun setPill(c: Context, b: Button, on: Boolean) {
        b.background = if (on) goldFace(c, dp(c, 99f)) else raised(c, CARD2, 0xFF0E0D0B.toInt(), dp(c, 99f), LINE)
        b.setTextColor(if (on) 0xFF1A1405.toInt() else TEXT)
    }
    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c); l.orientation = LinearLayout.VERTICAL; l.background = round(0xCC15140F.toInt(), dp(c, 16f), LINE, px(c, 1))
        l.setPadding(px(c, 14), px(c, 14), px(c, 14), px(c, 14)); return l
    }
    fun lp(c: Context, top: Int = 0, w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(w, h).apply { topMargin = px(c, top) }
    fun hr(c: Context): View { val v = View(c); v.setBackgroundColor(LINE); v.layoutParams = LinearLayout.LayoutParams(-1, px(c, 1)).apply { topMargin = px(c, 12); bottomMargin = px(c, 4) }; return v }
    fun center(v: TextView): TextView { v.gravity = Gravity.CENTER; return v }

    /* bottom sheet like the web panels: title, ✕, scrolling body, optional big button at the bottom */
    class Sheet(val dialog: Dialog, val body: LinearLayout)
    fun sheet(c: Context, title: String, footer: Pair<String, () -> Unit>? = null): Sheet {
        val d = Dialog(c); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(c); outer.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(); bg.setColor(0xFF161512.toInt()); val r = dp(c, 22f); bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); bg.setStroke(px(c, 1), LINE)
        outer.background = bg; outer.setPadding(px(c, 16), px(c, 16), px(c, 16), px(c, 16))
        val head = LinearLayout(c); head.orientation = LinearLayout.HORIZONTAL; head.gravity = Gravity.CENTER_VERTICAL
        head.addView(label(c, title, 20f, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        val x = btn(c, "✕") { d.dismiss() }; x.minWidth = px(c, 48); x.minimumWidth = px(c, 48)
        head.addView(x, LinearLayout.LayoutParams(px(c, 52), px(c, 52)))
        outer.addView(head)
        val sv = ScrollView(c); val body = LinearLayout(c); body.orientation = LinearLayout.VERTICAL; body.setPadding(0, px(c, 8), 0, px(c, 8)); sv.addView(body)
        outer.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        footer?.let { (l, f) -> outer.addView(btn(c, l, true) { f(); d.dismiss() }, lp(c, 8)) }
        d.setContentView(outer)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (c.resources.displayMetrics.heightPixels * 0.78f).toInt())
            w.setDimAmount(0.55f)
        }
        return Sheet(d, body)
    }
    fun section(c: Context, s: String) = label(c, s, 15f, TEXT, true).apply { layoutParams = lp(c, 14) }
    fun hint(c: Context, s: String) = label(c, s, 13f, SUB).apply { layoutParams = lp(c, 4) }
    fun fl(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)
    @Suppress("unused") fun vg(v: View) = v as ViewGroup
}
