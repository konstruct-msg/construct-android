package com.construct.messenger.veil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One tunnel start at a time, each beginning by taking down whatever is up. There is one native
 * listener and one rustls coordinator; two starts overlapping wrote the same listener fields, and
 * the earlier listener was left open with nobody holding it. And a superseded start answering
 * "up" was stopped by the router with a stop that reached whichever tunnel was current by then —
 * the newer one too. With starts in series and each replacing the last, a superseded result is
 * always taken down by what came after it, never by a stop aimed at it (TODO 164, item 4).
 */
internal class SerialStart(private val stopCurrent: () -> Unit) {
    private val lock = Mutex()

    suspend fun <T> run(start: suspend () -> T): T = lock.withLock {
        stopCurrent()
        start()
    }
}

/**
 * A blocking dial run to its end even when the start is cancelled, so what it brought up is known.
 * Cancelled meanwhile, the result is [teardown] and the cancellation goes on. Before this a
 * cancelled start lost the result: `withContext` hands its value back to the caller's dispatcher
 * with a prompt cancellation check — `NonCancellable` inside does not change that — so it threw on
 * the way out, with the listener the dial had just bound still open and nothing left to name it.
 * A start cancelled before the dial does not dial.
 */
internal suspend fun <T : Any> dialKeepingResult(dial: () -> T?, teardown: (T) -> Unit): T? {
    currentCoroutineContext().ensureActive()
    var brought: T? = null
    try {
        val result = withContext(NonCancellable + Dispatchers.IO) { dial().also { brought = it } }
        currentCoroutineContext().ensureActive()
        return result
    } catch (e: CancellationException) {
        brought?.let(teardown)
        throw e
    }
}
