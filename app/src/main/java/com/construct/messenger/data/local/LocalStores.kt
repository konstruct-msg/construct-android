package com.construct.messenger.data.local

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The core's store, opened once per launch (TODO 136). **Canon:** iOS `LocalRepositories` — every
 * repository over one store, installed together: a message names its chat and the chat its
 * contact, and half of them on another store is broken links.
 *
 * - Opened: the core answers. A Room file still on the device (its rows never moved: the app went
 *   from before 0.21 straight past the import) is deleted — it is the transcript in the clear, and
 *   nothing reads it any more.
 * - Would not open: [State.Failed], and nothing answers until [retry] opens it or the person
 *   erases. Until 2026-10-19 a device with no store file fell back to Room; Room is gone.
 *
 * Signing out needs nothing here: it ends the process ([LocalDataWipe]).
 */
class LocalStores(
    private val file: File,
    private val key: LocalStoreKey,
    /** Deletes what Room left on the device, if anything; true when there was something. */
    private val deleteRoomLeftovers: () -> Boolean,
    private val myUserId: () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LocalStoreGate {

    class Backing(
        val chats: ChatStore,
        val messages: MessageStore,
        val reactions: ReactionStore,
        val contacts: ContactStore,
        val processed: ProcessedStore,
        val sessionBlobs: SessionBlobStore,
        val issuedInvites: IssuedInviteStore,
        val peerDevices: PeerDeviceStore,
        val serverMessageIds: ServerMessageIdStore,
        val pendingResends: PendingResendStore,
        val pendingChunks: PendingChunkStore,
        val calls: CallStore,
    ) {
        companion object {
            fun core(session: LocalStoreSession, myUserId: () -> String?): Backing {
                val feed = session.feed
                val store = session.store
                return Backing(
                    chats = CoreChatStore(feed),
                    messages = CoreMessageStore(feed, myUserId),
                    reactions = CoreReactionStore(feed),
                    contacts = CoreContactStore(feed),
                    processed = CoreProcessedStore(store),
                    sessionBlobs = CoreSessionBlobStore(store),
                    issuedInvites = CoreIssuedInviteStore(feed),
                    peerDevices = CorePeerDeviceStore(store),
                    serverMessageIds = CoreServerMessageIdStore(store),
                    pendingResends = CorePendingResendStore(store),
                    pendingChunks = CorePendingChunkStore(store),
                    calls = CoreCallStore(feed),
                )
            }
        }
    }

    sealed interface State {
        data object Opening : State

        data object Ready : State

        /** The core's store would not open. Nothing answers until [retry] opens it. */
        data class Failed(val failure: LocalStoreSession.Failure) : State
    }

    private val _state = MutableStateFlow<State>(State.Opening)
    val state: StateFlow<State> = _state.asStateFlow()

    private val installed = MutableStateFlow<Backing?>(null)
    private val deciding = Mutex()

    init {
        scope.launch { decide() }
    }

    override suspend fun backing(): Backing = installed.filterNotNull().first()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun <T> watch(pick: (Backing) -> Flow<T>): Flow<T> = installed.filterNotNull().flatMapLatest(pick)

    /** After [State.Failed]: open again — the Keystore may answer now. */
    fun retry() {
        scope.launch { decide() }
    }

    private suspend fun decide() = deciding.withLock {
        if (installed.value != null) return@withLock
        _state.value = State.Opening
        when (val opening = LocalStoreSession.open(file, key.current())) {
            is LocalStoreSession.Opening.Opened -> {
                installed.value = Backing.core(opening.session, myUserId)
                // Before Ready, so Ready means the cleartext file is gone, not about to be.
                runCatching(deleteRoomLeftovers)
                    .onSuccess { if (it) Log.w(TAG, "Room's file was still here, its rows never moved — deleted") }
                    .onFailure { Log.e(TAG, "Room's file stayed", it) }
                _state.value = State.Ready
            }
            is LocalStoreSession.Opening.NotOpened -> {
                Log.e(TAG, "the store would not open — ${opening.failure}")
                _state.value = State.Failed(opening.failure)
            }
        }
    }

    private companion object {
        const val TAG = "LocalStores"
    }
}
