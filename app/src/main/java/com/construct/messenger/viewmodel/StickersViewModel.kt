package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.StickersRepository
import com.construct.messenger.stickers.StickerPack
import com.construct.messenger.stickers.StickerReference
import com.construct.messenger.stickers.StickerStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Stickers for the chat: the picker's sections and each bubble's image. **Canon:** iOS
 * `StickerPickerView` / `StickerBubbleView` over `StickerService`.
 */
@HiltViewModel
class StickersViewModel @Inject constructor(
    private val stickers: StickersRepository,
) : ViewModel() {

    data class Picker(val recents: List<StickerReference>, val packs: List<StickerPack>)

    /** Re-read whenever the store changes — a pack landing, a send, an install. */
    val picker: StateFlow<Picker> = stickers.generation
        .map { Picker(stickers.recents(), stickers.installed()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Picker(emptyList(), emptyList()))

    /** Bubbles reload on this: a pack that arrives turns its emoji into the picture. */
    val generation: StateFlow<Int> = stickers.generation

    fun file(ref: StickerReference): File? = stickers.file(ref)
    fun file(entry: StickerPack.Entry): File = stickers.file(entry)

    /**
     * A bubble whose pack is not here asks for it — the whole pack, once (iOS `ensurePresent`);
     * the emoji shows meanwhile, and stays if it never comes.
     */
    fun ensure(ref: StickerReference) {
        viewModelScope.launch { stickers.ensurePresent(ref.packId) }
    }

    sealed interface Catalog {
        data object Loading : Catalog
        data object Unavailable : Catalog
        data class Loaded(val packs: List<StickerStore.CatalogEntry>) : Catalog
    }

    private val _catalog = MutableStateFlow<Catalog>(Catalog.Loading)
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()
    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installing: StateFlow<Set<String>> = _installing.asStateFlow()
    private val _installFailed = MutableStateFlow<Set<String>>(emptySet())
    val installFailed: StateFlow<Set<String>> = _installFailed.asStateFlow()

    /** "More packs": what exists and is not installed yet. */
    fun loadCatalog() {
        viewModelScope.launch {
            _catalog.value = try {
                Catalog.Loaded(stickers.catalog())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                Catalog.Unavailable
            }
        }
    }

    fun install(entry: StickerStore.CatalogEntry) {
        if (entry.hex in _installing.value) return
        _installing.value += entry.hex
        _installFailed.value -= entry.hex
        viewModelScope.launch {
            if (!stickers.install(entry.packId)) _installFailed.value += entry.hex
            _installing.value -= entry.hex
        }
    }

    fun uninstall(pack: StickerPack) = stickers.uninstall(pack.hex)

    suspend fun cover(sha256: ByteArray): ByteArray? = stickers.cover(sha256)
}
