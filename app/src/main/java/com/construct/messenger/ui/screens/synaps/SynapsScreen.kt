package com.construct.messenger.ui.screens.synaps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Text
import com.construct.messenger.ui.components.ctBackground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.Contact
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.CTSearchBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.SynapsViewModel

@Composable
fun SynapsScreen(
    onOpenContact: (String) -> Unit,
    onScanQr: () -> Unit = {},
    viewModel: SynapsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .ctBackground()
            // Edge-to-edge: the screen keeps itself clear of the bars. Inside MainTabView the
            // Scaffold has already padded and consumed them, so these add nothing there.
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        CTNavBar(
            title = stringResource(R.string.synaps_title),
            // Scanning only: your own QR lives in Settings → Invite.
            trailingIcon = Icons.Default.QrCodeScanner,
            onTrailingAction = onScanQr,
        )

        CTSearchBar(
            query = uiState.query,
            onQueryChange = viewModel::onQueryChange,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Column(
            modifier = Modifier.padding(horizontal = CTLayout.edgePad)
        ) {
            // A pasted invite goes in through the scanner's "Paste invite link", as on iOS;
            // this screen only redeems it (PendingInviteStore) and says how it went below.
            if (uiState.query.isNotBlank()) {
                CTButton(
                    label = stringResource(R.string.synaps_find_request),
                    onClick = { viewModel.findAndRequest() },
                    enabled = !uiState.busy,
                )
            }
            uiState.status?.let { message ->
                Spacer(Modifier.height(Spacing.small))
                Text(
                    text = message,
                    style = ctRegular(12),
                    color = CTColor.textDim,
                )
            }
        }

        if (uiState.incomingRequests.isNotEmpty()) {
            Text(
                text = stringResource(R.string.synaps_requests_title),
                style = ctRegular(12),
                color = CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad, vertical = Spacing.small),
            )
            uiState.incomingRequests.forEach { request ->
                ContactRow(
                    contact = Contact(
                        userId = request.fromUserId,
                        displayName = request.displayName,
                        username = request.username,
                    ),
                    onClick = { viewModel.acceptRequest(request) },
                )
            }
        }

        if (uiState.filtered.isEmpty() && uiState.blocked.isEmpty()) {
            Text(
                text = stringResource(R.string.synaps_empty_title),
                style = ctRegular(14),
                color = CTColor.textDim,
                modifier = Modifier.padding(CTLayout.edgePad),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                items(uiState.filtered, key = { it.userId }) { contact ->
                    ContactRow(contact = contact, onClick = { onOpenContact(contact.userId) })
                }
                if (uiState.blocked.isNotEmpty()) {
                    item(key = "blocked-header") {
                        CTSettingsSectionHeader(
                            title = stringResource(R.string.synaps_blocked),
                            color = CTColor.textDim,
                        )
                    }
                    items(uiState.blocked, key = { "blocked-" + it.userId }) { contact ->
                        ContactRow(contact = contact, onClick = { onOpenContact(contact.userId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    // As the chat list: the name as given, the username only when there is none.
    val title = contact.displayName.ifBlank { contact.username }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CTAvatar(userId = contact.userId, displayName = contact.displayName, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = ctRegular(14),
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
    }
}
