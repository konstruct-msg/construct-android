package com.construct.messenger.ui.screens.chats

import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.foundation.background
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.components.TabIcons
import com.construct.messenger.ui.components.CTMatrixBackground
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.construct.messenger.ui.components.ConnectionStatusIndicator
import com.construct.messenger.ui.components.ctBackground
import com.construct.messenger.ui.theme.CTLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTSearchBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.MainViewModel

/** What the plain iOS `List` adds above its first row, measured side by side. */
private val LIST_TOP_INSET = 44.dp

@Composable
fun ChatsListScreen(
    onNavigateToChat: (String) -> Unit,
    onFindPeople: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onShowMyQr: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().ctBackground()) {
    // iOS draws the lattice behind the rows as well as the noise (`ChatsListView`).
    CTMatrixBackground(modifier = Modifier.fillMaxSize())
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge: the screen keeps itself clear of the bars. Inside MainTabView the
            // Scaffold has already padded and consumed them, so these add nothing there.
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Canon: iOS ChatsListView nav bar — no title; the connection dot, centred on the avatar
        // column (40), and the scanner (qrcode.viewfinder), one tap away.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CTLayout.navBarHeight)
                .padding(horizontal = CTLayout.edgePad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                ConnectionStatusIndicator(status = uiState.connection)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = stringResource(R.string.scan_qr_code),
                tint = CTColor.accent,
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onScanQr)
                    .padding(12.dp),
            )
        }

        CTSearchBar(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = CTLayout.edgePad).padding(top = 4.dp, bottom = 8.dp),
        )

        // iOS `filteredChats`: name, @username or the last message, case-insensitive. An empty
        // result is the same empty state as no chats at all.
        val needle = query.trim()
        val chats = if (needle.isEmpty()) uiState.chats else uiState.chats.filter {
            it.displayName.contains(needle, ignoreCase = true) ||
                it.username.contains(needle, ignoreCase = true) ||
                it.lastMessagePreview.orEmpty().contains(needle, ignoreCase = true)
        }
        if (chats.isEmpty()) {
            EmptyState(onScanQr = onScanQr, onShowMyQr = onShowMyQr, onOpenSynaps = onFindPeople)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                // The plain iOS List's own top inset, so the first row sits where it does there.
                contentPadding = PaddingValues(top = LIST_TOP_INSET),
            ) {
                items(
                    items = chats,
                    key = { it.contactId }
                ) { chat ->
                    ChatRow(
                        chat = chat,
                        onClick = { onNavigateToChat(chat.contactId) }
                    )
                }
            }
        }
    }
}
}

/**
 * No chats yet: where conversations come from. **Canon:** iOS `ChatsListView.streamsEmptyState`
 * — the icon, title and line, then scan a QR, show mine, open Synaps.
 */
@Composable
private fun EmptyState(onScanQr: () -> Unit, onShowMyQr: () -> Unit, onOpenSynaps: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // iOS: 48 above and below, under the list's top inset.
            .padding(top = LIST_TOP_INSET + 48.dp, bottom = 48.dp)
            .padding(horizontal = CTLayout.edgePad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CTLayout.sectionGap),
    ) {
        Icon(
            imageVector = Icons.Outlined.Forum,
            contentDescription = null,
            tint = CTColor.textDim,
            modifier = Modifier.padding(bottom = 4.dp).size(36.dp),
        )
        Text(
            text = stringResource(R.string.chats_empty_title),
            style = ctBold(16),
            color = CTColor.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.chats_empty_subtitle),
            style = ctRegular(13),
            color = CTColor.textDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = CTLayout.sectionGap),
        )
        Column(
            modifier = Modifier
                .padding(top = CTLayout.inlinePad)
                .widthIn(max = 320.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CTLayout.chromeGap),
        ) {
            EmptyAction(Icons.Default.QrCodeScanner, stringResource(R.string.chats_empty_scan_qr), onScanQr)
            EmptyAction(Icons.Default.QrCode, stringResource(R.string.chats_empty_show_qr), onShowMyQr)
            EmptyAction(TabIcons.Synaps, stringResource(R.string.chats_empty_open_synaps), onOpenSynaps)
        }
    }
}

@Composable
private fun EmptyAction(icon: ImageVector, title: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CornerRadius.small)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CTLayout.controlHeight)
            .clip(shape)
            .background(CTColor.bgMsg)
            .border(HairlineBorder, CTColor.noise, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = CTLayout.edgePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTLayout.chromeGap),
    ) {
        Icon(icon, contentDescription = null, tint = CTColor.accent, modifier = Modifier.size(CTLayout.navIconSize))
        Text(
            text = title.uppercase(),
            style = ctBold(12),
            color = CTColor.accent,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(14.dp))
    }
}
