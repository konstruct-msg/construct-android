package com.construct.messenger.calls

import uniffi.construct_core.callRelayUrls

/**
 * Which ICE servers a call uses. Which urls is the core's rule (construct-rules
 * `calls::relay_urls`, construct-core 0.76.0), shared with iOS: every call goes through our TURN
 * relay (owner, 2026-10-16, for 1.0). A direct call hands each side the other's network address,
 * and an address names a person where the account does not.
 *
 * Until 0.76.0 this copy relayed only with VEIL On; with VEIL off it went direct, and its first
 * candidate — a private host address — failed the iPhone's relay-only check within a second
 * (2026-11-16, Android 724 → iOS 779). Now:
 *
 * - relay only: no host or server-reflexive candidates, so no direct UDP to the peer and neither
 *   side learns the other's address;
 * - TURN over TLS (`turns:`) with VEIL On when the server offers it: the TURN handshake, the
 *   username among it, and the media are inside TLS instead of in clear on 3478;
 * - no fallback to STUN when TURN credentials could not be had: that fallback is a direct call.
 *   The call fails instead, which the person can see; a direct call they could not.
 *
 * What stays visible: a TLS connection to the TURN host. Hiding that needs TURN behind a front.
 */
data class CallIcePlan(val servers: List<Server>, val relayOnly: Boolean) {

    data class Server(val urls: List<String>, val username: String?, val credential: String?)

    companion object {
        /** Null when the call must not be placed: no TURN to relay through. */
        fun make(turnUrls: List<String>, username: String, credential: String, veilOn: Boolean): CallIcePlan? {
            val urls = callRelayUrls(turnUrls, veilOn) ?: return null
            return CallIcePlan(listOf(Server(urls, username, credential)), relayOnly = true)
        }
    }
}

/** No TURN to relay through ([CallIcePlan]): the call is not placed. */
class NoRelay : IllegalStateException("no TURN credentials — not placing a direct call")
