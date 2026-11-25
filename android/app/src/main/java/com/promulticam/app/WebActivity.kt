package com.promulticam.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/* Plans, support, manual, about: the same pages as the web app, opened inside the app */
class WebActivity : AppCompatActivity() {
    private lateinit var wv: WebView
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wv = WebView(this)
        wv.settings.javaScriptEnabled = true; wv.settings.domStorageEnabled = true; wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.setBackgroundColor(Ui.BG)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.host?.endsWith("promulticam.web.app") == true || u.host?.endsWith("firebaseapp.com") == true || u.host?.contains("google") == true) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}   // payment pages, maps, phone links: outside
                return true
            }
        }
        val open = intent.getStringExtra("open") ?: ""
        val lang = if (Ui.ms) "ms" else "en"
        wv.loadUrl("https://promulticam.web.app/?open=$open&app=1&lang=$lang")
        setContentView(wv)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (wv.canGoBack()) wv.goBack() else super.onBackPressed() }
    companion object { fun open(a: AppCompatActivity, what: String) = a.startActivity(Intent(a, WebActivity::class.java).putExtra("open", what)) }
    @Suppress("unused") private fun ext(u: Uri) = u
}
