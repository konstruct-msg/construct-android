package com.construct.messenger.stickers

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Add stickers" opens every pack in one sheet; each row's button is a pure decision. */
class StickerPackRowActionTest {

    /** An installed pack offers Remove, whatever the catalog's flags say about it. */
    @Test
    fun anInstalledPackOffersRemove() {
        assertEquals(StickerPackRowAction.REMOVE, StickerPackRowAction.of(installed = true, installing = false, failed = false))
        assertEquals(StickerPackRowAction.REMOVE, StickerPackRowAction.of(installed = true, installing = true, failed = true))
    }

    @Test
    fun aCatalogPackOffersGet() {
        assertEquals(StickerPackRowAction.GET, StickerPackRowAction.of(installed = false, installing = false, failed = false))
    }

    /** An install in flight shows progress, even when the previous attempt failed. */
    @Test
    fun anInstallInFlightShowsProgress() {
        assertEquals(StickerPackRowAction.INSTALLING, StickerPackRowAction.of(installed = false, installing = true, failed = false))
        assertEquals(StickerPackRowAction.INSTALLING, StickerPackRowAction.of(installed = false, installing = true, failed = true))
    }

    @Test
    fun aFailedInstallOffersRetry() {
        assertEquals(StickerPackRowAction.RETRY, StickerPackRowAction.of(installed = false, installing = false, failed = true))
    }
}
