package com.construct.messenger.stickers

import com.construct.messenger.util.IncomingPlaintext
import com.construct.messenger.util.MediaWire
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.StickerPack.StickerPackManifest

/**
 * Stickers against `construct-protos/conformance` (vendored into test resources) and the packs
 * shipped in the APK. A reference one client accepts and another refuses is a picture on one
 * screen and nothing on the other; a pack id computed differently refuses every real pack.
 */
class StickerConformanceTest {

    private fun vectors(name: String) =
        JSONObject(javaClass.classLoader!!.getResource("conformance/$name")!!.readText())

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val pinned = listOf(java.util.Base64.getDecoder().decode("kDGyaafEFExKTdB7MJXl4xYf0pFnxEg7bZJh7BDP9LI="))

    @Test fun `every StickerRef vector decodes to its fields and back to its bytes`() {
        val list = vectors("knst_sticker_ref.json").getJSONArray("vectors")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val content = MessageContent.parseFrom(hex(v.getString("message_content_hex")))
            val ref = StickerReference.fromWire(content.sticker)
            assertNotNull(v.getString("id"), ref)
            assertArrayEquals(hex(v.getString("pack_id_hex")), ref!!.packId)
            assertEquals(v.getInt("index"), ref.index)
            assertEquals(v.getString("emoji"), ref.emoji)
            val again = MessageContent.newBuilder().setSticker(ref.toWire()).build().toByteArray()
            assertArrayEquals(v.getString("id"), hex(v.getString("message_content_hex")), again)
        }
    }

    @Test fun `the validator answers every case as the vector file does`() {
        val file = vectors("knst_sticker_ref.json")
        val cases = file.optJSONArray("cases") ?: return
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val accepted = StickerReference.of(hex(c.getString("pack_id_hex")), 0, c.getString("emoji")) != null
            assertEquals(c.toString(), c.getBoolean("valid"), accepted)
        }
    }

    @Test fun `pack_id is the hash of the canonical bytes`() {
        val v = vectors("knst_sticker_pack.json")
        val manifest = StickerPackManifest.parseFrom(hex(v.getString("manifest_hex")))
        assertArrayEquals(hex(v.getString("canonical_hex")), StickerPack.canonicalBytes(manifest))
        assertArrayEquals(hex(v.getString("pack_id_hex")), StickerPack.packId(manifest))
        // The fixture is unsigned: refused, except where a fixture is allowed.
        try {
            StickerPack.verify(manifest.toByteArray(), pinned)
            fail("an unsigned manifest was accepted")
        } catch (e: StickerPack.VerifyError) {
            assertEquals(StickerPack.VerifyError.Unsigned, e)
        }
        assertEquals(4, StickerPack.verify(manifest.toByteArray(), pinned, allowUnsigned = true).stickers.size)
    }

    private val assets = File("src/main/assets/stickers")

    /** What the phone seeds from: each bundled manifest verifies against the pinned key, each file its hash. */
    @Test fun `the bundled packs are signed by the pinned key and their files match`() {
        val manifests = assets.listFiles { f -> f.name.startsWith("sticker-pack-") && f.name.endsWith(".pb") }.orEmpty()
        assertEquals(2, manifests.size)
        for (m in manifests) {
            val pack = StickerPack.verify(m.readBytes(), pinned)
            for (entry in pack.stickers) {
                val blob = File(assets, "${entry.hex}.webp").readBytes()
                assertEquals(entry.byteLen, blob.size)
                assertArrayEquals(entry.sha256, MessageDigest.getInstance("SHA-256").digest(blob))
            }
        }
        val momo = manifests.single { it.name.contains("0002") }
        val pack = StickerPack.verify(momo.readBytes(), pinned)
        assertEquals("ffd55c86b68fcbd270caff87365dba6ec9cd1874ff28fefcf0345fd41db75a59", pack.hex)
        assertEquals(14, pack.stickers.size)
    }

    /** A pack whose manifest changed by one byte is not that pack, and not trusted. */
    @Test fun `a tampered manifest is refused`() {
        val bytes = File(assets, "sticker-pack-0002-momo-cat.pb").readBytes()
        val manifest = StickerPackManifest.parseFrom(bytes)
        val retitled = manifest.toBuilder().setTitle("Momo the dog").build().toByteArray()
        try {
            StickerPack.verify(retitled, pinned)
            fail("a retitled manifest was accepted")
        } catch (e: StickerPack.VerifyError) {
            assertEquals(StickerPack.VerifyError.PackIdMismatch, e)
        }
        val resigned = manifest.toBuilder()
            .setTitle("Momo the dog")
            .setPackId(com.google.protobuf.ByteString.copyFrom(StickerPack.packId(manifest.toBuilder().setTitle("Momo the dog").build())))
            .build().toByteArray()
        try {
            StickerPack.verify(resigned, pinned)
            fail("a manifest with another's signature was accepted")
        } catch (e: StickerPack.VerifyError) {
            assertEquals(StickerPack.VerifyError.BadSignature, e)
        }
    }

    @Test fun `a received sticker is a bubble, a corrupt one is nothing`() {
        val ok = MessageContent.parseFrom(hex("5a2a0a2082e2106ca3d8e72c910c003c0ce8ce9fa8a5a2d3ad41d021ea35d34d21a1a8c410041a04f09f9880"))
        val decoded = IncomingPlaintext.decode(ok.toByteArray())
        assertTrue(decoded.isUserVisible)
        assertEquals(MediaWire.KIND_STICKER, decoded.media?.kind)
        val stored = MediaWire.decode(decoded.media!!.kind, decoded.media!!.bytes) as com.construct.messenger.data.model.MessageMedia.Sticker
        assertEquals(4, stored.ref.index)

        val shortPack = MessageContent.newBuilder().setSticker(
            ok.sticker.toBuilder().setPackId(com.google.protobuf.ByteString.copyFrom(ByteArray(31))),
        ).build()
        assertFalse(IncomingPlaintext.decode(shortPack.toByteArray()).isUserVisible)
        val longEmoji = MessageContent.newBuilder().setSticker(ok.sticker.toBuilder().setEmoji("x".repeat(33))).build()
        assertNull(IncomingPlaintext.decode(longEmoji.toByteArray()).media)
    }
}
