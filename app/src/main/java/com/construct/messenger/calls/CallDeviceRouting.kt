package com.construct.messenger.calls

import shared.proto.signaling.v1.Webrtc.HangupReason

/**
 * Which of the other person's devices each call signal goes to and comes from (vault
 * `decisions/a-call-rings-every-device.md`). **Canon:** iOS `CallDeviceRouting`, rule for rule.
 *
 * - The offer goes to every device of theirs the core plans a send to and we hold a session with.
 * - Until someone answers, everything we send goes to all of them.
 * - The first answer picks the device; from then on we talk to it alone, and the others are told
 *   the call was taken elsewhere (`HANGUP_REASON_ACCEPTED_ELSEWHERE`).
 * - A device is known by the session the core opened its signal under (`CallSignalDecrypted`'s
 *   contact id), never by the `sender_device_id` the sender writes itself.
 *
 * Until 2026-10-21 every signal went to the peer's pinned device: a second device of the caller
 * never heard the answer, and a second device of the callee never rang.
 *
 * Device ids are `CryptoDeviceId`s throughout.
 */
object CallDeviceRouting {
    /**
     * Who a call signal is encrypted for. [chosen]: the device this call talks to — the one that
     * sent the offer (callee side) or answered first (caller side). [offered]: the devices our
     * offer went to. [pinned]: the device the peer's pinned key names, for a call that knows
     * neither.
     */
    fun targets(chosen: String?, offered: List<String>, pinned: String?): List<String> = when {
        chosen != null -> listOf(chosen)
        offered.isNotEmpty() -> offered
        else -> listOfNotNull(pinned)
    }

    /**
     * The devices an offer goes to: those the core planned a send to that we hold a session with,
     * in the plan's order. A device without a session would get nothing it can open; the pinned
     * device is the fallback when none has one.
     */
    fun offerDevices(planned: List<String>, hasSession: (String) -> Boolean, pinned: String?): List<String> {
        val usable = planned.distinct().filter(hasSession)
        return usable.ifEmpty { listOfNotNull(pinned) }
    }

    /** Before a device is chosen, any of the peer's devices may speak (an answer, a decline); after, only the chosen one. */
    fun admits(device: String, chosen: String?): Boolean = chosen == null || chosen == device

    sealed interface AnswerDisposition {
        /** Take this answer: [device] becomes the call's device, and [release] are told the call was taken elsewhere. */
        data class Accept(val device: String, val release: List<String>) : AnswerDisposition

        /** Someone else answered first: tell [device] the call was taken elsewhere. */
        data class ReleaseLate(val device: String) : AnswerDisposition
    }

    fun onAnswer(from: String, chosen: String?, offered: List<String>): AnswerDisposition =
        if (chosen != null && chosen != from) AnswerDisposition.ReleaseLate(from)
        else AnswerDisposition.Accept(from, offered.filter { it != from })

    sealed interface HangupDisposition {
        /** End the call, and tell [release] (the other offered devices) it was taken elsewhere. */
        data class End(val release: List<String>) : HangupDisposition

        /** Only this device is out (it is busy); the call keeps ringing on [remaining]. */
        data class DropDevice(val remaining: List<String>) : HangupDisposition

        /** Not from this call's device: ignore. */
        data object Ignore : HangupDisposition
    }

    /**
     * A hangup (or busy) from one of the peer's devices. Declining anywhere ends the call for every
     * device; busy on one only takes that one out until all are busy.
     */
    fun onHangup(from: String, reason: HangupReason, chosen: String?, offered: List<String>): HangupDisposition {
        if (!admits(from, chosen)) return HangupDisposition.Ignore
        val others = offered.filter { it != from }
        if (chosen == null && reason == HangupReason.HANGUP_REASON_BUSY && others.isNotEmpty()) {
            return HangupDisposition.DropDevice(others)
        }
        return HangupDisposition.End(if (chosen == null) others else emptyList())
    }
}
