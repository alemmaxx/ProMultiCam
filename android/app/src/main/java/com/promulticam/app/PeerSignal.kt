package com.promulticam.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* Talks to the same connection server as the web app (PeerJS cloud), so native and web phones can join each other */
class PeerSignal(wantId: String?, private val l: Listener) {
    interface Listener {
        fun onOpen(id: String)
        fun onIdTaken() {}
        fun onMsg(type: String, src: String, payload: JSONObject)
        fun onClosed() {}
    }
    val id: String = wantId ?: ("pmcn-" + rnd(14))
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    @Volatile var open = false; private set
    private var closed = false
    private val beat = object : Runnable { override fun run() { if (closed) return; ws?.send("{\"type\":\"HEARTBEAT\"}"); main.postDelayed(this, 5000) } }

    fun connect() {
        val url = "wss://0.peerjs.com:443/peerjs?key=peerjs&id=$id&token=${rnd(12)}&version=1.5.4"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                val type = j.optString("type")
                main.post {
                    when (type) {
                        "OPEN" -> { open = true; l.onOpen(id) }
                        "ID-TAKEN" -> l.onIdTaken()
                        "HEARTBEAT", "ERROR" -> {}
                        else -> l.onMsg(type, j.optString("src"), j.optJSONObject("payload") ?: JSONObject())
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { open = false; main.post { if (!closed) l.onClosed() } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { open = false; main.post { if (!closed) l.onClosed() } }
        })
        main.removeCallbacks(beat); main.postDelayed(beat, 5000)
    }

    fun send(type: String, dst: String, payload: JSONObject) {
        val j = JSONObject().put("type", type).put("dst", dst).put("payload", payload)
        ws?.send(j.toString())
    }

    fun close() { closed = true; main.removeCallbacks(beat); try { ws?.close(1000, "bye") } catch (e: Exception) {}; ws = null }

    companion object {
        private const val A = "abcdefghijklmnopqrstuvwxyz0123456789"
        fun rnd(n: Int) = (1..n).map { A[(Math.random() * A.length).toInt()] }.joinToString("")
    }
}
