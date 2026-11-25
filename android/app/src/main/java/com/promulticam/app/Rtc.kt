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
        fun flip() { capturer.switchCamera(null); front = !front }
    }
    fun camera(ctx: Context, front: Boolean, w: Int, h: Int, id: String): Cam? {
        val en = Camera2Enumerator(ctx)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) == front } ?: en.deviceNames.firstOrNull() ?: return null
        val cap = en.createCapturer(name, null) ?: return null
        val helper = SurfaceTextureHelper.create("cam-$id", egl.eglBaseContext)
        val src = factory.createVideoSource(false)
        cap.initialize(helper, ctx.applicationContext, src.capturerObserver)
        cap.startCapture(w, h, 30)
        return Cam(cap, src, factory.createVideoTrack("v-$id", src), helper, front)
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
