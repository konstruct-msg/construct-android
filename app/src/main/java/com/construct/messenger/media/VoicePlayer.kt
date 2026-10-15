package com.construct.messenger.media

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
 * The message a loaded track belongs to — what the now-playing banner names and goes back to.
 * **Canon:** iOS `PlaybackSource`.
 */
data class PlaybackSource(
    val contactId: String,
    val messageId: String,
    /** A music file's name; null for a voice note. */
    val fileName: String? = null,
)

/**
 * One voice note or music file playing at a time. **Canon:** iOS `AudioPlayerService` — starting
 * another stops the first; tapping the one playing pauses it. A video note playing in place folds
 * back when a track starts ([SoundFocus]).
 *
 * Plays from memory: the decrypted audio is handed over as bytes and never written to disk.
 *
 * A music file plays at its own speed; the voice notes' [rate] is not applied to it. Only music
 * goes to the lock screen and the notification shade ([Track.title], TODO 151): a voice note is
 * part of a conversation, and nothing about the conversation goes there.
 *
 * Leaving the chat does not stop a track (iOS since `ed0b6266`); the banner controls it there.
 */
@Singleton
class VoicePlayer @Inject constructor(
    private val focus: SoundFocus,
    private val settings: VoicePlaybackSettings,
    private val engine: Engine,
) {
    data class Playing(
        val mediaId: String,
        val progress: Float,
        val durationMs: Long,
        val paused: Boolean,
        val source: PlaybackSource? = null,
    )

    /** What the engine is asked to play. [title] — a music file's name — puts it on the lock screen. */
    data class Track(val mediaId: String, val music: Boolean, val title: String? = null)

    /** What plays a track: ExoPlayer in the app, a fake in tests. */
    interface Engine {
        /** Starts playing at [rate]. [onEnd]: played to its end; [onError]: it cannot play. */
        fun open(audio: ByteArray, track: Track, rate: Float, onEnd: () -> Unit, onError: () -> Unit): Session
    }

    interface Session {
        val positionMs: Long
        /** 0 until known. */
        val durationMs: Long
        /** False when paused — here, or from the lock screen. */
        val isPlaying: Boolean
        fun play(rate: Float)
        fun pause()
        fun seekTo(positionMs: Long)
        fun setRate(rate: Float)
        fun release()
    }

    private val _state = MutableStateFlow<Playing?>(null)
    val state: StateFlow<Playing?> = _state.asStateFlow()

    /** The voice notes' speed, kept across notes and launches. */
    val rate: StateFlow<Float> get() = settings.rate

    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A track that played to its end by itself — not one stopped or replaced. */
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Session? = null
    private var ticker: Job? = null
    private var music = false

    /**
     * Play or pause [mediaId]; another track stops first. `music`: a file, at its own speed, on the
     * lock screen under [PlaybackSource.fileName].
     */
    fun toggle(mediaId: String, audio: ByteArray, music: Boolean = false, source: PlaybackSource? = null) {
        val current = _state.value
        if (current != null && current.mediaId == mediaId && session != null) {
            togglePlaying()
            return
        }
        stop()
        open(mediaId, audio, music, source)
    }

    /**
     * Move to [fraction] (0…1) of the track. The current one keeps playing or stays paused;
     * another one starts there. iOS `AudioPlayerService.seek`.
     */
    fun seek(mediaId: String, audio: ByteArray, fraction: Float, music: Boolean = false, source: PlaybackSource? = null) {
        val f = fraction.coerceIn(0f, 1f)
        if (_state.value?.mediaId != mediaId || session == null) {
            stop()
            if (!open(mediaId, audio, music, source)) return
        }
        val s = session ?: return
        val duration = s.durationMs.takeIf { it > 0 } ?: _state.value?.durationMs ?: 0
        s.seekTo((f * duration).toLong())
        _state.value = _state.value?.copy(progress = f)
    }

    /** Pause or resume what is loaded — the banner's button. False when nothing is. */
    fun togglePlaying(): Boolean {
        val s = session ?: return false
        val current = _state.value ?: return false
        if (s.isPlaying) {
            s.pause()
            _state.value = current.copy(paused = true)
        } else {
            focus.claim(this) { stop() }
            s.play(speed())
            _state.value = current.copy(paused = false)
        }
        return true
    }

    /** 1× → 1.25× → 1.5× → 2×, kept for every voice note after this one. */
    fun cycleRate() {
        val next = VoicePlaybackSettings.nextRate(settings.rate.value)
        settings.setRate(next)
        if (!music) session?.setRate(next)
    }

    fun stop() {
        ticker?.cancel()
        session?.release()
        session = null
        _state.value = null
        focus.release(this)
    }

    private fun speed() = if (music) 1f else settings.rate.value

    private fun open(mediaId: String, audio: ByteArray, music: Boolean, source: PlaybackSource?): Boolean {
        focus.claim(this) { stop() }
        this.music = music
        val track = Track(mediaId, music, title = source?.fileName?.takeIf { music })
        val s = try {
            engine.open(
                audio,
                track,
                speed(),
                onEnd = {
                    if (_state.value?.mediaId == mediaId) {
                        stop()
                        _finished.tryEmit(mediaId)
                    }
                },
                onError = {
                    Log.w(TAG, "track ${mediaId.take(8)}… does not play")
                    if (_state.value?.mediaId == mediaId) stop()
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "track ${mediaId.take(8)}… does not play", e)
            focus.release(this)
            return false
        }
        session = s
        _state.value = Playing(mediaId, 0f, s.durationMs, paused = false, source = source)
        ticker = scope.launch {
            while (isActive) {
                val now = session ?: break
                val d = now.durationMs
                // The lock screen pauses and resumes the player directly; the state follows it.
                _state.value = _state.value?.copy(
                    progress = if (d > 0) (now.positionMs.toFloat() / d).coerceIn(0f, 1f) else 0f,
                    durationMs = d,
                    paused = !now.isPlaying,
                )
                delay(TICK_MS)
            }
        }
        return true
    }

    private companion object {
        const val TAG = "VoicePlayer"
        const val TICK_MS = 50L
    }
}
