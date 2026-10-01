package com.construct.messenger.calls

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import shared.proto.signaling.v1.Webrtc.WebRTCSignal

/**
 * Call signals in, after the core opened them (`CfeAction.CallSignalDecrypted`). **Canon:** iOS
 * `SessionActionExecutor` → `CallManager.handleCallSignalProto`. The call machine (C2 step 3)
 * collects [signals]; until it exists they are logged and dropped, which is what Android did
 * before, so nothing changes for a caller.
 */
@Singleton
class CallSignalInbox @Inject constructor() {

    /** [accountId] is who called, [deviceId] the device the session is with — what candidates open on. */
    data class Incoming(val accountId: String, val deviceId: String, val signal: WebRTCSignal)

    private val _signals = MutableSharedFlow<Incoming>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val signals: SharedFlow<Incoming> = _signals

    fun deliver(incoming: Incoming) {
        _signals.tryEmit(incoming)
    }

    companion object {
        /**
         * Who may make this phone ring: a contact, not blocked (iOS `ContactPolicy.isCallableContact`
         * and the blocked check in `handleCallSignalProto`). A signal from anyone else is dropped
         * before anything reads it.
         */
        fun admits(isContact: Boolean, isBlocked: Boolean): Boolean = isContact && !isBlocked
    }
}
