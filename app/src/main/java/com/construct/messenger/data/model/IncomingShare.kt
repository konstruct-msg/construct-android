package com.construct.messenger.data.model

/** Local OS input, never a MessageContent or permission to send. Persisted by the core's codec. */
enum class IncomingShareStage { IMPORTING, READY, SUBMITTED, FINISHED }

data class SharedSource(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    internal val key: ByteArray,
) {
    val isVisual: Boolean get() = mime.startsWith("image/") || mime.startsWith("video/")
    // Do not put source keys in a diagnostic, even through data class toString().
    override fun toString() = "SharedSource($mime, $size bytes)"
}

data class IncomingShare(
    val id: String,
    val text: String,
    val items: List<SharedSource> = emptyList(),
    val contactId: String? = null,
    val stage: IncomingShareStage = IncomingShareStage.IMPORTING,
) {
    override fun toString() = "IncomingShare($stage, ${items.size} items)"
}

/** The composer's existing UI capacity, not the protocol's media size limit. */
object ComposerCapacity {
    const val ATTACHMENTS = 99
}
