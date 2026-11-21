package com.construct.messenger.service

import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.deviceSetNamed
import uniffi.construct_core.deviceSetShouldAsk

/**
 * What the answers to a send say about the recipient's devices, and which named sets the key
 * server has already been asked about (todo 321, step 3; construct-docs
 * `decisions/the-send-answer-names-the-device-set.md`).
 *
 * A send plans from the devices this one knows ([SessionManager.knownPeerDevices]) and does not
 * ask the key server first: on a bad path that request held every message for up to 30 s. The
 * answer to each sealed copy names the recipient's active devices, and a set that differs from
 * the plan is the one reason to ask. Which set, and whether it is worth asking about, is the
 * core's rule (`device_set_named`, `device_set_should_ask`) — the one iOS calls since
 * construct-apple #516. **The set is the address, not a timer**: the same set is asked about once
 * per process, a changed one again.
 */
@Singleton
class DeviceSetAnswers @Inject constructor() {
    private val asked = HashMap<String, List<String>>()

    /**
     * The set [answers] name when it is a reason to ask the key server about [recipient]: it names
     * something, differs from [planned], and is not the set already asked about. Null otherwise.
     */
    @Synchronized
    fun toAsk(recipient: String, answers: List<List<String>>, planned: Collection<String>): List<String>? {
        val named = deviceSetNamed(answers)
        if (named.isEmpty() || named.toSet() == planned.toSet()) return null
        return named.takeIf { deviceSetShouldAsk(it, asked[recipient]) }
    }

    /** The key server answered about [named] for [recipient]: the same set is not asked about again. */
    @Synchronized
    fun asked(recipient: String, named: List<String>) {
        asked[recipient] = named
    }
}
