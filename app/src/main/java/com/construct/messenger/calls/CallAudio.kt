package com.construct.messenger.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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

    private companion object {
        const val TAG = "CallAudio"
    }
}
