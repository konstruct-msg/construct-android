package com.construct.messenger.data.local

import android.util.Log
import com.construct.messenger.data.local.db.ConstructDatabase
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
 * Which store answers, decided once per launch (TODO 136). **Canon:** iOS `LocalRepositories` —
 * every repository over one store, installed together: a message names its chat and the chat its
 * contact, and half of them on another store is broken links.
 *
 * - The core's store open and [RoomImport] done: the core.
 * - Open, not imported yet: the import runs first, into an empty store, while every call waits
 *   ([LocalStoreGate]). Nothing writes to Room meanwhile, so what it moved is all there is. When
 *   every count matches, Room's file is deleted. Counts that do not match leave Room the store
 *   for this launch and the core's attempt erased, so the next launch starts from nothing again.
 * - Would not open, and there was no file: nothing ever moved — Room.
 * - Would not open over a file: [State.Failed], and nothing answers. Room would show the history as
 *   it was before the move and take writes the core never sees; a fresh store would lose it.
 *
 * Signing out needs nothing here: it ends the process ([LocalDataWipe]).
 */
class LocalStores(
    private val file: File,
    private val key: LocalStoreKey,
    private val room: () -> ConstructDatabase,
    /** False on a new install: nothing to move, and asking Room would create its file. */
    private val roomExists: () -> Boolean,
    /** Room's file and journal, once its rows have moved and the counts matched. */
    private val deleteRoom: () -> Unit,
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

            fun room(db: ConstructDatabase) = Backing(
                chats = RoomChatStore(db.chatDao()),
                messages = RoomMessageStore(db.messageDao()),
                reactions = RoomReactionStore(db.reactionDao()),
                contacts = RoomContactStore(db.userDao()),
                processed = RoomProcessedStore(db.ackDao()),
                sessionBlobs = RoomSessionBlobStore(db.sessionStateDao()),
                issuedInvites = RoomIssuedInviteStore(db.issuedInviteDao()),
                peerDevices = RoomPeerDeviceStore(db.peerDeviceDao()),
                serverMessageIds = RoomServerMessageIdStore(db.serverMessageIdDao()),
                pendingResends = RoomPendingResendStore(db.pendingResendDao()),
                pendingChunks = RoomPendingChunkStore(db.pendingChunkDao()),
                calls = RoomCallStore(db.callRecordDao()),
            )
        }
    }

    sealed interface State {
        data object Opening : State

        /** Room's rows are moving into the core's store; the screens wait. */
        data object Importing : State

        data class Ready(val onCore: Boolean) : State

        /** The core's store is on this device and would not open. Nothing answers until [retry] opens it. */
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
        val existed = file.exists()
        when (val opening = LocalStoreSession.open(file, key.current())) {
            is LocalStoreSession.Opening.Opened -> settle(opening.session)
            is LocalStoreSession.Opening.NotOpened ->
                if (existed) {
                    Log.e(TAG, "the store is here and would not open — ${opening.failure}")
                    _state.value = State.Failed(opening.failure)
                } else {
                    Log.e(TAG, "no store could be made — ${opening.failure}; Room stays")
                    useRoom()
                }
        }
    }

    private suspend fun settle(opened: LocalStoreSession) {
        if (RoomImport.isDone(opened.store)) return useCore(opened)
        if (!roomExists()) return import(opened, null)
        _state.value = State.Importing
        // Anything here is an earlier attempt that did not finish; its rows may be older than
        // Room's now. Start from an empty store under a new key.
        val session = fresh(opened) ?: return useRoom()
        import(session, room())
    }

    private suspend fun import(session: LocalStoreSession, db: ConstructDatabase?) {
        if (db == null) {
            session.store.put(RoomImport.MARKER, byteArrayOf(1))
            return useCore(session)
        }
        try {
            val report = RoomImport(db.exportDao(), session.feed, myUserId()).run()
            Log.i(TAG, "imported ${report.imported}, left out ${report.skipped}")
            useCore(session)
            // As iOS's plan has it: the old store goes once the check has passed — its file is
            // the transcript in the clear.
            runCatching(deleteRoom).onFailure { Log.e(TAG, "Room's file stayed", it) }
        } catch (e: Exception) {
            Log.e(TAG, "the import did not pass — Room stays, the attempt is erased", e)
            erase(session)
            useRoom()
        }
    }

    private fun fresh(session: LocalStoreSession): LocalStoreSession? {
        erase(session)
        return when (val opening = LocalStoreSession.open(file, key.current())) {
            is LocalStoreSession.Opening.Opened -> opening.session
            is LocalStoreSession.Opening.NotOpened -> {
                Log.e(TAG, "no fresh store for the import — ${opening.failure}; Room stays")
                null
            }
        }
    }

    private fun erase(session: LocalStoreSession) {
        runCatching { session.wipe() }.onFailure { Log.e(TAG, "the attempt did not wipe", it) }
        key.destroy()
    }

    private fun useCore(session: LocalStoreSession) {
        installed.value = Backing.core(session, myUserId)
        _state.value = State.Ready(onCore = true)
    }

    private fun useRoom() {
        installed.value = Backing.room(room())
        _state.value = State.Ready(onCore = false)
    }

    private companion object {
        const val TAG = "LocalStores"
    }
}
