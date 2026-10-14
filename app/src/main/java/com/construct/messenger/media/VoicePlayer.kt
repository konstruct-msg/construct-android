package com.construct.messenger.media

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.construct.messenger.diagnostics.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One voice note playing at a time. **Canon:** iOS `AudioPlayerService` — starting another stops
 * the first; tapping the one playing pauses it. A video note playing in place folds back when a
 * voice note starts ([SoundFocus]).
 *
 * Plays from memory: the decrypted note is handed over as bytes and never written to disk.
 *
 * A music file sent as a file plays here too ([toggle] with `music`), as on iOS: one sound at a
 * time across both. It plays at its own speed; the voice notes' [rate] is not applied to it.
 *
 * Speed, seeking on the waveform and playing one note after another (TODO 149) are iOS
 * `cycleRate`, `seek` and `onTrackFinished`.
 */
@Singleton
class VoicePlayer @Inject constructor(private val focus: SoundFocus, private val settings: VoicePlaybackSettings) {
    data class Playing(val mediaId: String, val progress: Float, val durationMs: Long, val paused: Boolean)

    private val _state = MutableStateFlow<Playing?>(null)
    val state: StateFlow<Playing?> = _state.asStateFlow()

    /** The voice notes' speed, kept across notes and launches. */
    val rate: StateFlow<Float> get() = settings.rate

    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A track that played to its end by itself — not one stopped or replaced. */
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var music = false

    /** The speed set on [player]; a new one plays at 1× until told otherwise. */
    private var applied = 1f

    fun toggle(mediaId: String, audio: ByteArray, music: Boolean = false) {
        val current = _state.value
        val p = player
        if (current != null && current.mediaId == mediaId && p != null) {
            if (p.isPlaying) {
                p.pause()
                _state.value = current.copy(paused = true)
            } else {
                resume(p)
                _state.value = current.copy(paused = false)
            }
            return
        }
        stop()
        open(mediaId, audio, music)
    }

    /**
     * Move to [fraction] (0…1) of the track. The current one keeps playing or stays paused;
     * another one starts there. iOS `AudioPlayerService.seek`.
     */
    fun seek(mediaId: String, audio: ByteArray, fraction: Float, music: Boolean = false) {
        val f = fraction.coerceIn(0f, 1f)
        if (_state.value?.mediaId != mediaId || player == null) {
            stop()
            if (!open(mediaId, audio, music)) return
        }
        val p = player ?: return
        p.seekTo((f * p.duration).toInt())
        _state.value = _state.value?.copy(progress = f)
    }

    /** 1× → 1.25× → 1.5× → 2×, kept for every voice note after this one. */
    fun cycleRate() {
        val next = VoicePlaybackSettings.nextRate(settings.rate.value)
        settings.setRate(next)
        // Paused: the speed is applied on resume — setting it now would start playback.
        player?.takeIf { !music && it.isPlaying }?.let { setSpeed(it, next) }
    }

    fun stop() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _state.value = null
        focus.release(this)
    }

    private fun open(mediaId: String, audio: ByteArray, music: Boolean): Boolean {
        focus.claim(this) { stop() }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (music) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(BytesSource(audio))
            mp.prepare()
            mp.setOnCompletionListener {
                stop()
                _finished.tryEmit(mediaId)
            }
            this.music = music
            applied = 1f
            resume(mp)
        } catch (e: Exception) {
            Log.w(TAG, "voice note ${mediaId.take(8)}… does not play", e)
            mp.release()
            focus.release(this)
            return false
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
        return true
    }

    /** Start or resume at the speed this track plays at. */
    private fun resume(p: MediaPlayer) {
        val speed = if (music) 1f else settings.rate.value
        // A speed set on a paused player starts it, so this is also the start; `start` after it
        // is for when the speed is already right and nothing is set.
        if (speed != applied) setSpeed(p, speed)
        p.start()
    }

    private fun setSpeed(p: MediaPlayer, speed: Float) {
        p.playbackParams = PlaybackParams().setSpeed(speed)
        applied = speed
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
