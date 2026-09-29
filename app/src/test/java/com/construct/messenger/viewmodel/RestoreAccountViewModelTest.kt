package com.construct.messenger.viewmodel

import com.construct.messenger.data.mock.MockAuthRepository
import com.construct.messenger.test.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RestoreAccountViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val phrase = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"

    @Test
    fun pastedPhraseFillsTheGridFromTheCell() {
        val viewModel = RestoreAccountViewModel(MockAuthRepository())

        viewModel.setWord(0, "  Alpha bravo charlie\ndelta echo foxtrot golf hotel india juliet kilo lima ")

        assertEquals(phrase, viewModel.uiState.value.phrase)
    }

    @Test
    fun pasteIntoALaterCellStopsAtTheLastCell() {
        val viewModel = RestoreAccountViewModel(MockAuthRepository())

        viewModel.setWord(10, "kilo lima mike november")

        val words = viewModel.uiState.value.words
        assertEquals(listOf("kilo", "lima"), words.takeLast(2))
        assertTrue(words.take(10).all { it.isEmpty() })
    }

    @Test
    fun submitNeedsTheAccountAndAllTwelveWords() {
        val viewModel = RestoreAccountViewModel(MockAuthRepository())
        viewModel.setWord(0, phrase)
        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.setIdentifier("alice")
        assertTrue(viewModel.uiState.value.canSubmit)

        viewModel.setWord(5, "")
        assertFalse(viewModel.uiState.value.canSubmit)
    }
}
