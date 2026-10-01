package com.construct.messenger.data.model

/** A call as the screen shows it. Built by [com.construct.messenger.data.repository.CallsRepository]; no protocol type crosses into the UI. */
data class CallUi(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val incoming: Boolean,
    val phase: Phase,
    /** Set when [phase] is [Phase.ENDED]. */
    val ended: EndedAs? = null,
    val reconnecting: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** When the call was answered — the timer counts from here. */
    val activeSinceMs: Long? = null,
) {
    enum class Phase { INCOMING, CALLING, RINGING, CONNECTING, ACTIVE, ENDED }

    /** iOS `InCallView.statusText` on `.ended`. */
    enum class EndedAs { ENDED, DECLINED, BUSY, MISSED, FAILED }

    val isLive: Boolean get() = phase != Phase.ENDED
}

/** Why a call did not start. iOS `call_error_*`. */
enum class CallStartError { NOT_A_CONTACT, BUSY, SETUP_FAILED }
