package com.construct.messenger.calls

/**
 * Which ICE servers a call uses, and whether it may go direct. **Canon:** iOS `CallIcePlan`
 * (construct-ios `b57db811`).
 *
 * With VEIL On the person asked that nothing of theirs be seen on the network. A call cannot go
 * through the tunnel today, so the plan keeps it as quiet as it can be outside it (vault
 * `security/konstrukt-unobservability-map.md`, D8; `decisions/veil-on-never-goes-direct.md`):
 *
 * - relay only: no host or server-reflexive candidates, so no direct UDP to the peer and neither
 *   side learns the other's address;
 * - TURN over TLS (`turns:`) when the server offers it: the TURN handshake, the username among
 *   it, and the media are inside TLS instead of in clear on 3478;
 * - no fallback to our STUN when TURN credentials could not be had: that fallback is a direct
 *   call. The call fails instead, which the person can see; a direct call they could not.
 *
 * What stays visible: a TLS connection to the TURN host. Hiding that needs TURN behind a front.
 */
data class CallIcePlan(val servers: List<Server>, val relayOnly: Boolean) {

    data class Server(val urls: List<String>, val username: String?, val credential: String?)

    companion object {
        /** Ours only. TURN answers STUN binding too, so with credentials nothing else is needed. */
        const val OWN_STUN = "stun:ams.konstruct.cc:3478"

        /** Null when the call must not be placed: VEIL On and no TURN to relay through. */
        fun make(turnUrls: List<String>, username: String, credential: String, veilOn: Boolean): CallIcePlan? {
            if (!veilOn) {
                return if (turnUrls.isEmpty()) CallIcePlan(listOf(Server(listOf(OWN_STUN), null, null)), relayOnly = false)
                else CallIcePlan(listOf(Server(turnUrls, username, credential)), relayOnly = false)
            }
            if (turnUrls.isEmpty()) return null
            val overTls = turnUrls.filter { it.lowercase().startsWith("turns:") }
            return CallIcePlan(listOf(Server(overTls.ifEmpty { turnUrls }, username, credential)), relayOnly = true)
        }
    }
}

/** VEIL is On and there is no TURN to relay through ([CallIcePlan]): the call is not placed. */
class NoRelayWhileVeilOn : IllegalStateException("VEIL is on and no TURN credentials — not placing a direct call")
