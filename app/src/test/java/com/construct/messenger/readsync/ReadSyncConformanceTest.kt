package com.construct.messenger.readsync

import com.construct.messenger.util.KnstFrame
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content type 30 against construct-protos `conformance/knst_read_markers.json` and the
 * `own_devices_only` column of `knst_content_types.json` (vendored into test resources) — iOS
 * `ReadSyncTests` reads the same files (TODO 153).
 */
class ReadSyncConformanceTest {
    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResource("conformance/$name.json")!!.readText())

    private val vectors = json("knst_read_markers")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun pair(a: JSONArray) = ReadSync.Boundary.of(a.getLong(0), a.getString(1))

    /** Mutation: encode partners out of order, or drop the id's lowercasing — reddens. */
    @Test
    fun `markers read and write as the vectors say`() {
        val cases = vectors.getJSONArray("decode")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val payload = hex(c.getString("payload"))
            val expected = c.getJSONArray("markers").let { m ->
                (0 until m.length()).map { j ->
                    val o = m.getJSONObject(j)
                    o.getString("partner_user_id") to ReadSync.Boundary(o.getLong("last_read_sent_at_ms"), o.getString("last_read_message_id"))
                }
            }
            val decoded = ReadSync.decode(payload)
            assertEquals(c.getString("name"), expected, decoded)
            assertArrayEquals("${c.getString("name")}: the same bytes back", payload, ReadSync.encode(decoded!!.toMap()))
        }
    }

    /** Mutation: let a marker from a contact apply, or an equal one move the boundary — reddens. */
    @Test
    fun `a marker moves the boundary as the vectors say`() {
        val cases = vectors.getJSONArray("apply")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val held = c.optJSONArray("held")?.let(::pair)
            val outcome = ReadSync.outcome(pair(c.getJSONArray("marker")), held, c.getBoolean("from_own_account"))
            assertEquals(c.getString("name"), c.getString("result"), outcome.name.lowercase())
        }
    }

    @Test
    fun `a boundary covers as the vectors say`() {
        val cases = vectors.getJSONArray("covers")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("name"), c.getBoolean("read"), ReadSync.covers(pair(c.getJSONArray("boundary")), pair(c.getJSONArray("message"))))
        }
    }

    /** Mutation: forget 30 (or 23) among the own-devices-only types — a marker could be sent to a contact. */
    @Test
    fun `own devices only is the table's column`() {
        val types = json("knst_content_types").getJSONArray("types")
        var some = false
        for (i in 0 until types.length()) {
            val row = types.getJSONObject(i)
            val own = row.getBoolean("own_devices_only")
            some = some || own
            assertEquals(row.getString("name"), own, KnstFrame.isOwnDevicesOnly(row.getInt("value")))
        }
        assertTrue(some)
    }
}
