package com.construct.messenger.media

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps a music file playing with the app in the background, and puts it in the shade and on the
 * lock screen (TODO 151, iOS `audio` background mode). Runs only while a music file is loaded:
 * [ExoVoiceEngine] starts it by connecting to it and stops it when the track goes. A voice note
 * never comes here.
 *
 * Not exported: the system's controls reach the session through the notification's platform
 * token, and no other app has any business binding to it. Only this app's own controller does.
 */
class MusicPlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.packageName == packageName }

    override fun onDestroy() {
        // The session belongs to the track ([ExoVoiceEngine]); it outlives a service the system stopped.
        session?.let(::removeSession)
        super.onDestroy()
    }

    companion object {
        /** The music file loaded now; null with none. Set and cleared by [ExoVoiceEngine]. */
        @Volatile
        var session: MediaSession? = null
    }
}
