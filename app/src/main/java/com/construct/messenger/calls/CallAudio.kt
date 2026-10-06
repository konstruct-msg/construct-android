package com.construct.messenger.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's audio for the length of a call. **Canon:** iOS `CallAudioController` — one owner,
 * the system choosing the route.
 *
 * `MODE_IN_COMMUNICATION` is what turns on the voice path (echo cancelling, the earpiece by
 * default, a headset or Bluetooth when one is connected — the system decides, we do not force a
 * route). Focus is held so music pauses and a notification sound ducks instead of playing over
 * the call. Both go back when the call does; nothing else in the app touches either.
 */
@Singleton
class CallAudio @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL

    @Synchronized
    fun acquire() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus = request
        previousMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "call audio on: focus=${if (granted) "granted" else "refused"} mode=${manager.mode}")
    }

    @Synchronized
    fun release() {
        val request = focus ?: return
        focus = null
        manager.mode = previousMode
        manager.abandonAudioFocusRequest(request)
        Log.i(TAG, "call audio off: mode=${manager.mode}")
    }

    /**
     * Loudspeaker, when Telecom has no connection for this call to route through (it refused the
     * call, or the account could not be registered). With a connection, [CallTelecom] asks
     * Telecom, which owns the route of a self-managed call.
     */
    @Synchronized
    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(manager::setCommunicationDevice)
            } else {
                manager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = on
        }
    }

    /** The sound goes to the earpiece — when Telecom has no connection to say where it goes. */
    @Synchronized
    fun outputIsEarpiece(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.communicationDevice?.type.let { it == null || it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            @Suppress("DEPRECATION")
            !manager.isSpeakerphoneOn && !manager.isWiredHeadsetOn && !manager.isBluetoothScoOn
        }

    private var ringback: ToneGenerator? = null

    /**
     * The caller hears the far end ringing — the country's ringback cadence on the voice stream,
     * until the call is answered or ends. iOS `DialTonePlayer`; and iOS's lesson: it stops the
     * moment the call is answered, or it holds the audio path the call needs.
     */
    @Synchronized
    fun startRingback() {
        if (ringback != null) return
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME) }
            .onFailure { Log.w(TAG, "no ringback: ${it.message}") }
            .getOrNull()
            ?.also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
    }

    @Synchronized
    fun stopRingback() {
        ringback?.run {
            stopTone()
            release()
        }
        ringback = null
    }

    private companion object {
        const val TAG = "CallAudio"
        const val RINGBACK_VOLUME = 60
    }
}
