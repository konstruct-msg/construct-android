package com.construct.messenger.stickers

/**
 * What a row in the sticker packs sheet offers. Pure, so the decision is tested apart from the
 * sheet. Same meaning as iOS `StickerPackRowAction`.
 */
enum class StickerPackRowAction {
    GET,

    /** An install is in flight: progress, no second tap. */
    INSTALLING,

    /** The last install failed. */
    RETRY,
    REMOVE;

    companion object {
        fun of(installed: Boolean, installing: Boolean, failed: Boolean): StickerPackRowAction = when {
            installed -> REMOVE
            installing -> INSTALLING
            failed -> RETRY
            else -> GET
        }
    }
}
