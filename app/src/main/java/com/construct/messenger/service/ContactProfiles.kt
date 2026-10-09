package com.construct.messenger.service

import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.LegacyProfileShare
import com.construct.messenger.util.ProfileShare

/**
 * What a contact's profile does to their row. Pure: the caller reads the row, writes the answer
 * and, when [Applied.fetchAvatar], starts the download. **Canon:** iOS
 * `ProfileSharingManager.apply` / `handleProfileMessage`.
 *
 * Onto an existing row only — a sender must not be able to put a contact in our store by sending
 * to us. The name is theirs to choose; a local name the user gave still outranks it on screen.
 */
internal object ContactProfiles {
    class Applied(val row: ContactRecord, val fetchAvatar: Boolean)

    /** Content type 29. Null: not newer than the profile held — ignored whole. */
    fun typed(row: ContactRecord, profile: ProfileShare, nowMs: Long): Applied? {
        val action = profile.decision(row.profileEditedAtMs) ?: return null
        val named = row.copy(
            // A profile is the sender's whole state at its version: no chosen name means none, so a
            // name shared earlier goes and the username shows — never the generated name, which
            // until 2026-10-02 replaced the username a QR invite had given.
            displayName = profile.chosenName(row.id).orEmpty(),
            isSharingWithMe = true,
            profileEditedAtMs = profile.editedAtMs,
        )
        return when (action) {
            is ProfileShare.AvatarAction.Download ->
                Applied(named.copy(pendingAvatarRef = action.ref.stored(), pendingAvatarSinceMs = nowMs), fetchAvatar = true)
            ProfileShare.AvatarAction.Clear ->
                Applied(named.copy(avatarData = null, pendingAvatarRef = null, pendingAvatarSinceMs = null), fetchAvatar = false)
            ProfileShare.AvatarAction.Keep -> Applied(named, fetchAvatar = false)
        }
    }

    /**
     * The untyped v1 layout, read for one release. It carries the send time, not a version, so it
     * cannot be ordered against a typed one: once a typed profile is held, it is a resend from a
     * build that predates the type and could put an older name back — ignored. Without an avatar
     * it leaves the one held, as it always did.
     */
    fun legacy(row: ContactRecord, profile: LegacyProfileShare, nowMs: Long): Applied? {
        if (row.profileEditedAtMs != 0L) return null
        // The generated name is no name: the one held stays (`ProfileShare.chosenName`).
        val shared = profile.displayName.trim().takeUnless { DisplayNameGenerator.isGenerated(it, row.id) }.orEmpty()
        val named = row.copy(displayName = shared.ifEmpty { row.displayName }, isSharingWithMe = true)
        val ref = legacyAvatar(profile) ?: return Applied(named, fetchAvatar = false)
        return Applied(named.copy(pendingAvatarRef = ref.stored(), pendingAvatarSinceMs = nowMs), fetchAvatar = true)
    }

    private fun legacyAvatar(p: LegacyProfileShare): ProfileShare.AvatarRef? {
        val id = p.avatarMediaId ?: return null
        val key = p.avatarMediaKey?.takeIf { it.size == ProfileShare.AvatarRef.KEY_BYTES } ?: return null
        return ProfileShare.AvatarRef(id, p.avatarMediaUrl.orEmpty(), key, p.avatarMediaType ?: "image/jpeg")
    }
}
