package com.promulticam.app

import android.content.Context
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/* One shared WebRTC engine for the whole app */
object Rtc {
    lateinit var egl: EglBase; private set
    lateinit var factory: PeerConnectionFactory; private set
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        egl = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        ready = true
    }

    fun rtcConfig(): PeerConnection.RTCConfiguration {
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        return PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
    }

    /* the phone camera as a WebRTC video track */
    class Cam(val capturer: CameraVideoCapturer, val source: VideoSource, val track: VideoTrack, val helper: SurfaceTextureHelper, var front: Boolean) {
        fun stop() { try { capturer.stopCapture() } catch (e: Exception) {}; capturer.dispose(); helper.dispose(); track.dispose(); source.dispose() }
        fun flip() { capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(f: Boolean) { lock30() }; override fun onCameraSwitchError(e: String?) {} }); front = !front }
        /* keep a steady 30 fps (the camera would slow to 15–20 fps in dim light); tried a few times while the camera starts */
        fun lock30() { val h = android.os.Handler(android.os.Looper.getMainLooper()); for (d in longArrayOf(1200, 3000, 6000)) h.postDelayed({ try { Focus.tap(capturer, 0.5f, 0.5f, front, true) } catch (e: Exception) {} }, d) }
    }
    /* steady 30 fps from a camera phone: keep the frame rate (drop sharpness first if the network is weak) and allow enough data */
    fun tune(s: org.webrtc.RtpSender?) {
        s ?: return
        try {
            val p = s.parameters
            p.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            p.encodings.forEach { e -> e.maxFramerate = 30; e.maxBitrateBps = 5_000_000; e.minBitrateBps = 1_200_000 }
            s.parameters = p
        } catch (e: Exception) {}
    }
    /* start the picture at a good quality instead of creeping up from a low one */
    fun fastStart(sdp: String): String {
        val nl = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(nl)
        val pts = lines.mapNotNull { Regex("^a=rtpmap:(\\d+) (VP8|VP9|H264)/90000").find(it)?.groupValues?.get(1) }.toSet()
        if (pts.isEmpty()) return sdp
        val withFmtp = lines.mapNotNull { Regex("^a=fmtp:(\\d+) ").find(it)?.groupValues?.get(1) }.toSet()
        val add = "x-google-start-bitrate=2500;x-google-min-bitrate=1200;x-google-max-bitrate=5000"
        val out = ArrayList<String>()
        for (ln in lines) {
            val f = Regex("^a=fmtp:(\\d+) ").find(ln)?.groupValues?.get(1)
            out.add(if (f != null && f in pts && !ln.contains("x-google-")) "$ln;$add" else ln)
            val r = Regex("^a=rtpmap:(\\d+) ").find(ln)?.groupValues?.get(1)
            if (r != null && r in pts && r !in withFmtp) out.add("a=fmtp:$r $add")   // e.g. VP8 has no fmtp line of its own
        }
        return out.joinToString(nl)
    }
    fun camera(ctx: Context, front: Boolean, w: Int, h: Int, id: String): Cam? {
        val en = Camera2Enumerator(ctx)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) == front } ?: en.deviceNames.firstOrNull() ?: return null
        val cap = en.createCapturer(name, null) ?: return null
        val helper = SurfaceTextureHelper.create("cam-$id", egl.eglBaseContext)
        val src = factory.createVideoSource(false)
        cap.initialize(helper, ctx.applicationContext, src.capturerObserver)
        cap.startCapture(w, h, 30)
        return Cam(cap, src, factory.createVideoTrack("v-$id", src), helper, front).also { it.lock30() }
    }

    /* microphone with no call-style processing: natural voice */
    fun mic(id: String): AudioTrack {
        val mc = MediaConstraints()
        for (k in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter", "googEchoCancellation2", "googNoiseSuppression2", "googAutoGainControl2"))
            mc.mandatory.add(MediaConstraints.KeyValuePair(k, "false"))
        return factory.createAudioTrack("a-$id", factory.createAudioSource(mc))
    }

    open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
