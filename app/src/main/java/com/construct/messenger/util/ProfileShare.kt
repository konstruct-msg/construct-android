package com.construct.messenger.util

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import shared.proto.core.v1.EnvelopeOuterClass

/**
 * The payload of content type 29 (`ProfileShare` in construct-protos `core/envelope.proto`): who
 * the sender is to this contact, by its own account. **Canon:** iOS `ProfileShare`.
 *
 * Until 2026-10-02 a profile had no type. It was recognised by whether a plaintext parsed as the
 * hand-written v1 layout ([LegacyProfileShare]); its timestamp was the send time, so a resent old
 * profile looked new; and "no avatar" meant both "removed" and "upload failed". How a payload
 * reads and when it applies are fixed for both clients by `knst_profile_share.json`.
 * `decisions/profile-share-is-a-typed-versioned-state.md`
 */
data class ProfileShare(
    val displayName: String,
    /** When the sender last changed its name or avatar, ms since the epoch. Not the send time. */
    val editedAtMs: Long,
    val avatar: Avatar,
) {
    /** An avatar in the media store, sealed under [mediaKey]. */
    class AvatarRef(val mediaId: String, val mediaUrl: String, val mediaKey: ByteArray, val mimeType: String) {
        fun stored(): ByteArray = proto().toByteArray()

        fun proto(): EnvelopeOuterClass.AvatarRef = EnvelopeOuterClass.AvatarRef.newBuilder()
            .setMediaId(mediaId)
            .setMediaUrl(mediaUrl)
            .setMediaKey(ByteString.copyFrom(mediaKey))
            .setMimeType(mimeType)
            .build()

        override fun equals(other: Any?): Boolean =
            other is AvatarRef && mediaId == other.mediaId && mediaUrl == other.mediaUrl &&
                mediaKey.contentEquals(other.mediaKey) && mimeType == other.mimeType

        override fun hashCode(): Int = mediaId.hashCode()

        companion object {
            const val KEY_BYTES = 32

            /** What a contact row keeps as its pending avatar. Null for bytes that are not usable. */
            fun fromStored(bytes: ByteArray): AvatarRef? = try {
                from(EnvelopeOuterClass.AvatarRef.parseFrom(bytes))
            } catch (_: InvalidProtocolBufferException) {
                null
            }

            /** A key that is not 32 bytes makes the reference unusable. */
            fun from(ref: EnvelopeOuterClass.AvatarRef): AvatarRef? {
                if (ref.mediaKey.size() != KEY_BYTES) return null
                return AvatarRef(ref.mediaId, ref.mediaUrl, ref.mediaKey.toByteArray(), ref.mimeType)
            }
        }
    }

    sealed interface Avatar {
        /** Download this and replace the avatar. */
        data class Set(val ref: AvatarRef) : Avatar
        /** The sender has no avatar now: clear it. */
        data object Removed : Avatar
        /** Not changed by this profile, or the sender could not upload it: keep what is held. */
        data object Unchanged : Avatar
    }

    /** What a receiver does with the avatar it holds once a profile is applied. */
    sealed interface AvatarAction {
        data class Download(val ref: AvatarRef) : AvatarAction
        data object Clear : AvatarAction
        data object Keep : AvatarAction
    }

    /**
     * The name this profile carries, if the sender chose one. Null for none: empty, or only the
     * name generated from [senderId] — what Android sent until 2026-10-02 when its user had set no
     * name. The receiver then shows the username. **Canon:** iOS `ProfileShare.chosenName`.
     */
    fun chosenName(senderId: String): String? =
        displayName.trim().takeUnless { it.isEmpty() || DisplayNameGenerator.isGenerated(it, senderId) }

    fun encoded(): ByteArray {
        val builder = EnvelopeOuterClass.ProfileShare.newBuilder()
            .setDisplayName(displayName)
            .setEditedAtMs(editedAtMs)
        when (avatar) {
            is Avatar.Set -> builder.setAvatarSet(avatar.ref.proto())
            Avatar.Removed -> builder.setAvatarRemoved(true)
            Avatar.Unchanged -> Unit
        }
        return builder.build().toByteArray()
    }

    /**
     * Whether to apply this profile over the one held, and what then happens to the avatar. Null:
     * ignore it whole — it is not newer, so a resend, a redelivery or a reordered queue cannot put
     * an older name or avatar back. Equal is not newer: the same profile twice applies once.
     * [heldEditedAtMs] 0 means nothing typed has been applied.
     */
    fun decision(heldEditedAtMs: Long): AvatarAction? {
        if (heldEditedAtMs > 0 && java.lang.Long.compareUnsigned(editedAtMs, heldEditedAtMs) <= 0) return null
        return when (avatar) {
            is Avatar.Set -> AvatarAction.Download(avatar.ref)
            Avatar.Removed -> AvatarAction.Clear
            Avatar.Unchanged -> AvatarAction.Keep
        }
    }

    companion object {
        /** Null when [payload] is not a `ProfileShare`. */
        fun read(payload: ByteArray): ProfileShare? {
            val proto = try {
                EnvelopeOuterClass.ProfileShare.parseFrom(payload)
            } catch (_: InvalidProtocolBufferException) {
                return null
            }
            val avatar = when (proto.avatarCase) {
                // A key of the wrong length is read as no change, not as a broken profile.
                EnvelopeOuterClass.ProfileShare.AvatarCase.AVATAR_SET ->
                    AvatarRef.from(proto.avatarSet)?.let(Avatar::Set) ?: Avatar.Unchanged
                EnvelopeOuterClass.ProfileShare.AvatarCase.AVATAR_REMOVED ->
                    if (proto.avatarRemoved) Avatar.Removed else Avatar.Unchanged
                else -> Avatar.Unchanged
            }
            return ProfileShare(proto.displayName, proto.editedAtMs, avatar)
        }
    }
}
