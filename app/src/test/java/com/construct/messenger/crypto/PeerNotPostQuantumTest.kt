package com.construct.messenger.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.construct_core.CryptoException
import uniffi.construct_core.PqxdhRefusal

/**
 * The refusal is a type since core 0.62, and a text search no longer finds it: the variant's
 * `message` is `reason=HYBRID_IDENTITY_MISSING`, with no `PQ_REQUIRED` in it.
 */
class PeerNotPostQuantumTest {
    @Test
    fun theCoresRefusalIsRecognised() {
        assertTrue(CryptoManager.isPeerNotPostQuantum(CryptoException.PqRequired(PqxdhRefusal.HYBRID_IDENTITY_MISSING)))
    }

    @Test
    fun anotherFailureToOpenIsNot() {
        assertFalse(CryptoManager.isPeerNotPostQuantum(CryptoException.SessionInitializationFailed("bad bundle")))
        assertFalse(CryptoManager.isPeerNotPostQuantum(IllegalStateException("PQ_REQUIRED")))
    }
}
