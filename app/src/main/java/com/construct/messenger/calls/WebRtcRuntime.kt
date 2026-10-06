package com.construct.messenger.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC, set up once. **Canon:** iOS `WebRTCRuntime` / `WebRTCFactory` / `WebRTCFieldTrials`
 * (`Services/Calls/WebRTCSession.swift`).
 *
 * Field trials are read once, by `PeerConnectionFactory.initialize`, before any factory exists —
 * so they are passed there and nowhere else. Built on first use rather than at launch: the
 * library is 12 MB of native code that a person who never calls should not pay for at start-up.
 */
object WebRtcRuntime {

    /**
     * `WebRTC-EnableDtlsPqc` puts X25519MLKEM768 first among the DTLS key-exchange groups
     * (`rtc_base/ssl_stream_adapter.cc`); DTLS 1.3 is the default from M150. A call's media keys
     * then come from a hybrid exchange, and a recording of it does not open once X25519 does
     * (`PQC-4`). The DTLS fingerprint that authenticates the exchange travels in the SDP, inside
     * the Double Ratchet session.
     *
     * Upstream keeps it behind a trial, so an update can rename or drop it without a compile
     * error. `WebRtcFieldTrialsTest` reads the linked library for this key.
     */
    const val DTLS_PQC_KEY = "WebRTC-EnableDtlsPqc"
    const val FIELD_TRIALS = "$DTLS_PQC_KEY/Enabled/"

    @Volatile private var factory: PeerConnectionFactory? = null

    /**
     * The one GL context video is encoded, decoded and drawn with. Created with the factory: a
     * renderer on another context would copy every frame between the two.
     */
    val egl: EglBase by lazy { EglBase.create() }

    fun factory(context: Context): PeerConnectionFactory =
        factory ?: synchronized(this) {
            factory ?: build(context.applicationContext).also { factory = it }
        }

    private fun build(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials(FIELD_TRIALS)
                .createInitializationOptions(),
        )
        // The voice path: the phone's own echo canceller and noise suppressor where it has them
        // (WebRTC falls back to its software ones where it does not), recorded as a call.
        val audio = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .createAudioDeviceModule()
        // Video codecs even for an audio call. A factory without them aborts inside
        // setRemoteDescription on an offer that carries a video section — libc++ "front() called
        // on an empty vector", on WebRTC's worker thread, where nothing catches it. iOS puts one
        // into every offer since video calls (TODO 121); 0.16.0 and earlier crash on answering.
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, /* enableIntelVp8Encoder = */ true, /* enableH264HighProfile = */ true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }
}
