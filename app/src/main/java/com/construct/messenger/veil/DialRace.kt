package com.construct.messenger.veil

import java.io.Closeable
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One blocking dial, and a second beside it when the first stays silent for [silenceMs]; the first
 * answer wins, the other is closed, and nothing is waited for past [totalMs]. **Canon:** iOS
 * `HedgedAttempt` with `VeilFrontExternalDialer.silenceBeforeSecondDial` / `handshakeTimeout`.
 *
 * A failure before the second dial started is the answer: this is for silence, not for retries.
 * After that the race fails once both have, with the first error, or at [totalMs].
 *
 * Until 2026-11-23 the native dial had no bound past the TCP connect: a handshake the network
 * swallowed held the front for 44 and 62 s while the next front was up in 0.3 s (todo 328).
 */
internal class DialRace<T : Any>(
    private val silenceMs: Long = SILENCE_MS,
    private val totalMs: Long = TOTAL_MS,
    private val onSilence: () -> Unit = {},
    /** An answer that came after the race was over. */
    private val discard: (T) -> Unit,
) {
    /**
     * Runs [attempt] on its own thread. It hands the socket it opens to its argument before it
     * blocks on it, so the race can close it — the only thing that wakes a blocked handshake.
     */
    fun run(attempt: (opened: (Closeable) -> Unit) -> T): T {
        val answers = LinkedBlockingQueue<Result<T>>()
        val opened = mutableListOf<Closeable>()
        var over = false
        val startedNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedNs) / 1_000_000

        fun dial() = thread(name = "veil-front-dial", isDaemon = true) {
            val answer = runCatching {
                attempt { socket ->
                    val late = synchronized(opened) {
                        if (!over) opened += socket
                        over
                    }
                    // Opened after the race was over: not to be dialed at all.
                    if (late) socket.close()
                }
            }
            val late = synchronized(opened) {
                if (!over) answers.put(answer)
                over
            }
            if (late) answer.onSuccess(discard)
        }

        var running = 1
        var hedged = false
        var firstError: Throwable? = null
        try {
            dial()
            while (true) {
                val waitMs = (if (hedged) totalMs else silenceMs) - elapsedMs()
                val answer = answers.poll(waitMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                when {
                    answer == null && !hedged -> {
                        hedged = true
                        onSilence()
                        running++
                        dial()
                    }
                    answer == null -> throw firstError ?: SocketTimeoutException("no handshake in ${totalMs}ms")
                    answer.isSuccess -> return answer.getOrThrow().also { won -> settle(opened, answers, won) { over = true } }
                    else -> {
                        firstError = firstError ?: answer.exceptionOrNull()
                        if (--running == 0) throw firstError!!
                    }
                }
            }
        } finally {
            settle(opened, answers, null) { over = true }
        }
    }

    /** Ends the race: closes every socket but the winner's, and discards an answer already queued. */
    private fun settle(opened: MutableList<Closeable>, answers: LinkedBlockingQueue<Result<T>>, won: T?, end: () -> Unit) {
        val losers = synchronized(opened) {
            end()
            opened.filter { it !== won }.also { opened.clear() }
        }
        generateSequence { answers.poll() }.forEach { it.onSuccess(discard) }
        losers.forEach { runCatching { it.close() } }
    }

    companion object {
        /** 188 of 192 native handshakes in the field were ready within this (iOS exports, todo 328). */
        const val SILENCE_MS = 3_000L
        const val TOTAL_MS = 8_000L
    }
}
