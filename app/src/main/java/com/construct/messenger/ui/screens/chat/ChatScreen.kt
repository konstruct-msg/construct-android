package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.MessageBubble
import com.construct.messenger.ui.components.MessageInputView
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.viewmodel.ChatViewModel

@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .imePadding()
            .padding(top = 24.dp)
    ) {
        CTNavBar(
            title = uiState.title,
            showBack = true,
            onBack = onNavigateBack,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            state = listState,
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }

        MessageInputView(
            value = uiState.draft,
            onValueChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            enabled = !uiState.sending,
        )
    }
}
