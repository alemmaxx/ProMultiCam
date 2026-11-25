package com.promulticam.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import java.util.Locale

/* Small helpers: colours, buttons and BM / EN words (the phone's language decides) */
object Ui {
    const val GOLD = 0xFFC8A45C.toInt()
    const val BG = 0xFF0E0E0F.toInt()
    const val CARD = 0xFF1C1C1E.toInt()
    const val TEXT = 0xFFF2EFE8.toInt()
    const val SUB = 0xFF9B978E.toInt()

    val ms: Boolean get() = Locale.getDefault().language == "ms" || Locale.getDefault().language == "in"
    fun t(en: String, bm: String) = if (ms) bm else en

    fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)

    fun btn(c: Context, label: String, gold: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.textSize = 15f
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        b.setTextColor(if (gold) 0xFF1A1405.toInt() else TEXT)
        val d = GradientDrawable()
        d.cornerRadius = dp(c, 14f)
        d.setColor(if (gold) GOLD else 0xFF2A2A2D.toInt())
        b.background = d
        b.stateListAnimator = null
        val p = dp(c, 12f).toInt()
        b.setPadding(p, p, p, p)
        b.setOnClickListener { onClick() }
        return b
    }

    fun label(c: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val v = TextView(c)
        v.text = s
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return v
    }

    fun round(color: Int, r: Float): GradientDrawable {
        val d = GradientDrawable(); d.cornerRadius = r; d.setColor(color); return d
    }

    fun center(v: TextView): TextView { v.gravity = Gravity.CENTER; return v }
    fun transparent() = Color.TRANSPARENT
}
