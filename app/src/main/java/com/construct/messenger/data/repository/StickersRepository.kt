package com.construct.messenger.data.repository

import com.construct.messenger.stickers.StickerPack
import com.construct.messenger.stickers.StickerReference
import com.construct.messenger.stickers.StickerStore
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** Sticker packs for the picker and the transcript. Implemented by [StickerStore]. */
interface StickersRepository {
    /** Changes whenever a pack lands or the picker's lists change. */
    val generation: StateFlow<Int>
    fun installed(): List<StickerPack>
    fun recents(): List<StickerReference>
    /** The image of [ref], or null while its pack is absent. */
    fun file(ref: StickerReference): File?
    fun file(entry: StickerPack.Entry): File
    /** Fetch [ref]'s pack, whole, if it is not here — what a bubble does for an unknown pack. */
    suspend fun ensurePresent(packId: ByteArray): StickerPack?
    suspend fun catalog(): List<StickerStore.CatalogEntry>
    suspend fun cover(sha256: ByteArray): ByteArray?
    suspend fun install(packId: ByteArray): Boolean
    fun uninstall(packHex: String)
}
