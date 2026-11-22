package com.construct.messenger.viewmodel

import com.construct.messenger.data.local.ChatMediaItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.construct_core.LocalMediaKind

/** What a tab of the chat gallery shows (vault todo 327). **Canon:** iOS `ChatMediaTab`. */
enum class ChatMediaTab(val kinds: List<LocalMediaKind>) {
    /** Photos, videos and animations — the grid. */
    VISUAL(listOf(LocalMediaKind.PHOTO, LocalMediaKind.VIDEO, LocalMediaKind.ANIMATION)),

    /**
     * Documents and audio files — the list, one row per message. Voice notes and round videos get
     * a tab of their own later; they are not pictures to page through.
     */
    FILES(listOf(LocalMediaKind.FILE)),
}

/**
 * A chat's media, page by page, from the store's media index. **Canon:** iOS
 * `ChatMediaGalleryModel` — same tabs, same paging, same one row per message for files.
 */
class ChatMediaGallery(
    private val page: suspend (kinds: List<LocalMediaKind>, before: ChatMediaItem?, limit: Int) -> List<ChatMediaItem>,
    private val pageSize: Int = 90,
) {
    var tab: ChatMediaTab = ChatMediaTab.VISUAL
        private set

    /** What is loaded of the tab, newest first. */
    var items: List<ChatMediaItem> = emptyList()
        private set

    /** No page left: the last one came back short. */
    var isComplete: Boolean = false
        private set

    // A scroll can ask for the next page while one is being read; the second ask waits and then
    // reads after the first, not the same page again.
    private val reading = Mutex()

    /** Another tab: what was loaded is dropped and its first page read. False when it was this tab. */
    suspend fun select(tab: ChatMediaTab): Boolean = reading.withLock {
        if (tab == this.tab) return@withLock false
        this.tab = tab
        items = emptyList()
        isComplete = false
        readPage()
        true
    }

    /** The next page of the tab, after the last item held. */
    suspend fun loadMore() = reading.withLock { readPage() }

    /** Called as item [index] shows: the next page once the grid nears the end of what is held. */
    suspend fun loadMoreIfNeeded(index: Int): Boolean {
        if (isComplete || index < items.size - PREFETCH_DISTANCE) return false
        loadMore()
        return true
    }

    /**
     * The messages the items belong to, each once, newest first: the files tab is a row per
     * message, since a message of three files is one bubble.
     */
    val messageIds: List<String> get() = items.map { it.messageId }.distinct()

    private suspend fun readPage() {
        if (isComplete) return
        val next = page(tab.kinds, items.lastOrNull(), pageSize)
        items = items + next
        if (next.size < pageSize) isComplete = true
    }

    companion object {
        const val PREFETCH_DISTANCE = 12
    }
}
