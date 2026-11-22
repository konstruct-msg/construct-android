package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.ChatMediaItem
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.data.repository.MessagesRepository
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.MediaWire
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The chat gallery (vault todo 327): [ChatMediaGallery]'s pages, each item matched to the media its
 * message carries, and the open / save / share the chat's viewer offers.
 */
@HiltViewModel
class ChatMediaGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messages: MessageStore,
    private val repository: MessagesRepository,
    keystoreManager: KeystoreManager,
) : ViewModel() {
    val contactId: String = requireNotNull(savedStateHandle.get<String>("contactId"))
    private val chatId: String? = keystoreManager.getUserId()?.let { ConversationId.direct(it, contactId) }

    /** A tile of the grid: the index entry and the item it is in its message. */
    data class Tile(val entry: ChatMediaItem, val media: MediaItem)

    /** A row of the file list: one message's files. */
    data class FileRow(val messageId: String, val timestampMs: Long, val files: List<MediaItem>)

    data class UiState(
        val tab: ChatMediaTab = ChatMediaTab.VISUAL,
        val tiles: List<Tile> = emptyList(),
        val files: List<FileRow> = emptyList(),
        val isComplete: Boolean = false,
    )

    private val gallery = ChatMediaGallery(page = { kinds, before, limit ->
        chatId?.let { messages.media(it, kinds, before, limit) } ?: emptyList()
    })
    private val records = mutableMapOf<String, MessageRecord?>()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { guarded { gallery.loadMore() } }
    }

    fun select(tab: ChatMediaTab) {
        viewModelScope.launch { guarded { gallery.select(tab) } }
    }

    /** Called as tile or row [index] shows. */
    fun shown(index: Int) {
        val itemIndex = when (gallery.tab) {
            ChatMediaTab.VISUAL -> index
            // A row is a message; its last item is how far the list has read.
            ChatMediaTab.FILES -> _ui.value.files.getOrNull(index)
                ?.let { row -> gallery.items.indexOfLast { it.messageId == row.messageId } } ?: return
        }
        viewModelScope.launch { guarded { gallery.loadMoreIfNeeded(itemIndex) } }
    }

    private suspend fun guarded(read: suspend () -> Unit) {
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "a page did not load", e)
        }
        publish()
    }

    private suspend fun publish() {
        val items = gallery.items
        val tiles = items.mapNotNull { entry -> mediaOf(entry)?.let { Tile(entry, it) } }
        val files = gallery.messageIds.mapNotNull { id ->
            val entries = items.filter { it.messageId == id }
            val found = entries.mapNotNull(::mediaOfCached)
            if (found.isEmpty()) null else FileRow(id, entries.first().timestampMs, found)
        }
        _ui.value = UiState(gallery.tab, tiles, files, gallery.isComplete)
    }

    private suspend fun mediaOf(entry: ChatMediaItem): MediaItem? {
        if (entry.messageId !in records) records[entry.messageId] = messages.get(entry.messageId)
        return mediaOfCached(entry)
    }

    private fun mediaOfCached(entry: ChatMediaItem): MediaItem? = mediaIn(records[entry.messageId], entry)

    // What the chat's viewer and file rows do, the same calls ChatViewModel makes.

    suspend fun mediaBytes(item: MediaItem): ByteArray = repository.mediaBytes(item)

    private val _fileLoading = MutableStateFlow<Set<String>>(emptySet())
    val fileLoading: StateFlow<Set<String>> = _fileLoading.asStateFlow()
    private val _fileUnavailable = MutableStateFlow<Set<String>>(emptySet())
    val fileUnavailable: StateFlow<Set<String>> = _fileUnavailable.asStateFlow()
    private val _openFile = MutableSharedFlow<OpenFile>(extraBufferCapacity = 1)
    val openFile: SharedFlow<OpenFile> = _openFile
    private val _shareFile = MutableSharedFlow<OpenFile>(extraBufferCapacity = 1)
    val shareFile: SharedFlow<OpenFile> = _shareFile
    private val _galleryResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val galleryResult: SharedFlow<Boolean> = _galleryResult

    fun openFile(item: MediaItem) {
        val id = item.mediaId
        if (id in _fileLoading.value) return
        _fileLoading.value = _fileLoading.value + id
        viewModelScope.launch {
            try {
                _openFile.tryEmit(OpenFile(repository.openable(item, item.filename ?: "file"), item.mimeType))
            } catch (e: MediaUnavailable) {
                _fileUnavailable.value = _fileUnavailable.value + id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network: a tap tries again.
            } finally {
                _fileLoading.value = _fileLoading.value - id
            }
        }
    }

    fun saveToGallery(item: MediaItem) {
        viewModelScope.launch {
            val saved = try {
                repository.saveToGallery(item)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _galleryResult.tryEmit(saved)
        }
    }

    fun share(item: MediaItem) {
        viewModelScope.launch {
            try {
                val name = item.filename?.takeIf { it.isNotBlank() } ?: if (item.isVideo) "video.mp4" else "photo.jpg"
                _shareFile.tryEmit(OpenFile(repository.openable(item, name), item.mimeType))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _galleryResult.tryEmit(false)
            }
        }
    }

    private companion object {
        const val TAG = "ChatMediaGallery"
    }
}

/**
 * The item [entry] names in [record], by media id: the decoder leaves out an item it cannot read
 * (`MediaWire.item`), so a position in the decoded album is not the index's `item`.
 */
internal fun mediaIn(record: MessageRecord?, entry: ChatMediaItem): MediaItem? {
    val album = record?.let { MediaWire.decode(it.mediaType, it.mediaPayload) } as? MessageMedia.Album ?: return null
    return album.items.firstOrNull { it.mediaId == entry.mediaId }
}
