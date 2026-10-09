package com.construct.messenger.ui.screens.synaps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.Contact
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.FilterSearchBar
import com.construct.messenger.ui.components.ctBackground
import com.construct.messenger.ui.components.rememberAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.SynapsViewModel

@Composable
fun SynapsScreen(
    onOpenContact: (String) -> Unit,
    onScanQr: () -> Unit = {},
    viewModel: SynapsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    // How much of the screen the bar, search and requests cover: the cloud's centre is the middle
    // of what is left. Measured, not a constant — the requests and the status come and go.
    var topInset by remember { mutableStateOf(0.dp) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .ctBackground()
            // Edge-to-edge: the screen keeps itself clear of the bars. Inside MainTabView the
            // Scaffold has already padded and consumed them, so these add nothing there.
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val cloud = uiState.cloud
        if (cloud.isNotEmpty()) {
            SynapsCloud(
                contacts = cloud,
                metrics = uiState.metrics,
                blockedIds = uiState.blocked.mapTo(HashSet()) { it.userId },
                topInset = topInset,
                onOpen = onOpenContact,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { topInset = with(density) { it.size.height.toDp() } },
        ) {
            CTNavBar(
                title = stringResource(R.string.synaps_title),
                // Scanning only: your own QR lives in Settings → Invite.
                trailingIcon = Icons.Default.QrCodeScanner,
                onTrailingAction = onScanQr,
            )

            FilterSearchBar(
                query = uiState.query,
                onQueryChange = viewModel::onQueryChange,
                modifier = Modifier.padding(horizontal = CTSpace.m, vertical = CTSpace.s),
            )

            Column(
                modifier = Modifier.padding(horizontal = CTLayout.edgePad)
            ) {
                // A pasted invite goes in through the scanner's "Paste invite link", as on iOS;
                // this screen only redeems it (PendingInviteStore) and says how it went below.
                if (uiState.query.isNotBlank()) {
                    Button(
                        onClick = { viewModel.findAndRequest() },
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.synaps_find_request)) }
                }
                uiState.status?.let { message ->
                    Spacer(Modifier.height(CTSpace.s))
                    Text(
                        text = message,
                        style = CTFont.secondary,
                        color = CTColor.textDim,
                    )
                }
            }

            if (uiState.incomingRequests.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.synaps_requests_title),
                    style = CTFont.secondary,
                    color = CTColor.textDim,
                    modifier = Modifier.padding(horizontal = CTLayout.edgePad, vertical = CTSpace.s),
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

            if (cloud.isEmpty()) {
                Text(
                    text = stringResource(R.string.synaps_empty_title),
                    style = CTFont.ui(14),
                    color = CTColor.textDim,
                    modifier = Modifier.padding(CTLayout.edgePad),
                )
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
            .padding(horizontal = CTSpace.m, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CTAvatar(userId = contact.userId, displayName = contact.displayName, image = rememberAvatar(contact.avatar), size = 44.dp)
        Spacer(Modifier.width(CTSpace.m))
        Text(
            text = title,
            style = CTFont.ui(14),
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
    }
}
