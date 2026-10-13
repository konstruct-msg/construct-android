package com.construct.messenger.data.repository

import com.construct.messenger.data.model.ForwardItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content waiting for the chat it was forwarded to. That chat takes it when it opens — or at once,
 * if it is the one on screen — and sends it through its own send path, so a forward needs no
 * second sender beside the one every chat has. **Canon:** iOS `ForwardDrafts`.
 */
@Singleton
class ForwardDrafts @Inject constructor() {
    private val _pending = MutableStateFlow<Map<String, List<ForwardItem>>>(emptyMap())

    /** By contact id. */
    val pending: StateFlow<Map<String, List<ForwardItem>>> = _pending.asStateFlow()

    fun hand(items: List<ForwardItem>, to: String) {
        if (items.isEmpty()) return
        _pending.update { it + (to to (it[to].orEmpty() + items)) }
    }

    fun take(contactId: String): List<ForwardItem> {
        var taken = emptyList<ForwardItem>()
        _pending.update {
            taken = it[contactId].orEmpty()
            it - contactId
        }
        return taken
    }
}
