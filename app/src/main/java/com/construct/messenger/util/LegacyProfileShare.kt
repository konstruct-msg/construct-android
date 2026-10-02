package com.construct.messenger.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A contact's profile, sent to us inside the E2E channel when they share it: the name they go by
 * and, from iOS, an avatar in the media store.
 *
 * **Canon:** iOS `ProfileShareData.toBinaryData` / `fromBinaryData`, byte for byte — version 0x01,
 * the name as u16-LE length + UTF-8, then four optional fields (a presence byte, then u16-LE length
 * + bytes), then the timestamp as i64 LE. It travels as the payload of an ordinary KNST frame
 * (type 1), which is why a reader must try it before reading the payload as text.
 *
 * Android has no media yet, so the avatar fields are carried but not fetched.
 */
data class LegacyProfileShare(
    val displayName: String,
    val avatarMediaId: String? = null,
    val avatarMediaUrl: String? = null,
    val avatarMediaKey: ByteArray? = null,
    val avatarMediaType: String? = null,
    val timestampSec: Long,
) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(n: Int) {
            out.write(n and 0xFF)
            out.write((n shr 8) and 0xFF)
        }
        fun bytes(b: ByteArray) {
            u16(b.size)
            out.write(b)
        }
        fun optional(b: ByteArray?) {
            out.write(if (b != null) 1 else 0)
            if (b != null) bytes(b)
        }
        out.write(VERSION)
        bytes(displayName.toByteArray(Charsets.UTF_8))
        optional(avatarMediaId?.toByteArray(Charsets.UTF_8))
        optional(avatarMediaUrl?.toByteArray(Charsets.UTF_8))
        optional(avatarMediaKey)
        optional(avatarMediaType?.toByteArray(Charsets.UTF_8))
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(timestampSec).array())
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is LegacyProfileShare &&
            displayName == other.displayName &&
            avatarMediaId == other.avatarMediaId &&
            avatarMediaUrl == other.avatarMediaUrl &&
            avatarMediaKey.contentEquals(other.avatarMediaKey) &&
            avatarMediaType == other.avatarMediaType &&
            timestampSec == other.timestampSec

    override fun hashCode(): Int = displayName.hashCode() * 31 + timestampSec.hashCode()

    companion object {
        private const val VERSION = 0x01

        /**
         * Null unless [data] is a whole v1 profile. Peer-controlled bytes: every length is checked
         * against what is left, and the name must be valid UTF-8, as iOS requires.
         */
        fun decode(data: ByteArray): LegacyProfileShare? {
            if (data.size <= 1 || data[0].toInt() != VERSION) return null
            var at = 1
            fun take(): ByteArray? {
                if (at + 2 > data.size) return null
                val len = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
                at += 2
                if (at + len > data.size) return null
                return data.copyOfRange(at, at + len).also { at += len }
            }
            fun utf8(b: ByteArray): String? = runCatching {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString()
            }.getOrNull()
            // iOS reads a field marked present but cut short as absent and carries on; here the
            // whole payload is refused instead. Whatever iOS writes parses the same either way.
            var cut = false
            fun optional(): ByteArray? {
                if (at >= data.size) return null.also { cut = true }
                val present = data[at++].toInt() == 1
                return if (present) take() ?: null.also { cut = true } else null
            }
            val name = take()?.let(::utf8) ?: return null
            val mediaId = optional()?.let(::utf8)
            val mediaUrl = optional()?.let(::utf8)
            val mediaKey = optional()
            val mediaType = optional()?.let(::utf8)
            if (cut) return null
            if (at + 8 > data.size) return null
            val ts = ByteBuffer.wrap(data, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
            return LegacyProfileShare(name, mediaId, mediaUrl, mediaKey, mediaType, ts)
        }
    }
}
