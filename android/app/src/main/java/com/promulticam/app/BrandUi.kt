package com.promulticam.app

import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.promulticam.app.Ui.t

/* the brand settings box (title, price, contact, logo) */
object BrandUi {
    fun show(a: AppCompatActivity, brand: Brand, pickLogo: () -> Unit, saved: () -> Unit) {
        val d14 = Ui.dp(a, 14f).toInt()
        val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL; col.setPadding(d14 * 2, d14, d14 * 2, 0)
        val on = CheckBox(a); on.text = t("Brand in the video", "Jenama dalam video"); on.isChecked = brand.on; col.addView(on)
        fun field(hint: String, v: String): EditText { val e = EditText(a); e.hint = hint; e.setText(v); e.inputType = InputType.TYPE_CLASS_TEXT; col.addView(e); return e }
        val ti = field(t("Title", "Tajuk"), brand.title)
        val pr = field(t("Price / tagline", "Harga / slogan"), brand.price)
        val ph = field(t("Phone / contact", "Telefon / hubungi"), brand.phone)
        col.addView(Ui.btn(a, t("Choose logo", "Pilih logo")) { pickLogo() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d14 })
        col.addView(Ui.btn(a, t("Remove logo", "Buang logo")) { brand.logoFile.delete(); brand.refresh(); saved() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d14 / 2 })
        AlertDialog.Builder(a).setTitle(t("Brand", "Jenama")).setView(col)
            .setPositiveButton(t("Save", "Simpan")) { _, _ ->
                brand.on = on.isChecked; brand.title = ti.text.toString(); brand.price = pr.text.toString(); brand.phone = ph.text.toString(); brand.refresh(); saved()
            }.setNegativeButton(t("Cancel", "Batal"), null).show()
    }
}
