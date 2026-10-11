package com.construct.messenger.veil

/**
 * Pure front-selection and floor rules, so they are tested without a keystore or the network.
 *
 * Two jobs, both stateless here (the caller holds the mutable failure counts and the stores):
 *  - **order** the learned fronts for dialling — fewest in-session failures first, newest as the
 *    tie-break — so a blocked front is tried last, not first, yet never dropped (add-only; the pin
 *    stays, so eviction is never censor-triggerable). The failure map is in-memory and reset on a
 *    network path change; nothing about "front X is dead on network Y" is persisted
 *    (`decisions/no-client-side-network-learning.md`).
 *  - keep a **floor** of usable fronts: how many distinct fronts hold a live capability and are not
 *    the one just seen fail. Below the floor the caller tops up by asking a working front for its
 *    signed alternates.
 *
 * **Canon:** iOS `RelayPool` (failure-ordered selection) + `VeilFrontFloorMaintainer`
 * (usable count / floor), decision `veil-front-topup-keep-two-usable.md`.
 */
object VeilFrontPool {

    /** Target number of usable fronts to keep on hand. iOS `VeilFrontFloorMaintainer.floor`. */
    const val FLOOR = 2

    /** Learned fronts ordered for dialling: fewest failures first, then newest. Stable + pure. */
    fun order(fronts: List<LearnedFront>, failures: Map<String, Int>): List<LearnedFront> =
        fronts.sortedWith(
            compareBy<LearnedFront> { failures[it.address] ?: 0 }
                .thenByDescending { it.learnedAtMs },
        )

    /**
     * Number of distinct usable fronts among [addresses]. A front is usable when it holds a live
     * (non-expired) capability ([hasLiveCap]) and is not in [excluding] — used to drop a front we
     * just saw fail, so a capped-but-blocked front cannot mask the shortage.
     */
    fun usableCount(
        addresses: List<String>,
        excluding: Set<String> = emptySet(),
        hasLiveCap: (String) -> Boolean,
    ): Int {
        val seen = HashSet<String>()
        var count = 0
        for (address in addresses) {
            if (address.isNotEmpty() && address !in excluding && seen.add(address) && hasLiveCap(address)) count++
        }
        return count
    }

    fun isBelowFloor(usable: Int): Boolean = usable < FLOOR
}
