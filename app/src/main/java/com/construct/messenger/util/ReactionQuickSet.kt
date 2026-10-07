package com.construct.messenger.util

/**
 * The emoji at the head of the message menu. They start as the popular set and become the user's
 * own: every reaction this device sends is counted, old uses fade, and an emoji used more lately
 * than the weakest in the row takes that one's place.
 *
 * **Canon:** iOS `ReactionQuickSet` — the same rule and the same numbers, so the row learns alike on
 * both platforms (TODO 117). The row is six here, not five: iOS shows it in a system palette, where
 * a sixth pushed the plus behind a scroll; this row is drawn by the app. The rule has to match, not
 * the size.
 *
 * Two properties are the point, and the tests pin both:
 * - **Gradual.** A one-off reaction does not enter the row; a few uses close together do.
 * - **Stable.** An entrant takes the evicted emoji's slot, and the others keep theirs: the thumb
 *   learns positions, not scores.
 *
 * Pure, like [ReactionRules]. The counts never leave this device.
 */
data class ReactionQuickSet(val slots: List<String>, val scores: Map<String, Double>) {

    /** The row after the user reacted with [emoji]. Taking a reaction off is not a use. */
    fun recording(emoji: String): ReactionQuickSet {
        val scores = scores.mapValues { it.value * DECAY }.toMutableMap()
        scores[emoji] = (scores[emoji] ?: 0.0) + 1
        val slots = slots.toMutableList()
        if (emoji !in slots) {
            // The first of the weakest gives way, so ties evict in a fixed order.
            val weakest = slots.indices.minBy { scores[slots[it]] ?: 0.0 }
            if ((scores[emoji] ?: 0.0) > (scores[slots[weakest]] ?: 0.0)) slots[weakest] = emoji
        }
        return ReactionQuickSet(slots, scores.filter { it.value >= FORGET_BELOW || it.key in slots })
    }

    /** A stored row that does not have [SIZE] distinct entries is not one this code wrote. */
    val isWellFormed: Boolean get() = slots.size == SIZE && slots.toSet().size == SIZE

    companion object {
        val DEFAULTS: List<String> = ReactionRules.QUICK_SET
        val SIZE: Int = DEFAULTS.size

        /** What every count is multiplied by when another reaction is recorded. */
        const val DECAY = 0.9

        /**
         * The first default's starting score; each later one starts [SEED_STEP] lower, so the last
         * is the first to give way. An emoji enters on its third use in a row: after two its 1.9 is
         * under the last default's 2.75 × 0.9², after three its 2.71 is over 2.75 × 0.9³.
         */
        const val SEED = 3.0
        const val SEED_STEP = 0.05

        /** Counts below this, for emoji not in the row, are forgotten. */
        const val FORGET_BELOW = 0.05

        val INITIAL = ReactionQuickSet(
            slots = DEFAULTS,
            scores = DEFAULTS.withIndex().associate { (i, emoji) -> emoji to SEED - i * SEED_STEP },
        )
    }
}
