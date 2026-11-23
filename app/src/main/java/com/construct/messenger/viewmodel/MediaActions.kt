package com.construct.messenger.viewmodel

import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.data.repository.MessagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Open, share and save a media item, outside a chat: what the gallery and a note offer. */
class MediaActions(private val scope: CoroutineScope, private val repository: MessagesRepository) {
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

    suspend fun mediaBytes(item: MediaItem): ByteArray = repository.mediaBytes(item)

    fun openFile(item: MediaItem) = load(item) {
        _openFile.tryEmit(OpenFile(repository.openable(item, item.filename ?: "file"), item.mimeType))
    }

    /** [item]'s bytes to [use], with its row loading meanwhile; one that is gone says so. */
    fun withBytes(item: MediaItem, use: (ByteArray) -> Unit) = load(item) { use(repository.mediaBytes(item)) }

    fun saveToGallery(item: MediaItem) {
        scope.launch {
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
        scope.launch {
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

    private fun load(item: MediaItem, use: suspend () -> Unit) {
        val id = item.mediaId
        if (id in _fileLoading.value) return
        _fileLoading.value = _fileLoading.value + id
        scope.launch {
            try {
                use()
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
}
