package com.construct.messenger.ui.screens.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTMatrixBackground
import com.construct.messenger.ui.components.FilterSearchBar
import com.construct.messenger.ui.components.ConnectionStatusIndicator
import com.construct.messenger.ui.components.TabIcons
import com.construct.messenger.ui.components.ctBackground
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.viewmodel.MainViewModel

/** What the plain iOS `List` adds above its first row, measured side by side. */
private val LIST_TOP_INSET = 44.dp

@OptIn(ExperimentalMaterial3Api::class)
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
    // The row whose swipe buttons are showing; opening another closes it.
    var openRow by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

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
        // Material's top bar; the dot sits in the navigation slot, moved to the avatar column.
        TopAppBar(
            title = {},
            navigationIcon = {
                Box(
                    modifier = Modifier.padding(start = CTSpace.m).width(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ConnectionStatusIndicator(status = uiState.connection)
                }
            },
            actions = {
                IconButton(onClick = onScanQr) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = stringResource(R.string.scan_qr_code),
                        tint = CTColor.accent,
                    )
                }
            },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        FilterSearchBar(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = CTLayout.edgePad).padding(top = CTSpace.xs, bottom = CTSpace.s),
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
                    SwipeActionsRow(
                        open = openRow == chat.contactId,
                        onOpenChange = { isOpen ->
                            openRow = if (isOpen) chat.contactId else openRow.takeUnless { it == chat.contactId }
                        },
                        leading = SwipeAction(
                            icon = Icons.Outlined.PushPin,
                            label = stringResource(if (chat.isPinned) R.string.chat_unpin else R.string.chat_pin),
                            color = CTColor.textDim,
                            tint = CTColor.onFill,
                            onClick = { viewModel.togglePin(chat) },
                        ),
                        trailing = listOf(
                            SwipeAction(
                                icon = Icons.Outlined.Delete,
                                label = stringResource(R.string.delete),
                                color = CTColor.danger,
                                tint = CTColor.onFill,
                                onClick = { viewModel.deleteChat(chat) },
                            ),
                            SwipeAction(
                                icon = if (chat.unreadCount > 0) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread,
                                label = stringResource(
                                    if (chat.unreadCount > 0) R.string.chat_mark_read else R.string.chat_mark_unread,
                                ),
                                color = CTColor.accentDim,
                                tint = CTColor.onFill,
                                onClick = { viewModel.toggleUnread(chat) },
                            ),
                        ),
                    ) { closeInstead ->
                        Box {
                            ChatRow(
                                chat = chat,
                                onClick = closeInstead ?: { onNavigateToChat(chat.contactId) },
                                onLongClick = { menuFor = chat.contactId },
                            )
                            // iOS `contextMenu`: the same three, reachable without a swipe.
                            DropdownMenu(
                                expanded = menuFor == chat.contactId,
                                onDismissRequest = { menuFor = null },
                                modifier = Modifier.background(CTColor.outMsgBg),
                            ) {
                                ChatMenuItem(
                                    stringResource(if (chat.isPinned) R.string.chat_unpin else R.string.chat_pin),
                                    Icons.Outlined.PushPin,
                                    CTColor.text,
                                ) { menuFor = null; viewModel.togglePin(chat) }
                                ChatMenuItem(
                                    stringResource(if (chat.unreadCount > 0) R.string.chat_mark_read else R.string.chat_mark_unread),
                                    if (chat.unreadCount > 0) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread,
                                    CTColor.text,
                                ) { menuFor = null; viewModel.toggleUnread(chat) }
                                HorizontalDivider(color = CTColor.noise)
                                ChatMenuItem(stringResource(R.string.delete), Icons.Outlined.Delete, CTColor.danger) {
                                    menuFor = null
                                    viewModel.deleteChat(chat)
                                }
                            }
                        }
                    }
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
            // It sits in a List on iOS: on a short screen (landscape) it scrolls instead of
            // squeezing its text to nothing.
            .verticalScroll(rememberScrollState())
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
            modifier = Modifier.padding(bottom = CTSpace.xs).size(CTIcon.overlay),
        )
        Text(
            text = stringResource(R.string.chats_empty_title),
            style = CTFont.ui(16, FontWeight.Bold),
            color = CTColor.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.chats_empty_subtitle),
            style = CTFont.body,
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
            style = CTFont.ui(12, FontWeight.Bold),
            color = CTColor.accent,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(CTIcon.row))
    }
}

@Composable
private fun ChatMenuItem(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = CTFont.ui(14), color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(CTIcon.nav)) },
        onClick = onClick,
    )
}
