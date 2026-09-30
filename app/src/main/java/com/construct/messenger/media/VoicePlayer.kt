package com.construct.messenger.media

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One voice note playing at a time. **Canon:** iOS `AudioPlayerService` — starting another stops
 * the first; tapping the one playing pauses it.
 *
 * Plays from memory: the decrypted note is handed over as bytes and never written to disk.
 */
@Singleton
class VoicePlayer @Inject constructor() {
    data class Playing(val mediaId: String, val progress: Float, val durationMs: Long, val paused: Boolean)

    private val _state = MutableStateFlow<Playing?>(null)
    val state: StateFlow<Playing?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    fun toggle(mediaId: String, audio: ByteArray) {
        val current = _state.value
        val p = player
        if (current != null && current.mediaId == mediaId && p != null) {
            if (p.isPlaying) {
                p.pause()
                _state.value = current.copy(paused = true)
            } else {
                p.start()
                _state.value = current.copy(paused = false)
            }
            return
        }
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(BytesSource(audio))
            mp.prepare()
            mp.setOnCompletionListener { stop() }
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "voice note ${mediaId.take(8)}… does not play", e)
            mp.release()
            return
        }
        player = mp
        _state.value = Playing(mediaId, 0f, mp.duration.toLong(), paused = false)
        ticker = scope.launch {
            while (isActive) {
                val now = player ?: break
                val d = now.duration.coerceAtLeast(1)
                _state.value = _state.value?.copy(progress = now.currentPosition.toFloat() / d)
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _state.value = null
    }

    private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }

    private companion object {
        const val TAG = "VoicePlayer"
        const val TICK_MS = 50L
    }
}
