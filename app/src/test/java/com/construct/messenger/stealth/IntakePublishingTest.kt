package com.construct.messenger.stealth

import com.construct.messenger.stealth.IntakeCredentials.Published
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakePublishingTest {
    private val account = "6f1c2a9e-0b7d-4c55-9a1e-3d2f8b7c6a10"
    private val key = Published.keyId(ByteArray(32) { 1 })
    private fun published(epoch: ULong, keyId: String = key, accountId: String = account) =
        Published(accountId, epoch, keyId)

    /** The current epoch is in it: a fresh install's first incoming message is due today. */
    @Test
    fun `the window is today and six days ahead`() {
        assertEquals((100uL..106uL).toList(), IntakeCredentials.Publishing.window(100uL))
    }

    /** Mutation: publish on every call — an authenticated RPC an hour and on every reconnect; never — the window drains. */
    @Test
    fun `publish once per epoch, and again if the clock went back`() {
        assertTrue(IntakeCredentials.Publishing.shouldPublish(null, published(100uL)))
        assertFalse(IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL)))
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(99uL), published(100uL)))
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(101uL), published(100uL)))
    }

    /** Mutation: compare the epoch alone — the defect, a re-minted key's tags waiting a day. */
    @Test
    fun `a new key publishes in the same epoch`() {
        val other = Published.keyId(ByteArray(32) { 2 })
        assertNotEquals(key, other)
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL, keyId = other)))
    }

    /** Mutation: drop the account from the comparison — another account here would inherit the marker. */
    @Test
    fun `another account publishes, the same one in another case does not`() {
        val other = "0a0b0c0d-0000-4000-8000-000000000001"
        assertTrue(IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL, accountId = other)))
        assertFalse(
            IntakeCredentials.Publishing.shouldPublish(published(100uL), published(100uL, accountId = account.uppercase())),
        )
    }

    /** Mutation: decode a malformed marker as something — an unreadable one must mean "publish". */
    @Test
    fun `the marker survives its own encoding, and a malformed one reads as none`() {
        assertEquals(published(100uL), Published.decode(published(100uL).encoded()))
        assertNull(Published.decode(null))
        assertNull(Published.decode("100"))
        assertNull(Published.decode("$account|100"))
        assertNull(Published.decode("$account|x|$key"))
    }

    private val k = ByteArray(32) { 3 }
    private fun state(g: UInt) = uniffi.construct_core.IntakeKeyState(g, k)
    private fun next(held: uniffi.construct_core.IntakeKeyState?, gen: UInt, live: Boolean) =
        IntakeCredentials.OwnKey.next(held, gen, live)

    /**
     * The table of iOS `IntakeOwnKey.next`. Mutations, one at a time: promote whatever the server
     * says; mint instead of wait on a live account; treat "generation without tags" as live; ask
     * the server with a key of a generation — each reddens a row.
     */
    @Test
    fun `our own key is used, promoted, minted or waited for — by state`() {
        val use = next(state(4u), 9u, true)
        assertTrue(use is IntakeCredentials.OwnKey.Next.Use && use.state.generation == 4u)
        // Generation 1 is a generation: the promoted key never asks the server again.
        assertTrue(next(state(1u), 0u, false) is IntakeCredentials.OwnKey.Next.Use)
        // Migration: a per-device key while the account has no generation becomes generation 1, same key.
        val promoted = next(state(0u), 0u, false) as IntakeCredentials.OwnKey.Next.Promote
        assertEquals(1u, promoted.state.generation)
        assertTrue(promoted.state.key.contentEquals(k))
        assertEquals(IntakeCredentials.OwnKey.Next.Wait, next(state(0u), 3u, true))
        assertEquals(IntakeCredentials.OwnKey.Next.Mint(3u), next(state(0u), 3u, false))
        assertEquals(IntakeCredentials.OwnKey.Next.Wait, next(null, 3u, true))
        assertEquals(IntakeCredentials.OwnKey.Next.Mint(3u), next(null, 3u, false))
        assertEquals(IntakeCredentials.OwnKey.Next.Mint(0u), next(null, 0u, false))
    }

    /** Mutation: little-endian, or reject the 32-byte form — a stored key loses its generation or its key. */
    @Test
    fun `a stored key keeps its generation, and one from before reads as generation 0`() {
        val stored = IntakeCredentials.KeyRecord.encode(state(300u))
        assertEquals(36, stored.size)
        assertEquals(listOf<Byte>(0, 0, 1, 44), stored.take(4))
        val back = IntakeCredentials.KeyRecord.decode(stored)!!
        assertEquals(300u, back.generation)
        assertTrue(back.key.contentEquals(k))
        assertEquals(0u, IntakeCredentials.KeyRecord.decode(k)!!.generation)
        assertNull(IntakeCredentials.KeyRecord.decode(ByteArray(33)))
    }

    /** Mutation: >= — every publish at our own generation would be taken as refused. */
    @Test
    fun `a publish is stale only when the account is above us`() {
        assertTrue(IntakeCredentials.Publishing.refusedAsStale(5u, 4u))
        assertFalse(IntakeCredentials.Publishing.refusedAsStale(4u, 4u))
        assertFalse(IntakeCredentials.Publishing.refusedAsStale(0u, 4u))
    }

    /** Mutation: treat UNIMPLEMENTED as "ask again" — every device on an old key stops publishing until the server deploys. */
    @Test
    fun `an old server reads as an account at generation 0, any other error as unknown`() {
        assertEquals(
            IntakeCredentials.AccountIntake(0u, false),
            IntakeCredentials.AccountIntake.onError(io.grpc.StatusException(io.grpc.Status.UNIMPLEMENTED)),
        )
        assertNull(IntakeCredentials.AccountIntake.onError(io.grpc.StatusException(io.grpc.Status.UNAVAILABLE)))
        assertNull(IntakeCredentials.AccountIntake.onError(java.io.IOException("reset")))
    }

    // --- a rotation (block or remove on this device) ---

    /** Mutation: `held ?: server` — a sibling's newer generation is minted under; ours alone — the server's is. */
    @Test
    fun `a rotation mints above both our generation and the server's`() {
        assertEquals(7u, IntakeCredentials.Rotation.above(7u, 3u))
        assertEquals(9u, IntakeCredentials.Rotation.above(2u, 9u))
        assertEquals(4u, IntakeCredentials.Rotation.above(null, 4u))
        assertEquals(0u, IntakeCredentials.Rotation.above(null, 0u))
    }

    /** Mutation: re-mint on every stale answer — a retry budget; never — a sibling's key the blocked contact may hold stays live. */
    @Test
    fun `a stale publish re-mints once above the generation it names, nothing else does`() {
        val stale = IntakeCredentials.PublishOutcome.Stale(12u)
        assertEquals(12u, IntakeCredentials.Rotation.reMintAbove(stale, alreadyReMinted = false))
        assertNull(IntakeCredentials.Rotation.reMintAbove(stale, alreadyReMinted = true))
        assertNull(IntakeCredentials.Rotation.reMintAbove(IntakeCredentials.PublishOutcome.Failed, alreadyReMinted = false))
        assertNull(IntakeCredentials.Rotation.reMintAbove(IntakeCredentials.PublishOutcome.Stored, alreadyReMinted = false))
    }

    /** Mutation: keep the dropped contact, or our own account — the key goes to the one it was taken from, or to note-to-self. */
    @Test
    fun `the cards go to every remaining contact once, not to the dropped one or to us`() {
        val schedule = IntakeCredentials.Rotation.cardSchedule(
            listOf("a", "B", "b", "dropped", account, "c"),
            dropped = "DROPPED",
            ownAccount = account.uppercase(),
            random = kotlin.random.Random(1),
        )
        assertEquals(listOf("a", "B", "c"), schedule.map { it.first })
    }

    /** Mutation: offset 0 — a batch, which shows the server when and how many; beyond the hour — the lane stays costly longer. */
    @Test
    fun `the cards are spread within the hour, each at its own moment`() {
        val offsets = IntakeCredentials.Rotation.cardSchedule(
            (1..50).map { "c$it" }, dropped = "x", ownAccount = null, random = kotlin.random.Random(7),
        ).map { it.second }
        assertTrue(offsets.all { it in 0 until IntakeCredentials.Rotation.SPREAD_MS })
        assertTrue("not one moment", offsets.toSet().size > 40)
        assertTrue("spread over the hour, not its first minutes", offsets.max() > IntakeCredentials.Rotation.SPREAD_MS / 2)
    }
}
