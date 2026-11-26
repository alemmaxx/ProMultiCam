package com.promulticam.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/* The web pages inside the app: manual, about, and the EDITOR (the same editor as the web).
   Videos go in and come back through a small bridge, so edited videos land in the app's Videos list. */
class WebActivity : AppCompatActivity() {
    private lateinit var wv: WebView
    private var out: FileOutputStream? = null
    private var outFile: File? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this); Auth.init(this)
        wv = WebView(this)
        wv.settings.javaScriptEnabled = true; wv.settings.domStorageEnabled = true; wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.databaseEnabled = true
        wv.setBackgroundColor(Ui.BG)
        wv.addJavascriptInterface(Bridge(), "PMCNative")
        wv.webChromeClient = object : WebChromeClient() { override fun onPermissionRequest(r: PermissionRequest) { r.grant(r.resources) } }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.host?.endsWith("promulticam.web.app") == true) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
                return true
            }
            /* the editor asks for /__native/<name>: give it the app's video file */
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val p = r.url.path ?: return null
                if (!p.startsWith("/__native/")) return null
                val f = File(Store.dir(this@WebActivity), File(Uri.decode(p.removePrefix("/__native/"))).name)
                return if (f.exists()) WebResourceResponse("video/mp4", null, FileInputStream(f)) else WebResourceResponse("text/plain", null, 404, "Not found", null, null)
            }
        }
        val open = intent.getStringExtra("open") ?: ""
        val f = intent.getStringExtra("f") ?: ""
        val lang = if (Ui.ms) "ms" else "en"
        wv.loadUrl("https://promulticam.web.app/?open=$open&app=1&lang=$lang" + if (f.isNotEmpty()) "&f=" + Uri.encode(f) else "")
        setContentView(wv)
    }

    inner class Bridge {
        @JavascriptInterface fun pro(): Boolean = Auth.isPro
        /* a finished video from the editor, in pieces (base64) */
        @JavascriptInterface fun put(name: String, b64: String, first: Boolean, last: Boolean) {
            try {
                if (first) { out?.close(); val clean = File(name).name.ifEmpty { "Edit.mp4" }; outFile = File(Store.dir(this@WebActivity), if (clean.endsWith(".mp4")) clean else "$clean.mp4"); out = FileOutputStream(outFile) }
                out?.write(Base64.decode(b64, Base64.DEFAULT))
                if (last) { out?.close(); out = null; runOnUiThread { Toast.makeText(this@WebActivity, Ui.t("✅ Saved in the app's Videos", "✅ Disimpan dalam Video app"), Toast.LENGTH_SHORT).show() } }
            } catch (e: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (wv.canGoBack()) wv.goBack() else super.onBackPressed() }
    override fun onDestroy() { try { out?.close() } catch (e: Exception) {}; wv.destroy(); super.onDestroy() }

    companion object {
        /* pages that need the web login (plans, support, device check) open in Chrome; reading pages and the editor open inside the app */
        fun open(a: AppCompatActivity, what: String, file: String? = null) {
            if (what in listOf("manual", "about", "edit", "media")) { a.startActivity(Intent(a, WebActivity::class.java).putExtra("open", what).putExtra("f", file ?: "")); return }
            val lang = if (Ui.ms) "ms" else "en"
            val url = if (what == "apk") "https://promulticam.web.app/apk.html" else "https://promulticam.web.app/?open=$what&lang=$lang"
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
        }
    }
}
