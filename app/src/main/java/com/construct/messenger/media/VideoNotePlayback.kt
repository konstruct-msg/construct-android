package com.construct.messenger.media

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * The one video note playing with sound, expanded in place in the transcript.
 * `decisions/video-notes-are-uncropped-and-expand.md` (revised 2026-10-05: a tap expands the note
 * inside the chat rather than opening it full screen).
 *
 * One at a time, and one sound at a time ([SoundFocus]): expanding a note stops a voice message,
 * and a voice message collapses the note. The bubble decides nothing — it shows what [state] says
 * and forwards taps. **Canon:** iOS `VideoNotePlayback`.
 */
@Singleton
class VideoNotePlayback @Inject constructor(
    private val engine: Engine,
    private val focus: SoundFocus,
) {
    data class Key(val messageId: String, val itemIndex: Int = 0)

    data class State(
        val expanded: Key? = null,
        val paused: Boolean = false,
        /** 0…1 of the note. */
        val progress: Float = 0f,
        val rate: Float = 1f,
    )

    /** What plays a note: ExoPlayer in the app, a fake in tests. */
    interface Engine {
        fun open(data: ByteArray, key: Key, rate: Float, onProgress: (Float) -> Unit, onEnd: () -> Unit): Session
    }

    interface Session {
        fun pause()
        fun resume(rate: Float)
        fun setRate(rate: Float)
        fun attach(view: TextureView)
        fun detach(view: TextureView)
        fun release()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var session: Session? = null

    /** A tap on the note: expand and play it, or pause / resume the one already expanded. */
    fun tap(key: Key, data: ByteArray) {
        val now = _state.value
        val s = session
        if (now.expanded != key || s == null) {
            expand(key, data)
            return
        }
        if (now.paused) {
            s.resume(now.rate)
            _state.value = now.copy(paused = false)
        } else {
            s.pause()
            _state.value = now.copy(paused = true)
        }
    }

    fun cycleRate() {
        val now = _state.value
        val next = RATES[(RATES.indexOf(now.rate) + 1).mod(RATES.size)]
        session?.setRate(next)
        _state.value = now.copy(rate = next)
    }

    /**
     * Back to the muted loop. Called when the note ends, scrolls away, the chat closes, or
     * something else starts playing. The next note starts at normal speed.
     */
    fun collapse() {
        if (_state.value.expanded == null && session == null) return
        session?.release()
        session = null
        _state.value = State()
        focus.release(this)
    }

    fun collapse(ifShowing: Key) {
        if (_state.value.expanded == ifShowing) collapse()
    }

    /** The expanded card's surface. */
    fun attach(view: TextureView) = session?.attach(view)

    fun detach(view: TextureView) = session?.detach(view)

    private fun expand(key: Key, data: ByteArray) {
        collapse()
        focus.claim(this) { collapse() }
        val rate = 1f
        session = engine.open(
            data, key, rate,
            onProgress = { p -> if (_state.value.expanded == key) _state.value = _state.value.copy(progress = p) },
            onEnd = { collapse(ifShowing = key) },
        )
        _state.value = State(expanded = key, rate = rate)
    }

    companion object {
        /** The speeds a tap on the speed chip cycles through, as for voice on iOS. */
        val RATES = listOf(1f, 1.5f, 2f)

        /** "1×", "1.5×", "2×". */
        fun rateLabel(rate: Float): String =
            if (rate == Math.round(rate).toFloat()) "${rate.toInt()}×" else "%.1f×".format(java.util.Locale.ROOT, rate)
    }
}

/**
 * ExoPlayer from memory, as every video here: the decrypted note is never written. With sound, and
 * unlike the muted loop it asks for audio focus — this one is meant to be heard.
 */
@OptIn(UnstableApi::class)
class ExoVideoNoteEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : VideoNotePlayback.Engine {
    override fun open(
        data: ByteArray,
        key: VideoNotePlayback.Key,
        rate: Float,
        onProgress: (Float) -> Unit,
        onEnd: () -> Unit,
    ): VideoNotePlayback.Session {
        val player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setMediaSource(
                ProgressiveMediaSource.Factory { ByteArrayDataSource(data) }
                    .createMediaSource(androidx.media3.common.MediaItem.fromUri("data://${key.messageId}/${key.itemIndex}")),
            )
            playbackParameters = PlaybackParameters(rate)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onEnd()
                }
            })
            prepare()
            playWhenReady = true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ticker: Job = scope.launch {
            while (isActive) {
                val d = player.duration
                if (d > 0) onProgress((player.currentPosition.toFloat() / d).coerceIn(0f, 1f))
                delay(TICK_MS)
            }
        }
        return object : VideoNotePlayback.Session {
            override fun pause() = player.pause()
            override fun resume(rate: Float) {
                player.playbackParameters = PlaybackParameters(rate)
                player.play()
            }
            override fun setRate(rate: Float) {
                player.playbackParameters = PlaybackParameters(rate)
            }
            override fun attach(view: TextureView) = player.setVideoTextureView(view)
            override fun detach(view: TextureView) = player.clearVideoTextureView(view)
            override fun release() {
                ticker.cancel()
                player.release()
            }
        }
    }

    private companion object {
        const val TICK_MS = 100L
    }
}
