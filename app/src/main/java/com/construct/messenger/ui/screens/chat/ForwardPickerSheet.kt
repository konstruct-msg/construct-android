package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.ui.components.FilterSearchBar
import com.construct.messenger.ui.screens.chats.ChatRow
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.MainViewModel

/**
 * Where a forward goes: the chats, searchable as the chat list is. **Canon:** iOS
 * `ForwardPickerView`. The picked chat sends the content itself (`ForwardDrafts`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardPickerSheet(
    onPick: (ChatSummary) -> Unit,
    onDismiss: () -> Unit,
    chats: MainViewModel = hiltViewModel(),
) {
    val state by chats.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CTColor.bg,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.forward_to),
                style = MaterialTheme.typography.titleMedium,
                color = CTColor.text,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad),
            )
            FilterSearchBar(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.padding(horizontal = CTLayout.edgePad).padding(top = CTSpace.s, bottom = CTSpace.s),
            )
            // The chat list's own filter: name, @username or the last message.
            val needle = query.trim()
            val shown = if (needle.isEmpty()) state.chats else state.chats.filter {
                it.displayName.contains(needle, ignoreCase = true) ||
                    it.username.contains(needle, ignoreCase = true) ||
                    it.lastMessagePreview.orEmpty().contains(needle, ignoreCase = true)
            }
            LazyColumn {
                items(shown, key = { it.contactId }) { chat ->
                    ChatRow(chat = chat, onClick = {
                        onPick(chat)
                        onDismiss()
                    })
                }
            }
        }
    }
}
