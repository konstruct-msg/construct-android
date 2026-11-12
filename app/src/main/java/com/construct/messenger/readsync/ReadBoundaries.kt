package com.construct.messenger.readsync

import android.content.Context
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.construct_core.PartnerBoundary
import uniffi.construct_core.ReadMarkerState

/**
 * Per partner (ServerUserId, lowercased): how far this account has read the chat, from this
 * device or another of its own, the newest incoming message seen (what this device marks as read
 * when the chat is open), and the boundaries read here not yet sent to our other devices.
 *
 * The state is the core's ([ReadMarkerState], construct-rules `read_sync`), one for every client
 * (todo 300), kept as its CFE in a file of this device's own (`no_backup`): read on the receive
 * path without waiting, as the SharedPreferences it replaces were. Until 2026-11-12 the two
 * preference files held `ms:id` strings; they are read once into the core's state, until
 * [LEGACY_UNTIL_MS], and cleared. A local wipe clears it with everything else.
 */
@Singleton
class ReadBoundaries internal constructor(private val file: File, private val context: Context?) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, FILE), context)

    private var state: ReadMarkerState? = null

    fun boundary(partner: String): ReadSync.Boundary? = synchronized(this) {
        held().boundary(partner)?.let(ReadSync.Boundary::of)
    }

    /** Move [partner]'s boundary to [to] if it is later; true when it moved. */
    fun advance(partner: String, to: ReadSync.Boundary): Boolean = changing { it.advance(partner, to.core) }

    /** [message] from [partner] is at or before what this account has read. */
    fun covers(partner: String, message: ReadSync.Boundary): Boolean = synchronized(this) {
        held().covers(partner, message.core)
    }

    /** An incoming message from [partner] arrived; the newest one is what reading the chat reads. */
    fun noteIncoming(partner: String, message: ReadSync.Boundary) = changing { it.noteIncoming(partner, message.core) }

    fun newestIncoming(partner: String): ReadSync.Boundary? = synchronized(this) {
        held().newestIncoming(partner)?.let(ReadSync.Boundary::of)
    }

    /** A marker from another device: applied when from our own account and later. */
    fun apply(partner: String, marker: ReadSync.Boundary, fromOwnAccount: Boolean, ownAccount: String): ReadSync.Outcome =
        with(ReadSync) { changing { it.applyMarker(partner, marker.core, fromOwnAccount, ownAccount) }.kt }

    /** Read here: the boundary moves and the marker waits to go out. True when it moved. */
    fun noteRead(partner: String, boundary: ReadSync.Boundary): Boolean = changing { it.noteRead(partner, boundary.core) }

    /** What is to be sent now; pending is emptied. */
    fun takePending(): Map<String, ReadSync.Boundary> =
        changing { it.takePending() }.associate { it.partner to ReadSync.Boundary.of(it.boundary) }

    /** A send that failed: back into pending, unless reading moved further meanwhile. */
    fun returnPending(unsent: Map<String, ReadSync.Boundary>) =
        changing { state -> state.returnPending(unsent.map { (p, b) -> PartnerBoundary(p, b.core) }) }

    fun hasPending(): Boolean = synchronized(this) { held().hasPending() }

    private fun <T> changing(body: (ReadMarkerState) -> T): T = synchronized(this) {
        val state = held()
        body(state).also { save(state) }
    }

    private fun held(): ReadMarkerState = state ?: load().also { state = it }

    private fun load(): ReadMarkerState {
        if (file.exists()) {
            val kept = runCatching { file.readBytes() }.onFailure { Log.w(TAG, "$FILE not read", it) }.getOrNull()
            // Bytes that are not this state start it empty, logged by the core.
            if (kept != null) return ReadMarkerState.fromBytes(kept)
        }
        val state = ReadMarkerState()
        context?.let { migrate(it, state) }
        return state
    }

    /** The `ms:id` strings of the preference files from before (temporary, until [LEGACY_UNTIL_MS]). */
    private fun migrate(context: Context, state: ReadMarkerState) {
        val read = context.getSharedPreferences(LEGACY_READ, Context.MODE_PRIVATE)
        val newest = context.getSharedPreferences(LEGACY_NEWEST, Context.MODE_PRIVATE)
        if (read.all.isEmpty() && newest.all.isEmpty()) return
        read.all.forEach { (partner, v) -> (v as? String)?.let(::legacy)?.let { state.advance(partner, it.core) } }
        newest.all.forEach { (partner, v) -> (v as? String)?.let(::legacy)?.let { state.noteIncoming(partner, it.core) } }
        save(state)
        read.edit().clear().apply()
        newest.edit().clear().apply()
        Log.i(TAG, "read boundaries moved from preferences into the core's state")
    }

    /** Write beside, then move over: a kill mid-write leaves the old file whole. */
    private fun save(state: ReadMarkerState) {
        runCatching {
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeBytes(state.toBytes())
            if (!tmp.renameTo(file)) error("rename failed")
        }.onFailure { Log.e(TAG, "$FILE not saved", it) }
    }

    internal companion object {
        const val TAG = "ReadBoundaries"
        const val FILE = "read-sync.cfe"
        const val LEGACY_READ = "read_boundaries"
        const val LEGACY_NEWEST = "read_boundaries_newest"

        /** 2027-01-31: after it the preference files are no longer read (rule 11); a test fails then. */
        const val LEGACY_UNTIL_MS = 1_801_353_600_000L

        /** `ms:id` — the id last, as it may hold anything. */
        fun legacy(s: String): ReadSync.Boundary? {
            val colon = s.indexOf(':')
            if (colon <= 0) return null
            val ms = s.substring(0, colon).toLongOrNull() ?: return null
            return ReadSync.Boundary(ms, s.substring(colon + 1))
        }
    }
}
