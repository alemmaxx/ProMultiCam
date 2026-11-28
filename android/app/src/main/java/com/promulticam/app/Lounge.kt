package com.promulticam.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* keeps the chat polite: rude, hateful or dirty words are stopped before they are sent (Malay and English, also with tricks like b4b1 or fuuuck) */
object Mod {
    private val WORDS = listOf(
        // Malay
        "babi", "bodoh", "bangang", "bengap", "bebal", "sial", "celaka", "puki", "pukimak", "kimak", "pantat", "kote", "butoh", "konek", "lancau", "lahanat", "laknat", "keparat",
        "jahanam", "bangsat", "sundal", "pelacur", "jalang", "lonte", "cibai", "pundek", "pondan", "mampus", "haramjadah", "anakharam", "keling", "bodo", "tolol", "goblok", "kontol", "memek", "ngentot", "anjing",
        // English
        "fuck", "fck", "fuk", "shit", "bitch", "bastard", "asshole", "dick", "pussy", "cunt", "slut", "whore", "retard", "nigger", "nigga", "faggot", "motherfucker", "dumbass", "porn", "sex", "nude", "rape",
        // harm
        "killyourself", "kys", "bunuhdiri", "akubunuh", "bunuhkau")
    private fun norm(s: String): String {
        val m = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i')
        val a = s.lowercase(Locale.ROOT).map { m[it] ?: it }.filter { it.isLetter() }.joinToString("")
        val sb = StringBuilder(); for (ch in a) if (sb.isEmpty() || sb.last() != ch) sb.append(ch); return sb.toString()   // fuuuck → fuck
    }
    /* true when the text has a bad word (looks inside joined words too) */
    fun bad(s: String): Boolean {
        val joined = norm(s); val words = s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}@$!]+")).map { norm(it) }
        return WORDS.any { w -> val k = norm(w); words.any { it == k } || (k.length >= 5 && joined.contains(k)) }
    }
    val msg get() = Ui.t("Please keep it kind. That message has a word that is not allowed.", "Sila guna bahasa yang baik. Mesej itu ada perkataan yang tidak dibenarkan.")
}

/* the user-name box: tells at once if a name is free or already taken */
object NameBox {
    fun show(c: AppCompatActivityLike, done: () -> Unit) {
        val a = c.act
        val e = Ui.input(a, Ui.t("Your name", "Nama anda"), Profile.name(a) ?: "")
        val st = Ui.label(a, "", 13f, Ui.SUB, true)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.px(a, 20), Ui.px(a, 8), Ui.px(a, 20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)); addView(st, Ui.lp(a, 6)) }
        val d = AlertDialog.Builder(a).setTitle(Ui.t("User name", "Nama pengguna")).setView(box).setPositiveButton("OK", null).setNegativeButton(Ui.t("Cancel", "Batal"), null).create()
        var okName = false; var seq = 0
        fun check() { val n = e.text.toString().trim(); val my = ++seq; okName = false; d.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            when { n.isEmpty() -> { st.text = ""; return }; n.length > 30 -> { st.setTextColor(0xFFE5483B.toInt()); st.text = Ui.t("✗ Up to 30 letters", "✗ Maksimum 30 aksara"); return }
                Mod.bad(n) -> { st.setTextColor(0xFFE5483B.toInt()); st.text = Ui.t("✗ Not a polite name", "✗ Nama tidak sopan"); return } }
            st.setTextColor(Ui.SUB); st.text = Ui.t("Checking…", "Menyemak…")
            Thread { val free = Profile.available(n); a.runOnUiThread { if (my != seq) return@runOnUiThread
                when (free) { true -> { okName = true; st.setTextColor(0xFF3DDC97.toInt()); st.text = Ui.t("✓ Available", "✓ Boleh digunakan"); d.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true }
                    false -> { st.setTextColor(0xFFE5483B.toInt()); st.text = Ui.t("✗ Already taken — try another", "✗ Sudah digunakan orang lain — cuba nama lain") }
                    else -> { st.setTextColor(0xFFE5483B.toInt()); st.text = Ui.t("No internet connection", "Tiada sambungan internet") } } } }.start() }
        e.addTextChangedListener(object : android.text.TextWatcher { override fun afterTextChanged(x: android.text.Editable?) { a.window.decorView.removeCallbacks(null); e.removeCallbacks(null); e.postDelayed({ check() }, 450) }
            override fun beforeTextChanged(x: CharSequence?, p1: Int, p2: Int, p3: Int) {}; override fun onTextChanged(x: CharSequence?, p1: Int, p2: Int, p3: Int) {} })
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!okName) return@setOnClickListener
            val n = e.text.toString(); Thread { val err = Profile.claim(a, n); a.runOnUiThread { if (err == null) { d.dismiss(); done() } else { st.setTextColor(0xFFE5483B.toInt()); st.text = Profile.errText(err) } } }.start() }
            check() }
        d.show()
    }
}
class AppCompatActivityLike(val act: androidx.appcompat.app.AppCompatActivity)

/* the community chat: everyone signed in can talk; kind words only */
class LoungeActivity : PageActivity() {
    override val title0 get() = Ui.t("Community chat", "Sembang komuniti")
    private var alive = true; private var lastKey = ""
    private lateinit var inp: android.widget.EditText; private lateinit var sv: ScrollView
    override fun build() {
        body.parent?.let { sv = it as ScrollView }
        body.addView(Ui.hint(this, Ui.t("Share ideas, tips and good things. Be kind — rude words are blocked.", "Kongsi idea, tip dan perkara baik. Berbudi bahasa — perkataan kasar disekat.")))
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
        inp = Ui.input(this, Ui.t("Say something nice…", "Tulis sesuatu yang baik…")); inp.maxLines = 4
        row.addView(inp, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.btn(this, Ui.t("Send", "Hantar"), true) { send() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d / 2 })
        root.addView(row, Ui.lp(this, 6))
        if (Profile.name(this) == null) Thread { if (Profile.load(this) == null) main.post { NameBox.show(AppCompatActivityLike(this)) {} } }.start()
        poll(true)
    }
    private fun send() {
        val v = inp.text.toString().trim().take(500); if (v.isEmpty()) return
        if (Mod.bad(v)) { AlertDialog.Builder(this).setMessage(Mod.msg).setPositiveButton("OK", null).show(); return }
        val un = Profile.name(this) ?: run { NameBox.show(AppCompatActivityLike(this)) { send() }; return }
        inp.setText("")
        Thread { val ok = try { Fs.write("lounge/" + Fs.newId(), mapOf("uid" to Auth.uid, "user" to un, "text" to v), now = listOf("at"), merge = false) } catch (e: Exception) { false }
            main.post { if (!ok) { inp.setText(v); Toast(Ui.t("Not sent. Try again.", "Tidak dihantar. Cuba lagi.")) } else poll(false) } }.start()
    }
    private fun Toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private fun poll(again: Boolean) {
        Thread { val l = try { Fs.list("lounge", "at", desc = true, limit = 120).reversed() } catch (e: Exception) { null }
            main.post { if (!alive) return@post
                if (l != null) { val key = l.joinToString { it.id }; if (key != lastKey) { lastKey = key; show(l) } }
                if (again) main.postDelayed({ if (alive) poll(true) }, 4000) } }.start()
    }
    private fun show(l: List<Fs.Doc>) {
        while (body.childCount > 1) body.removeViewAt(1)
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()); val me = Auth.uid
        l.forEach { m ->
            if (Mod.bad(m.s("text"))) return@forEach   // just in case: never show bad words
            val mine = m.s("uid") == me
            val wrap = LinearLayout(this); wrap.orientation = LinearLayout.VERTICAL; wrap.gravity = if (mine) Gravity.END else Gravity.START
            val b = LinearLayout(this); b.orientation = LinearLayout.VERTICAL; b.setPadding(d, d * 2 / 3, d, d * 2 / 3)
            b.background = Ui.round(if (mine) 0xFF3A3020.toInt() else 0xFF22201C.toInt(), Ui.dp(this, 16f), if (mine) Ui.GOLD else 0, 1)
            if (!mine) b.addView(Ui.label(this, "@" + m.s("user"), 12f, Ui.GOLD, true))
            b.addView(Ui.label(this, m.s("text"), 15f, Ui.TEXT))
            val at = m.t("at")
            if (at > 0) b.addView(Ui.label(this, fmt.format(Date(at)), 10f, Ui.SUB), Ui.lp(this, 2))
            wrap.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (mine) leftMargin = Ui.px(this@LoungeActivity, 48) else rightMargin = Ui.px(this@LoungeActivity, 48) })
            if (mine || Auth.isAdmin) b.setOnLongClickListener { AlertDialog.Builder(this).setMessage(Ui.t("Delete this message?", "Padam mesej ini?"))
                .setPositiveButton(Ui.t("Delete", "Padam")) { _, _ -> Thread { Fs.delete("lounge/" + m.id); main.post { poll(false) } }.start() }.setNegativeButton(Ui.t("Cancel", "Batal"), null).show(); true }
            body.addView(wrap, Ui.lp(this, 8))
        }
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
    override fun onDestroy() { alive = false; super.onDestroy() }
}
