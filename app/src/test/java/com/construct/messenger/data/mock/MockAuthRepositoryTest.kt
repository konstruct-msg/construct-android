package com.construct.messenger.data.mock

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockAuthRepositoryTest {
    @Test
    fun startsUnauthenticatedAndCanInitializeIdentity() = runTest {
        val repository = MockAuthRepository()

        assertFalse(repository.authState.value.isInitialized)

        repository.initializeIdentity()

        assertTrue(repository.authState.value.isInitialized)
    }
}
