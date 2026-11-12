package com.construct.messenger.data.repository

import android.net.Uri
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.data.model.IncomingShare
import com.construct.messenger.data.model.IncomingShareStage
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.media.PickedFiles
import com.construct.messenger.share.IncomingShareFiles
import com.construct.messenger.share.IncomingShareIntent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One local request. A prepared draft is never the ordinary pending-send queue. */
@Singleton
class IncomingShareRepository @Inject constructor(
    private val store: SessionBlobStore,
    private val crypto: CryptoManager,
    private val sources: IncomingShareFiles,
    private val messages: MessageStore,
    private val appLock: AppLockRepository,
) {
    enum class Problem { INVALID, TOO_LARGE, UNREADABLE, RESTORE, INTERRUPTED, REVIEW }
    data class State(
        val draft: IncomingShare? = null,
        val busy: Boolean = false,
        val replacement: Boolean = false,
        val problem: Problem? = null,
        val submitting: Boolean = false,
    )
    private data class Offer(val id: String, val input: IncomingShareIntent.Result)
    @Volatile private var offered: Offer? = null
    @Volatile private var recreatedPendingId: String? = null
    val hasInput: Boolean get() = offered != null || recreatedPendingId != null
    fun hasUnpreparedInput(id: String): Boolean = offered?.id == id

    /** Android saved a pending lifecycle token, never its payload. If the process lost that
     * input before CFE was written, tell the user to share again instead of replaying an Intent. */
    fun recreatedInput(pendingId: String?) {
        if (pendingId != null && offered == null) {
            recreatedPendingId = pendingId
            changes.update { it + 1 }
        }
    }
    private val changes = MutableStateFlow(0L)
    /** Only a lifecycle trigger: not a copy of the payload or a delivery cursor. */
    val inputChanges = changes.asStateFlow()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var loaded = false
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun offer(input: IncomingShareIntent.Result, id: String = UUID.randomUUID().toString()): String? {
        if (input == IncomingShareIntent.Result.NotShare) return null
        require(com.construct.messenger.share.ShareSourceUris.isId(id))
        if (offered?.id != id) {
            offered = Offer(id, input)
            changes.update { it + 1 }
        }
        return id
    }

    /** Called only after auth + PIN + local-store gates. Never from the raw Intent callback. */
    suspend fun prepare() = lock.withLock {
        if (appLock.lock.value.requiresUnlock) return@withLock
        try {
            if (!loaded) {
                val kept = store.get(KEY)?.let(crypto::importIncomingShare)
                if (kept != null && kept.stage != IncomingShareStage.FINISHED && !sources.present(kept)) {
                    mutable.value = State(draft = kept.copy(stage = IncomingShareStage.IMPORTING), problem = Problem.INTERRUPTED)
                } else {
                    mutable.value = State(draft = kept, problem = when (kept?.stage) {
                        IncomingShareStage.IMPORTING -> Problem.INTERRUPTED
                        IncomingShareStage.SUBMITTED -> Problem.REVIEW
                        else -> null
                    })
                }
                sources.install(kept?.takeUnless { it.stage == IncomingShareStage.FINISHED })
                withContext(Dispatchers.IO) { sources.prune(kept?.takeUnless { it.stage == IncomingShareStage.FINISHED }?.id) }
                loaded = true
            }
            val lostInput = recreatedPendingId
            recreatedPendingId = null
            if (lostInput != null && offered == null && mutable.value.draft?.id != lostInput) {
                mutable.value = mutable.value.copy(problem = Problem.INTERRUPTED)
                return@withLock
            }
            val input = offered ?: return@withLock
            val current = mutable.value.draft
            if (input.id == current?.id && current.stage != IncomingShareStage.IMPORTING) {
                offered = null
                return@withLock
            }
            if (current != null && current.id != input.id && current.stage != IncomingShareStage.FINISHED) {
                mutable.value = mutable.value.copy(replacement = true)
                return@withLock
            }
            val payload = (input.input as? IncomingShareIntent.Result.Content)?.payload
            if (payload == null) {
                mutable.value = mutable.value.copy(problem = Problem.INVALID)
                offered = null
                return@withLock
            }
            var draft = IncomingShare(input.id, payload.text)
            // Persist the importing marker before the first read. A crash cannot turn a partial
            // import into a complete draft, or silently rely on the provider grant surviving.
            save(draft)
            mutable.value = mutable.value.copy(busy = true, problem = null)
            withContext(Dispatchers.IO) { sources.prune(null) }
            var remaining = sources.importBudget
            for (uri in payload.uris) {
                val item = sources.import(draft.id, uri, remaining)
                remaining -= item.size
                draft = draft.copy(items = draft.items + item)
                save(draft)
            }
            save(draft.copy(stage = IncomingShareStage.READY))
            if (offered?.id == input.id) offered = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(problem = if (e is PickedFiles.TooLarge) Problem.TOO_LARGE else if (!loaded) Problem.RESTORE else Problem.UNREADABLE)
        } finally {
            mutable.value = mutable.value.copy(busy = false)
        }
    }

    fun uri(request: String, item: String): Uri = sources.uri(request, item)

    suspend fun select(id: String, contactId: String) = change(id) { it.copy(contactId = contactId) }
    suspend fun pickAgain(id: String) = change(id) { it.copy(contactId = null) }
    suspend fun editText(id: String, text: String) = change(id) { it.copy(text = text) }
    suspend fun remove(id: String, uri: Uri) = change(id) { draft ->
        draft.copy(items = draft.items.filterNot { sources.uri(id, it.id) == uri })
    }

    private suspend fun change(id: String, update: (IncomingShare) -> IncomingShare) = lock.withLock {
        val current = mutable.value.draft ?: return@withLock
        if (current.id != id || current.stage != IncomingShareStage.READY) return@withLock
        try { save(update(current)) } catch (_: Exception) {
            mutable.value = mutable.value.copy(problem = Problem.RESTORE)
        }
    }

    suspend fun cancel(id: String? = state.value.draft?.id) = lock.withLock {
        if (mutable.value.submitting) return@withLock
        try {
            // Rejecting a raw share before auth/PIN must not erase an older CFE draft we have
            // not been allowed to load. Only an explicitly refused corrupt draft is removable.
            if (!loaded && mutable.value.problem != Problem.RESTORE) {
                offered = null
                recreatedPendingId = null
                changes.update { it + 1 }
                return@withLock
            }
            val current = mutable.value.draft
            if (current != null && current.id != id) return@withLock
            if (current != null) finish(current)
            else if (current == null) {
                store.remove(KEY)
                withContext(Dispatchers.IO) { sources.prune(null) }
                loaded = true
                mutable.value = State()
            }
            offered = null
            recreatedPendingId = null
            changes.update { it + 1 }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(problem = Problem.RESTORE)
        }
    }

    fun clearProblem() { mutable.value = mutable.value.copy(problem = null) }

    fun keepCurrent() {
        offered = null
        mutable.value = mutable.value.copy(replacement = false)
    }

    suspend fun replaceCurrent() {
        try {
            lock.withLock {
                if (mutable.value.submitting) return
                mutable.value.draft?.let { finish(it) }
                mutable.value = mutable.value.copy(replacement = false)
            }
            prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(problem = Problem.RESTORE)
        }
    }

    /** Only the explicit composer Send callback may call this. Commit consent before executing. */
    suspend fun submit(id: String, contactId: String, text: String, itemUris: List<Uri>, send: suspend () -> List<SendOutcome>): Boolean = lock.withLock {
        val current = mutable.value.draft ?: return@withLock false
        if (appLock.lock.value.requiresUnlock || mutable.value.submitting || current.id != id || current.contactId != contactId || current.stage != IncomingShareStage.READY) return@withLock false
        val selected = current.items.filter { sources.uri(id, it.id) in itemUris }
        if (text.isBlank() && selected.isEmpty()) return@withLock false
        try {
            save(current.copy(text = text, items = selected, stage = IncomingShareStage.SUBMITTED))
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(problem = Problem.RESTORE)
            return@withLock false
        }
        mutable.value = mutable.value.copy(submitting = true)
        // Independent of the chat ViewModel: navigating away must not cancel the second group.
        scope.launch {
            try {
                val outcomes = send()
                val owned = outcomes.map { outcome ->
                    val messageId = when (outcome) {
                        is SendOutcome.Sent -> outcome.messageId
                        is SendOutcome.Failed -> outcome.messageId
                    }
                    messages.get(messageId) != null
                }
                lock.withLock {
                    val held = mutable.value.draft ?: return@withLock
                    if (outcomes.isNotEmpty() && owned.all { it }) {
                        // The ordinary sender now owns rows + staged sealed copies, even for a
                        // failed upload. Only now may source keys/files be discarded.
                        finish(held)
                    } else if (owned.none { it }) {
                        save(held.copy(stage = IncomingShareStage.READY))
                        mutable.value = mutable.value.copy(problem = Problem.UNREADABLE)
                    } else {
                        mutable.value = mutable.value.copy(problem = Problem.REVIEW)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(problem = Problem.REVIEW)
            } finally {
                mutable.update { it.copy(submitting = false) }
                // An Intent received while this send owned the sources still needs a consumer.
                if (offered != null) changes.update { it + 1 }
            }
        }
        true
    }

    private suspend fun finish(draft: IncomingShare) {
        // A minimal tombstone suppresses Android redelivery of the same launch Intent. No text,
        // recipient or key is kept after completion. A new intentional share has a different id.
        save(IncomingShare(draft.id, "", stage = IncomingShareStage.FINISHED))
        withContext(Dispatchers.IO) { sources.prune(null) }
        mutable.value = mutable.value.copy(problem = null, replacement = false)
    }

    private suspend fun save(draft: IncomingShare) {
        withContext(Dispatchers.IO) { store.put(KEY, crypto.exportIncomingShare(draft)) }
        sources.install(draft.takeUnless { it.stage == IncomingShareStage.FINISHED })
        mutable.value = mutable.value.copy(draft = draft)
    }

    private companion object { const val KEY = "incoming-share:v1" }
}
