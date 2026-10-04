package com.construct.messenger.recovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.construct.messenger.data.local.KeystoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vault against the real Keystore. Emulator only (never the owner's phone), and in two steps
 * with the screen lock changed between them by the shell:
 *
 * ```
 * adb -s emulator-5554 shell locksettings set-pin 1111
 * adb -s emulator-5554 shell am instrument -w -e class \
 *   com.construct.messenger.recovery.RecoveryPhraseVaultInstrumentedTest#heldBehindTheLock \
 *   com.construct.messenger.test/androidx.test.runner.AndroidJUnitRunner
 * adb -s emulator-5554 shell locksettings clear --old 1111
 * … #lostOnceTheLockIsRemoved
 * ```
 *
 * The first leaves a held phrase for a test account; the second finds its key destroyed by the
 * platform and reports [HeldPhrase.LOST] instead of looking as if all were well.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPhraseVaultInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = RecoveryPhraseVault(context, KeystoreManager(context))

    @Test
    fun heldBehindTheLock() {
        assertTrue("needs a screen lock", vault.canHold)
        assertTrue(vault.storePending(PHRASE, ACCOUNT))
        assertTrue(vault.promotePending(ACCOUNT))
        assertNull("moved, not copied", vault.pendingPhrase(ACCOUNT))
        assertEquals(HeldPhrase.HELD, vault.held(ACCOUNT))
        assertEquals(HeldPhrase.NONE, vault.held("someone-else"))
        // Opening needs the person: on API 30+ a cipher for the prompt, nothing without it.
        assertTrue(vault.unlock() is RecoveryPhraseVault.Unlock.Prompt)
    }

    @Test
    fun lostOnceTheLockIsRemoved() {
        try {
            assertEquals(HeldPhrase.LOST, vault.held(ACCOUNT))
            assertEquals(RecoveryPhraseVault.Unlock.Lost, vault.unlock())
            // Stays said after the first look.
            assertEquals(HeldPhrase.LOST, vault.held(ACCOUNT))
        } finally {
            vault.forgetHeld()
        }
        assertEquals(HeldPhrase.NONE, vault.held(ACCOUNT))
    }

    private companion object {
        const val ACCOUNT = "vault-instrumented-test"
        const val PHRASE = "abandon ability able about above absent absorb abstract absurd abuse access accident"
    }
}
