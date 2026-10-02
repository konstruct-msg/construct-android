package com.construct.messenger.util

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * The KNST frame against construct-protos `conformance/knst_frame.json` (vendored into test
 * resources) — the core and iOS read the same file. Since core 0.31 the core writes and reads the
 * frame ([KnstFrame] calls it, loaded on this machine from `app/src/test/host/`), so this checks
 * the wrapper and the numbers callers budget by, not a second implementation.
 */
class KnstFrameConformanceTest {
    private val vectors =
        JSONObject(javaClass.classLoader!!.getResource("conformance/knst_frame.json")!!.readText())
    private val cases = vectors.getJSONArray("cases")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Mutation: read [KnstFrame.Chunk.index] from `totalChunks` — `chunk_of_two_is_not_control` reddens. */
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
     * padding cut off. Mutation: let [KnstFrame.Chunk.body] return the whole payload —
     * `padded_body_is_cut_to_length` reddens.
     */
    @Test
    fun `a control frame's body is what the vector says`() {
        for (i in 0 until cases.length()) {
            val v = cases.getJSONObject(i)
            if (!v.getBoolean("is_frame")) continue
            val name = v.getString("name")
            val chunk = KnstFrame.parse(hex(v.getString("frame")))!!
            val body = chunk.body()?.takeIf { chunk.index == 0 && chunk.total == 1 }
            if (v.getBoolean("control")) {
                assertArrayEquals(name, hex(v.getString("body")), body)
            } else {
                assertNull(name, body)
            }
        }
    }

    /** A body splits into exactly the vector's frames, and one past the limit is refused. */
    @Test
    fun `a body splits as the vector says`() {
        val id = UUID.fromString(vectors.getString("message_id"))
        val encode = vectors.getJSONArray("encode")
        for (i in 0 until encode.length()) {
            val v = encode.getJSONObject(i)
            val name = v.getString("name")
            val payload = ByteArray(v.getInt("payload_len")) { (it % 251).toByte() }
            if (v.isNull("frames")) {
                assertThrows(name, IllegalArgumentException::class.java) { KnstFrame.chunks(payload, v.getInt("content_type"), id) }
                continue
            }
            val frames = v.getJSONArray("frames")
            val got = KnstFrame.chunks(payload, v.getInt("content_type"), id)
            assertEquals(name, frames.length(), got.size)
            for (f in 0 until frames.length()) assertArrayEquals("$name[$f]", hex(frames.getString(f)), got[f])
        }
    }

    /** What callers budget by is what the core splits by. Mutation: MAX_PAYLOAD 3769 — reddens. */
    @Test
    fun `the budget numbers are the core's`() {
        assertEquals(vectors.getInt("chunk_payload_size"), KnstFrame.MAX_PAYLOAD)
        assertEquals(vectors.getInt("max_chunks"), KnstFrame.MAX_CHUNKS)
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
