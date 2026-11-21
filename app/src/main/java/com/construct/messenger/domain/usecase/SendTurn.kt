package com.construct.messenger.domain.usecase

/**
 * When a send already shown in the chat may leave (todo 321). The row is written and the media
 * uploaded first, so the bubble appears at once; only the send itself waits for [go] — for the
 * sends to the same person placed before it, and for a queued one among them to leave
 * (`com.construct.messenger.data.repository.ChatSendLanes`).
 */
fun interface SendTurn {
    suspend fun go(send: suspend () -> SendOutcome): SendOutcome

    companion object {
        /** No lane: the send leaves at once. What a caller outside the repository gets. */
        val NOW = SendTurn { it() }
    }
}
