package com.promulticam.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/* One PeerJS connection (data or media) = one WebRTC peer connection, speaking the web app's protocol */
class PeerLink(
    private val sig: PeerSignal, val peer: String, val connectionId: String, val type: String, private val cb: Cb
) {
    interface Cb {
        fun onData(link: PeerLink, msg: Map<String, Any?>) {}
        fun onOpen(link: PeerLink) {}
        fun onTrack(link: PeerLink, track: MediaStreamTrack) {}
        fun onClosed(link: PeerLink) {}
    }
    private val main = Handler(Looper.getMainLooper())
    private var dc: DataChannel? = null
    private var remoteSet = false
    private val pending = ArrayList<IceCandidate>()
    @Volatile var closed = false; private set

    val pc: PeerConnection = Rtc.factory.createPeerConnection(Rtc.rtcConfig(), object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) main.post { close() }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) {
            val cand = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
            sig.send("CANDIDATE", peer, JSONObject().put("candidate", cand).put("type", type).put("connectionId", connectionId))
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel) { main.post { setupDc(d) } }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        override fun onTrack(t: RtpTransceiver) { val tr = t.receiver.track() ?: return; main.post { if (!closed) cb.onTrack(this@PeerLink, tr) } }
    })!!

    private fun setupDc(d: DataChannel) {
        dc = d
        d.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p: Long) {}
            override fun onStateChange() {
                val st = d.state()
                main.post { if (st == DataChannel.State.OPEN) cb.onOpen(this@PeerLink) else if (st == DataChannel.State.CLOSED) close() }
            }
            override fun onMessage(b: DataChannel.Buffer) {
                val a = ByteArray(b.data.remaining()); b.data.get(a)
                val v = try { if (b.binary) BinaryPack.unpack(a) else org.json.JSONObject(String(a)).let { j -> j.keys().asSequence().associateWith { j.opt(it) } } } catch (e: Exception) { null }
                @Suppress("UNCHECKED_CAST")
                if (v is Map<*, *>) main.post { cb.onData(this@PeerLink, v as Map<String, Any?>) }
            }
        })
        if (d.state() == DataChannel.State.OPEN) main.post { cb.onOpen(this) }
    }

    fun buffered(): Long = try { dc?.bufferedAmount() ?: 0L } catch (e: Exception) { 0L }
    fun send(msg: Map<String, Any?>): Boolean {
        val d = dc ?: return false
        if (d.state() != DataChannel.State.OPEN) return false
        return d.send(DataChannel.Buffer(ByteBuffer.wrap(BinaryPack.pack(msg)), true))
    }

    /* we start the connection (camera -> director) */
    fun offer(metadata: JSONObject?) {
        if (type == "data") setupDc(pc.createDataChannel(connectionId, DataChannel.Init().apply { ordered = true }))
        pc.createOffer(object : Rtc.Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                d ?: return
                pc.setLocalDescription(Rtc.Sdp(), d)
                val p = JSONObject().put("sdp", JSONObject().put("type", "offer").put("sdp", d.description))
                    .put("type", type).put("connectionId", connectionId).put("browser", "chrome")
                if (metadata != null) p.put("metadata", metadata)
                if (type == "data") p.put("label", connectionId).put("reliable", true).put("serialization", "binary")
                sig.send("OFFER", peer, p)
            }
        }, MediaConstraints())
    }

    /* the other side started it (director answers a camera) */
    fun answer(payload: JSONObject) {
        val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
        pc.setRemoteDescription(object : Rtc.Sdp() {
            override fun onSetSuccess() {
                main.post { remoteSet = true; pending.forEach { pc.addIceCandidate(it) }; pending.clear() }
                pc.createAnswer(object : Rtc.Sdp() {
                    override fun onCreateSuccess(d: SessionDescription?) {
                        d ?: return
                        pc.setLocalDescription(Rtc.Sdp(), d)
                        sig.send("ANSWER", peer, JSONObject().put("sdp", JSONObject().put("type", "answer").put("sdp", d.description))
                            .put("type", type).put("connectionId", connectionId).put("browser", "chrome"))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun gotAnswer(payload: JSONObject) {
        val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
        pc.setRemoteDescription(object : Rtc.Sdp() {
            override fun onSetSuccess() { main.post { remoteSet = true; pending.forEach { pc.addIceCandidate(it) }; pending.clear() } }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun gotCandidate(payload: JSONObject) {
        val c = payload.optJSONObject("candidate") ?: return
        val ic = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
        if (remoteSet) pc.addIceCandidate(ic) else pending.add(ic)
    }

    fun close() {
        if (closed) return
        closed = true
        try { dc?.close() } catch (e: Exception) {}
        try { pc.close() } catch (e: Exception) {}
        cb.onClosed(this)
    }

    companion object { fun newId(prefix: String) = prefix + PeerSignal.rnd(14) }
}
