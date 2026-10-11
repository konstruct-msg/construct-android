package com.construct.messenger.data.local

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** [ProcessedStore] in memory, held to [ProcessedStoreContract]. */
internal class FakeProcessedStore : ProcessedStore {
    /** Id → (sender, processed at). */
    val rows = LinkedHashMap<String, Pair<String, Long>>()

    override suspend fun record(messageId: String, senderId: String, processedAtMs: Long) {
        rows.putIfAbsent(messageId, senderId to processedAtMs)
    }

    override suspend fun ids(): List<String> = rows.keys.toList()

    override suspend fun forgetBefore(cutoffMs: Long): Int {
        val before = rows.size
        rows.values.removeAll { it.second < cutoffMs }
        return before - rows.size
    }
}

/** [PendingChunkStore] in memory, held to [PendingChunkStoreContract]. */
internal class FakePendingChunkStore : PendingChunkStore {
    val rows = mutableListOf<PendingChunk>()

    override suspend fun add(chunk: PendingChunk) {
        if (rows.none { it.senderId == chunk.senderId && it.messageId == chunk.messageId && it.chunkIndex == chunk.chunkIndex }) {
            rows += chunk
        }
    }

    override suspend fun chunks(senderId: String, messageId: String) =
        rows.filter { it.senderId == senderId && it.messageId == messageId }.sortedBy { it.chunkIndex }

    override suspend fun delete(senderId: String, messageId: String) {
        rows.removeAll { it.senderId == senderId && it.messageId == messageId }
    }

    override suspend fun forgetBefore(cutoffMs: Long): Int {
        val before = rows.size
        rows.removeAll { it.receivedAtMs < cutoffMs }
        return before - rows.size
    }
}

/** [PendingResendStore] in memory, held to [PendingResendStoreContract]. */
internal class FakePendingResendStore : PendingResendStore {
    val rows = mutableListOf<PendingResend>()

    override suspend fun queue(resend: PendingResend) {
        if (rows.none { it.messageId == resend.messageId && it.deviceId == resend.deviceId }) rows += resend
    }

    override suspend fun all() = rows.sortedBy { it.createdAtMs }

    override suspend fun delete(messageId: String, deviceId: String) {
        rows.removeAll { it.messageId == messageId && it.deviceId == deviceId }
    }

    override suspend fun countAttempt(messageId: String, deviceId: String) {
        val i = rows.indexOfFirst { it.messageId == messageId && it.deviceId == deviceId }
        if (i >= 0) rows[i] = rows[i].copy(attempts = rows[i].attempts + 1)
    }

    override suspend fun forgetBefore(cutoffMs: Long): Int {
        val before = rows.size
        rows.removeAll { it.createdAtMs < cutoffMs }
        return before - rows.size
    }
}

/** [IssuedInviteStore] in memory, held to [IssuedInviteStoreContract]. */
internal class FakeIssuedInviteStore : IssuedInviteStore {
    private val rows = kotlinx.coroutines.flow.MutableStateFlow<Map<String, IssuedInviteRecord>>(emptyMap())

    override fun observeAll() = rows.map { it.values.sortedWith(compareByDescending<IssuedInviteRecord> { r -> r.issuedAtEpochSec }.thenBy { r -> r.jti }) }

    override suspend fun record(invite: IssuedInviteRecord) = rows.update { it + (invite.jti to invite) }

    override suspend fun delete(jti: String) = rows.update { it - jti }
}

/** [SessionBlobStore] in memory, held to [SessionBlobStoreContract]. */
internal class FakeSessionBlobStore : SessionBlobStore {
    val rows = mutableMapOf<String, ByteArray>()

    override suspend fun put(key: String, bytes: ByteArray) {
        rows[key] = bytes
    }

    override suspend fun get(key: String): ByteArray? = rows[key]

    override suspend fun remove(key: String) {
        rows.remove(key)
    }

    override suspend fun withPrefix(prefix: String): Map<String, ByteArray> = rows.filterKeys { it.startsWith(prefix) }
}
