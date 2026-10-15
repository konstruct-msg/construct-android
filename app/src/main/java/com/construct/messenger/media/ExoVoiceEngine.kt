package com.construct.messenger.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/**
 * ExoPlayer from memory, as every video here: the decrypted audio is never written. It holds audio
 * focus (a call or another app's music pauses it) and pauses when headphones come out.
 *
 * A music file ([VoicePlayer.Track.title] set) also gets a [MediaSession] in [MusicPlaybackService]:
 * the notification and the lock screen show the file name without its extension — never the chat
 * or the sender — with play, pause and the position (iOS `NowPlaying`). A voice note gets none.
 */
@OptIn(UnstableApi::class)
class ExoVoiceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoicePlayer.Engine {
    override fun open(
        audio: ByteArray,
        track: VoicePlayer.Track,
        rate: Float,
        onEnd: () -> Unit,
        onError: () -> Unit,
    ): VoicePlayer.Session {
        val title = track.title?.let(::lockScreenTitle)
        val player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (track.music) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setHandleAudioBecomingNoisy(true)
            val item = androidx.media3.common.MediaItem.Builder()
                .setUri("data://${track.mediaId}")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
            setMediaSource(ProgressiveMediaSource.Factory { ByteArrayDataSource(audio) }.createMediaSource(item))
            playbackParameters = PlaybackParameters(rate)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) onEnd()
                }

                override fun onPlayerError(error: PlaybackException) = onError()
            })
            prepare()
            playWhenReady = true
        }
        val lockScreen = title?.let { LockScreen(player) }
        return object : VoicePlayer.Session {
            override val positionMs get() = player.currentPosition
            override val durationMs get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            // What was asked, not whether sound is coming out yet: buffering is not a pause.
            override val isPlaying get() = player.playWhenReady
            override fun play(rate: Float) {
                player.playbackParameters = PlaybackParameters(rate)
                player.play()
            }
            override fun pause() = player.pause()
            override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
            override fun setRate(rate: Float) {
                player.playbackParameters = PlaybackParameters(rate)
            }
            override fun release() {
                lockScreen?.release()
                player.release()
            }
        }
    }

    /**
     * The session the shade and the lock screen control, published through [MusicPlaybackService].
     * Connecting a controller of our own starts the service; Media3 keeps it in the foreground,
     * with its notification, while the track plays.
     */
    private inner class LockScreen(player: ExoPlayer) {
        private val session = MediaSession.Builder(context, player)
            .setId("music-${UUID.randomUUID()}")
            .setSessionActivity(openApp())
            .build()
        private val controller = run {
            MusicPlaybackService.session = session
            MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync()
        }

        fun release() {
            MediaController.releaseFuture(controller)
            if (MusicPlaybackService.session === session) MusicPlaybackService.session = null
            session.release()
            context.stopService(Intent(context, MusicPlaybackService::class.java))
        }
    }

    private fun openApp(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** A file name as a track title: without its extension. iOS `NowPlaying.info`. */
        fun lockScreenTitle(fileName: String): String =
            fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
    }
}
