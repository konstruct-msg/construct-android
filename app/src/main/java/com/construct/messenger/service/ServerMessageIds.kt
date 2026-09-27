package com.construct.messenger.service

/**
 * Which of our messages a server-assigned id belongs to.
 *
 * A sealed copy does not keep the id we send it under (`<base>-fd-<tag>`): the server assigns its
 * own, so the relay cannot link the copies of one message. The recipient sees only that id, and
 * when it cannot read the copy its DECRYPTION_ERROR names it — so `ResendMessage` names an id no
 * row here has. The send response is the one place the pair is visible.
 *
 * In memory, capped, like iOS `ServerMessageIdMap`: an error answered across a restart still
 * retires the state, and resends nothing.
 */
object ServerMessageIds {
    private const val CAPACITY = 512
    private val map = object : LinkedHashMap<String, String>(CAPACITY, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CAPACITY
    }

    @Synchronized
    fun record(serverId: String, localId: String) {
        val server = serverId.lowercase()
        val local = localId.lowercase()
        if (server.isEmpty() || server == local) return
        map[server] = local
    }

    /** The local id for a (possibly server-assigned) [id]; [id] itself when unknown. */
    @Synchronized
    fun localId(id: String): String = map[id.lowercase()] ?: id
}
