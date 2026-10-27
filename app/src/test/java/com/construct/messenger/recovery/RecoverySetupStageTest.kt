package com.construct.messenger.recovery

import com.construct.messenger.recovery.RecoveryViewModel.Companion.setupStage
import com.construct.messenger.test.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Where the recovery screen opens. **Canon:** iOS `RecoverySetupModeTests` (#381). The defect it
 * guards: an account whose key is on the server and whose phrase is not on this device was offered
 * a new phrase, which the server refused only after it had been copied (the key is set once).
 */
class RecoverySetupStageTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val set = RecoveryStatus(isSetup = true, fingerprint = "A1B2 C3D4", isHybrid = true)
    private val none = RecoveryStatus(isSetup = false, fingerprint = null)

    /** Mutation: ignore the server's isSetup — an account with a key is offered a new phrase. */
    @Test
    fun anAccountWithAKeyIsNeverOfferedANewPhrase() {
        assertEquals(RecoveryStage.AlreadySet("A1B2 C3D4"), setupStage(HeldPhrase.NONE, hasAddress = true, status = set))
        assertEquals(RecoveryStage.Confirm, setupStage(HeldPhrase.NONE, hasAddress = false, status = set))
        assertEquals(RecoveryStage.Explain, setupStage(HeldPhrase.NONE, hasAddress = false, status = none))
    }

    /** Mutation: offline taken as "no key" (Android's old fallback) — a new phrase for any account. */
    @Test
    fun noAnswerOffersNothing() {
        assertEquals(RecoveryStage.Unavailable, setupStage(HeldPhrase.NONE, hasAddress = false, status = null))
        assertEquals(RecoveryStage.AlreadySet(null), setupStage(HeldPhrase.NONE, hasAddress = true, status = null))
    }

    /** Mutation: the server's answer checked before the held phrase — the copy is never offered. */
    @Test
    fun aPhraseOnThisDeviceWins() {
        assertEquals(RecoveryStage.BackupIntro, setupStage(HeldPhrase.HELD, hasAddress = true, status = set))
        assertEquals(RecoveryStage.BackupLost, setupStage(HeldPhrase.LOST, hasAddress = true, status = set))
    }

    /** An Ed25519-only key is upgraded from the typed phrase, not replaced (todo 222). */
    @Test
    fun anEd25519KeyAsksForThePhraseOnce() {
        assertEquals(RecoveryStage.Confirm, setupStage(HeldPhrase.NONE, hasAddress = true, status = set.copy(isHybrid = false)))
    }

    /** Mutation: drop the guard in startSetup — a phrase is made outside the "no key" flow. */
    @Test
    fun startSetupMakesNoPhraseOutsideTheCreateFlow() {
        val repository: RecoveryRepository = mock<RecoveryRepository>().also { whenever(it.hasOwnAddress()).thenReturn(true) }
        val vm = RecoveryViewModel(repository, mock(), mock())

        vm.startSetup()

        assertEquals(RecoveryStage.Ready, vm.uiState.value.stage)
        verify(repository, never()).newPhrase()
    }
}
