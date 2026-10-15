package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.media.ChatOnScreen
import com.construct.messenger.media.PlaybackSource
import com.construct.messenger.media.VoicePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the now-playing banner shows; null while it is down. */
data class NowPlayingUi(
    val source: PlaybackSource,
    /** The person the chat is with, as the chat names them. */
    val chatName: String,
    val paused: Boolean,
    val progress: Float,
    val elapsedMs: Long,
)

/**
 * The track that outlives its chat (TODO 151). **Canon:** iOS `NowPlayingBanner` — up on every
 * screen but the track's own chat, where the message's row is the control.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val player: VoicePlayer,
    private val chatOnScreen: ChatOnScreen,
    contactsRepository: ContactsRepository,
) : ViewModel() {
    val state: StateFlow<NowPlayingUi?> = combine(player.state, chatOnScreen.contactId, contactsRepository.contacts) { playing, onScreen, contacts ->
        val source = playing?.source
        if (playing == null || !ChatOnScreen.showsBanner(source, onScreen)) return@combine null
        source!!
        NowPlayingUi(
            source = source,
            chatName = chatPeerName(contacts.find { it.userId == source.contactId }, source.contactId),
            paused = playing.paused,
            progress = playing.progress,
            elapsedMs = (playing.progress * playing.durationMs).toLong(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun togglePlaying() {
        player.togglePlaying()
    }

    fun stop() = player.stop()

    /** The chat to open, its message brought into view once it has. */
    fun open(source: PlaybackSource): String {
        chatOnScreen.requestFocus(source)
        return source.contactId
    }
}
