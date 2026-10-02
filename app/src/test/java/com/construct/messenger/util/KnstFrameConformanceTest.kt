package com.construct.messenger.util

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * The KNST header against construct-protos `conformance/knst_frame.json` (vendored into test
 * resources) — the core and iOS read the same file. Since core 0.29 the core names a call signal by
 * byte 5 of this frame, so the app and the core must agree on what a frame and a control frame are.
 */
class KnstFrameConformanceTest {
    private val cases =
        JSONObject(javaClass.classLoader!!.getResource("conformance/knst_frame.json")!!.readText()).getJSONArray("cases")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Mutation: drop the version check in [KnstFrame.parse] — `wrong_version` reddens. */
    @Test
    fun `a frame is read as the vector says`() {
        for (i in 0 until cases.length()) {
            val v = cases.getJSONObject(i)
            val name = v.getString("name")
            val chunk = KnstFrame.parse(hex(v.getString("frame")))
            if (!v.getBoolean("is_frame")) {
                assertNull(name, chunk)
                continue
            }
            assertNotNull(name, chunk)
            assertEquals(name, v.getInt("content_type"), chunk!!.contentType)
            assertEquals(name, UUID.fromString(v.getString("message_id")), chunk.messageId)
            assertEquals(name, v.getInt("chunk_index"), chunk.index)
            assertEquals(name, v.getInt("total_chunks"), chunk.total)
            assertEquals(name, v.getInt("plaintext_length"), chunk.length)
        }
    }

    /**
     * A control frame is one whole chunk whose declared length fits; its body is that many bytes,
     * padding cut off. Mutation: let [IncomingPlaintext.knstPayload] return the whole payload —
     * `padded_body_is_cut_to_length` reddens.
     */
    @Test
    fun `a control frame's body is what the vector says`() {
        for (i in 0 until cases.length()) {
            val v = cases.getJSONObject(i)
            if (!v.getBoolean("is_frame")) continue
            val name = v.getString("name")
            val frame = hex(v.getString("frame"))
            val chunk = KnstFrame.parse(frame)!!
            val body = IncomingPlaintext.knstPayload(frame)?.takeIf { chunk.index == 0 && chunk.total == 1 }
            if (v.getBoolean("control")) {
                assertArrayEquals(name, hex(v.getString("body")), body)
            } else {
                assertNull(name, body)
            }
        }
    }

    /** What Android sends reads back as the vector it equals. */
    @Test
    fun `a whole frame packs as the vector`() {
        for (i in 0 until cases.length()) {
            val v = cases.getJSONObject(i)
            val name = v.getString("name")
            if (!v.getBoolean("is_frame") || !v.getBoolean("control") || name.startsWith("padded")) continue
            val packed = KnstFrame.pack(hex(v.getString("body")), v.getInt("content_type"), UUID.fromString(v.getString("message_id")))
            assertArrayEquals(name, hex(v.getString("frame")), packed)
        }
    }
}
