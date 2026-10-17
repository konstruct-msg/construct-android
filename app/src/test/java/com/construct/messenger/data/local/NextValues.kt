package com.construct.messenger.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A store's observation as a queue of what it emitted, for checking that a write made after the
 * watch began reaches it. Collected on a real dispatcher with a real timeout: the stores answer on
 * `Dispatchers.IO`, which a test's virtual clock does not drive.
 */
class NextValues<T>(flow: Flow<T>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val values = Channel<T>(Channel.UNLIMITED)

    init {
        scope.launch { flow.collect { values.send(it) } }
    }

    suspend fun next(): T = withContext(Dispatchers.Default) { withTimeout(5_000) { values.receive() } }

    fun close() = scope.cancel()
}

fun <T> nextValues(flow: Flow<T>) = NextValues(flow)
