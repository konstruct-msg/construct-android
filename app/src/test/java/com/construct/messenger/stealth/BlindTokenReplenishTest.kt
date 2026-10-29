package com.construct.messenger.stealth

import com.construct.messenger.stealth.BlindTokenService.Companion.BANK_TARGET
import com.construct.messenger.stealth.BlindTokenService.Companion.BATCH_SIZE
import com.construct.messenger.stealth.BlindTokenService.Outcome
import com.construct.messenger.stealth.BlindTokenService.Replenish
import com.construct.messenger.stealth.BlindTokenService.Replenish.Bootstrap
import com.construct.messenger.stealth.BlindTokenService.Replenish.Gate
import io.grpc.Status
import io.grpc.StatusException
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as iOS `BlindTokenService` tests: the wallet's decisions, apart from the RPC. */
class BlindTokenReplenishTest {
    private val now = 1_000_000_000_000L
    private val later = now + 60_000

    /** Mutation: check pacing before the empty wallet — a burst is held by our own politeness (iOS 2026-08-04). */
    @Test
    fun `an empty wallet outranks pacing, never a back-off`() {
        assertEquals(Gate.START, Replenish.gate(0, running = false, pacingUntilMs = later, backoffUntilMs = 0, nowMs = now))
        assertEquals(Gate.BLOCKED_BY_PACING, Replenish.gate(5, running = false, pacingUntilMs = later, backoffUntilMs = 0, nowMs = now))
        assertEquals(Gate.BLOCKED_BY_BACKOFF, Replenish.gate(0, running = false, pacingUntilMs = 0, backoffUntilMs = later, nowMs = now))
        assertEquals(Gate.START, Replenish.gate(5, running = false, pacingUntilMs = now, backoffUntilMs = now, nowMs = now))
    }

    /** Mutation: start a second batch while one runs — the issuer's hourly cap spent twice as fast. */
    @Test
    fun `a running batch is waited for, not doubled`() {
        assertEquals(Gate.WAIT_FOR_IN_FLIGHT, Replenish.gate(0, running = true, pacingUntilMs = 0, backoffUntilMs = later, nowMs = now))
        assertEquals(Bootstrap.SKIP, Replenish.bootstrapAction(0, running = true, backoffUntilMs = 0, nowMs = now))
    }

    /** Mutation: drop the bank — a wallet of 40 pulls nothing until it is empty, the defect. */
    @Test
    fun `a cold wallet fills, a low one banks, a full one rests`() {
        assertEquals(Bootstrap.COLD_START, Replenish.bootstrapAction(9, running = false, backoffUntilMs = 0, nowMs = now))
        assertEquals(Bootstrap.BANK, Replenish.bootstrapAction(40, running = false, backoffUntilMs = 0, nowMs = now))
        assertEquals(Bootstrap.SKIP, Replenish.bootstrapAction(BANK_TARGET, running = false, backoffUntilMs = 0, nowMs = now))
        assertEquals(Bootstrap.SKIP, Replenish.bootstrapAction(0, running = false, backoffUntilMs = later, nowMs = now))
    }

    @Test
    fun `a cold start pulls as many batches as the bank has room for`() {
        assertEquals(6, Replenish.coldStartBatchCount(0, BANK_TARGET, BATCH_SIZE))
        assertEquals(6, Replenish.coldStartBatchCount(1, BANK_TARGET, BATCH_SIZE))
        assertEquals(1, Replenish.coldStartBatchCount(119, BANK_TARGET, BATCH_SIZE))
        assertEquals(0, Replenish.coldStartBatchCount(BANK_TARGET, BANK_TARGET, BATCH_SIZE))
    }

    /** Mutation: a flat hour for the cap — a refusal at 14:55 stays dark until 15:55. */
    @Test
    fun `the hourly cap waits for the next UTC hour, a blip two minutes`() {
        val at1455 = 14 * 3_600_000L + 55 * 60_000L
        assertEquals(15 * 3_600_000L, Replenish.backoffUntil(Outcome.RATE_LIMITED, at1455))
        assertEquals(16 * 3_600_000L, Replenish.backoffUntil(Outcome.RATE_LIMITED, 15 * 3_600_000L))
        assertEquals(now + 120_000L, Replenish.backoffUntil(Outcome.TRANSPORT, now))
        assertEquals(now + 3_600_000L, Replenish.backoffUntil(Outcome.SERVER_DISABLED, now))
    }

    @Test
    fun `the issuer's answers are told apart`() {
        assertEquals(Outcome.RATE_LIMITED, Replenish.classify(StatusException(Status.RESOURCE_EXHAUSTED)))
        assertEquals(Outcome.SERVER_DISABLED, Replenish.classify(StatusException(Status.UNAVAILABLE.withDescription("Token issuance not configured"))))
        assertEquals(Outcome.TRANSPORT, Replenish.classify(StatusException(Status.UNAVAILABLE.withDescription("io exception"))))
        assertEquals(Outcome.UNAUTHENTICATED, Replenish.classify(StatusException(Status.UNAUTHENTICATED)))
        assertEquals(Outcome.VERIFY_REJECTED, Replenish.classify(BlindTokenService.IssuanceRejected("x")))
        assertEquals(Outcome.TRANSPORT, Replenish.classify(java.io.IOException("reset")))
    }
}
