package com.construct.messenger.data.local

import com.construct.messenger.data.model.ReplyRef
import org.json.JSONObject

/**
 * A quote as the store's `reply_to_content` keeps it — iOS `ReplyPreviewPayload.storedContent`,
 * so a row reads the same whichever client wrote it (TODO 136):
 * - a quote of text: the text itself;
 * - of anything else: `{"type":"reply_preview","kind":"image","text":"caption"}` — the kind and
 *   the user's own words only, never the quoted media's descriptor.
 *
 * Text is capped at [ReplyRef.MAX_CHARS], as iOS caps it.
 */
object ReplyQuoteStorage {
    private const val TYPE = "reply_preview"

    private val KIND_OF_MEDIA = mapOf(
        "MEDIA_TYPE_IMAGE" to "image",
        "MEDIA_TYPE_VIDEO" to "video",
        "MEDIA_TYPE_AUDIO" to "audio",
        "MEDIA_TYPE_FILE" to "file",
        "MEDIA_TYPE_ANIMATED" to "animated",
        "MEDIA_TYPE_STICKER" to "sticker",
    )
    private val MEDIA_OF_KIND = KIND_OF_MEDIA.entries.associate { (media, kind) -> kind to media } + ("videoNote" to "MEDIA_TYPE_VIDEO")

    /** [preview] and [mediaType] (a proto `MediaType` name, or null for text) in iOS's stored form. */
    fun encode(preview: String?, mediaType: String?, videoNote: Boolean = false): String? {
        val text = preview?.take(ReplyRef.MAX_CHARS)?.takeIf { it.isNotEmpty() }
        if (mediaType == null) return text
        val kind = if (videoNote && mediaType == "MEDIA_TYPE_VIDEO") "videoNote" else KIND_OF_MEDIA[mediaType] ?: "unknownAttachment"
        // Key order as Swift's synthesized encoder writes it: type, kind, text.
        return buildString {
            append("{\"type\":").append(JSONObject.quote(TYPE))
            append(",\"kind\":").append(JSONObject.quote(kind))
            if (text != null) append(",\"text\":").append(JSONObject.quote(text))
            append('}')
        }
    }

    /** The stored quote as (preview, media type name, video note); plain text when it is not the envelope. */
    fun decode(stored: String?): Triple<String, String?, Boolean>? {
        if (stored.isNullOrEmpty()) return null
        val json = runCatching { JSONObject(stored) }.getOrNull()?.takeIf { it.optString("type") == TYPE }
            ?: return Triple(stored, null, false)
        val kind = json.optString("kind")
        val text = if (json.has("text") && !json.isNull("text")) json.getString("text") else ""
        return Triple(text, MEDIA_OF_KIND[kind], kind == "videoNote")
    }
}
