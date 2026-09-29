package com.construct.messenger.viewmodel

import com.construct.messenger.data.mock.MockChatsRepository
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposesMockStreamList() = runTest {
        val viewModel = MainViewModel(
            MockChatsRepository(),
            object : com.construct.messenger.data.repository.ConnectionRepository {
                override val status = kotlinx.coroutines.flow.MutableStateFlow(com.construct.messenger.ui.components.ConnectionStatus.CONNECTED)
                override val lastHeartbeatAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
            },
        )

        assertTrue(viewModel.uiState.value.chats.isNotEmpty())
        assertEquals("test_contact", viewModel.uiState.value.suggestedContactId)
    }
}
