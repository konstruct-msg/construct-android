package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.model.CallStartError
import com.construct.messenger.data.model.CallUi
import com.construct.messenger.data.repository.CallsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The call screen, the mini bar and the call buttons in a chat and a profile. */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val calls: CallsRepository,
) : ViewModel() {
    val call: StateFlow<CallUi?> = calls.call
    val startError: StateFlow<CallStartError?> = calls.startError

    /** The microphone was refused — said here, since the state machine never saw the call. */
    private val _micRefused = MutableStateFlow(false)
    val micRefused: StateFlow<Boolean> = _micRefused.asStateFlow()

    /** Only with no call on — iOS `canStartCall`. */
    fun canCall(): Boolean = call.value == null

    fun start(peerId: String) = calls.start(peerId)
    fun answer() = calls.answer()
    fun end() = calls.end()
    fun toggleMute() = calls.setMuted(!(call.value?.muted ?: false))
    fun toggleSpeaker() = calls.setSpeaker(!(call.value?.speaker ?: false))
    fun dismissEnded() = calls.dismissEnded()

    fun microphoneRefused() {
        _micRefused.value = true
    }

    fun clearErrors() {
        _micRefused.value = false
        calls.clearStartError()
    }
}
