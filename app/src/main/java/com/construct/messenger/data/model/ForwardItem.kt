package com.construct.messenger.data.model

/**
 * What a forward sends: the content of a message, and nothing about where it came from.
 * **Canon:** iOS `ForwardItem` (construct-ios fc9fbe66); `decisions/forward-carries-no-author.md`.
 *
 * Owner's rule (2026-10-13): nobody may ever learn whom a forwarded message is from. A forward is
 * an ordinary send of the same content by the chat it goes to — no author, no "forwarded" mark,
 * no reply or quote of the original, no time. Its media goes up again, sealed under a new key, so
 * the original's object is never referenced: a second chat fetching the same object would tie the
 * two conversations together for the server.
 *
 * The [MediaItem]s kept here only locate the local copy of the bytes; none of their ids or keys
 * goes into what is sent.
 */
sealed interface ForwardItem {
    data class Text(val text: String) : ForwardItem

    /** Photos, videos (a video note stays a note) or files, with their caption — content, so it goes too. */
    data class Album(val items: List<MediaItem>, val caption: String) : ForwardItem

    data class Voice(val audio: MediaItem, val waveform: List<Int>) : ForwardItem

    data class Sticker(val ref: com.construct.messenger.stickers.StickerReference) : ForwardItem

    companion object {
        /** The content of [message], read the way its bubble reads it; null when there is nothing to send. */
        fun of(message: Message): ForwardItem? = when (val media = message.media) {
            is MessageMedia.Sticker -> Sticker(media.ref)
            is MessageMedia.Voice -> Voice(media.audio, media.waveform)
            is MessageMedia.Album -> media.items.takeIf { it.isNotEmpty() }?.let { Album(it, message.body.trim()) }
            null -> message.body.trim().takeIf { it.isNotEmpty() }?.let(::Text)
        }

        /** The forwardable content of [messages], in the order given — the transcript's. */
        fun of(messages: List<Message>): List<ForwardItem> = messages.mapNotNull(::of)
    }
}
