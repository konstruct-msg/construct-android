package com.construct.messenger.util

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Content type 29 against construct-protos `conformance/knst_profile_share.json` (vendored into
 * test resources) — iOS reads the same file.
 */
class ProfileShareConformanceTest {
    private val vectors =
        JSONObject(javaClass.classLoader!!.getResource("conformance/knst_profile_share.json")!!.readText())

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun expectedAvatar(v: JSONObject): ProfileShare.Avatar {
        val avatar = v.get("avatar")
        if (avatar is JSONObject) {
            val set = avatar.getJSONObject("set")
            return ProfileShare.Avatar.Set(
                ProfileShare.AvatarRef(
                    set.getString("media_id"),
                    set.getString("media_url"),
                    hex(set.getString("media_key")),
                    set.getString("mime_type"),
                ),
            )
        }
        return when (avatar as String) {
            "removed" -> ProfileShare.Avatar.Removed
            "unchanged" -> ProfileShare.Avatar.Unchanged
            else -> error("unknown avatar $avatar")
        }
    }

    /** Mutation: read a short key as `Set` — `short_key_is_unchanged` reddens. */
    @Test
    fun `every payload reads as the vector says`() {
        val list = vectors.getJSONArray("decode")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val name = v.getString("name")
            val read = ProfileShare.read(hex(v.getString("payload")))
            assertNotNull(name, read)
            assertEquals(name, v.getString("display_name"), read!!.displayName)
            assertEquals(name, v.getLong("edited_at_ms"), read.editedAtMs)
            assertEquals(name, expectedAvatar(v), read.avatar)
        }
    }

    /** What Android sends is byte for byte what the vectors hold — so iOS reads it the same way. */
    @Test
    fun `what is sent encodes as the vector`() {
        val list = vectors.getJSONArray("decode")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            if (v.getString("name") == "short_key_is_unchanged") continue // not something we send
            val profile = ProfileShare(v.getString("display_name"), v.getLong("edited_at_ms"), expectedAvatar(v))
            assertArrayEquals(v.getString("name"), hex(v.getString("payload")), profile.encoded())
        }
    }

    /** Mutation: let an equal `edited_at_ms` apply — `same_is_not_newer` reddens. */
    @Test
    fun `a profile applies only when newer, and the avatar follows its state`() {
        val ref = ProfileShare.AvatarRef("m", "u", ByteArray(32), "image/jpeg")
        val list = vectors.getJSONArray("apply")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val name = v.getString("name")
            val avatar = when (v.getString("avatar")) {
                "set" -> ProfileShare.Avatar.Set(ref)
                "removed" -> ProfileShare.Avatar.Removed
                else -> ProfileShare.Avatar.Unchanged
            }
            val held = if (v.isNull("held_edited_at_ms")) 0L else v.getLong("held_edited_at_ms")
            val decision = ProfileShare("n", v.getLong("edited_at_ms"), avatar).decision(held)
            if (v.getString("result") == "ignore") {
                assertNull(name, decision)
                continue
            }
            val expected = when (v.getString("avatar_action")) {
                "download" -> ProfileShare.AvatarAction.Download(ref)
                "clear" -> ProfileShare.AvatarAction.Clear
                else -> ProfileShare.AvatarAction.Keep
            }
            assertEquals(name, expected, decision)
        }
    }

    @Test
    fun `a stored reference reads back, and a bad one is no reference`() {
        val ref = ProfileShare.AvatarRef("m-1", "https://x/m-1", ByteArray(32) { 1 }, "image/jpeg")
        assertEquals(ref, ProfileShare.AvatarRef.fromStored(ref.stored()))
        assertNull(ProfileShare.AvatarRef.fromStored(byteArrayOf(0x7f, 0x7f)))
    }
}
