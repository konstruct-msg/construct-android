package com.construct.messenger.media

import javax.inject.Inject
import javax.inject.Singleton

/**
 * One sound at a time inside the app: a voice message and a video note playing in place stop each
 * other. Whoever starts claims it; the one holding it before is told to stop.
 *
 * **Canon:** iOS `AudioPlayerService.play` collapsing `VideoNotePlayback`, and
 * `VideoNotePlayback.expand` stopping `AudioPlayerService`. Android has two singletons that cannot
 * inject each other, so the rule lives here between them.
 */
@Singleton
class SoundFocus @Inject constructor() {
    private var holder: Any? = null
    private var onLost: (() -> Unit)? = null

    /** [owner] starts playing; the previous holder's [onLost] runs, once. */
    fun claim(owner: Any, onLost: () -> Unit) {
        if (holder === owner) {
            this.onLost = onLost
            return
        }
        val previous = this.onLost
        holder = owner
        this.onLost = onLost
        previous?.invoke()
    }

    /** [owner] stopped on its own; nothing is told. */
    fun release(owner: Any) {
        if (holder !== owner) return
        holder = null
        onLost = null
    }
}
