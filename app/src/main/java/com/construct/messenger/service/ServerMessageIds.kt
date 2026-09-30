package com.construct.messenger.service

import com.construct.messenger.data.local.db.ServerMessageIdDao
import com.construct.messenger.data.local.db.ServerMessageIdEntity
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which of our messages a server-assigned id belongs to.
 *
 * A sealed copy does not keep the id we send it under (`<base>-fd-<tag>`): the server assigns its
 * own, so the relay cannot link the copies of one message. The recipient sees only that id, and
 * when it cannot read the copy its DECRYPTION_ERROR names it — so `ResendMessage` names an id no
 * row here has. The send response is the one place the pair is visible.
 *
 * In Room since 2026-09-30. It was in memory, like iOS `ServerMessageIdMap`, and an error that
 * arrived after we restarted — the ordinary case: the recipient reads its queue whenever it next
 * comes online — retired the state and resent nothing, so the message stayed lost. Kept for
 * [RETENTION_MS], as long as the server keeps a device's queue: an error cannot name a copy older
 * than that.
 */
@Singleton
class ServerMessageIds @Inject constructor(
    private val dao: ServerMessageIdDao,
) {
    private val pruned = AtomicBoolean(false)

    suspend fun record(serverId: String, localId: String, nowMs: Long = System.currentTimeMillis()) {
        val server = serverId.lowercase()
        val local = localId.lowercase()
        if (server.isEmpty() || server == local) return
        // Once per process: the table only grows by our own sends.
        if (pruned.compareAndSet(false, true)) dao.pruneOlderThan(nowMs - RETENTION_MS)
        dao.upsert(ServerMessageIdEntity(serverId = server, localId = local, recordedAtMs = nowMs))
    }

    /** The local id for a (possibly server-assigned) [id]; [id] itself when unknown. */
    suspend fun localId(id: String): String = dao.localId(id.lowercase()) ?: id

    companion object {
        /** The server trims a device queue to 30 days (`XTRIM MINID`, vault TODO 79). */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
