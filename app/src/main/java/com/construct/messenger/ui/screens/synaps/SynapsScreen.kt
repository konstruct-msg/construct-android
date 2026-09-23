package com.construct.messenger.ui.screens.synaps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.model.Contact
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSearchBar
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.SynapsViewModel

@Composable
fun SynapsScreen(
    onNavigateToChat: (String) -> Unit,
    viewModel: SynapsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.lastMintedLink) {
        val link = uiState.lastMintedLink ?: return@LaunchedEffect
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("invite", link))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .padding(top = 24.dp)
    ) {
        CTNavBar(
            title = stringResource(R.string.nav_synaps),
            trailingIcon = Icons.Default.Share,
            onTrailingAction = { viewModel.shareInvite() },
        )

        CTSearchBar(
            query = uiState.query,
            onQueryChange = viewModel::onQueryChange,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Column(
            modifier = Modifier.padding(horizontal = CTLayout.edgePad)
        ) {
            CTTextField(
                placeholder = stringResource(R.string.synaps_paste_placeholder),
                value = uiState.paste,
                onValueChange = viewModel::onPasteChange,
            )
            Spacer(Modifier.height(Spacing.small))
            CTButton(
                label = stringResource(R.string.synaps_accept),
                onClick = { viewModel.accept() },
                enabled = uiState.paste.isNotBlank() && !uiState.busy,
            )
            if (uiState.query.isNotBlank()) {
                Spacer(Modifier.height(Spacing.small))
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

        if (uiState.filtered.isEmpty()) {
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
                    ContactRow(contact = contact, onClick = { onNavigateToChat(contact.userId) })
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val title = if (contact.username.isNotBlank()) "@${contact.username}" else contact.displayName
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
