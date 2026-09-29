package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.ConnectionStatus
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.viewmodel.SettingsViewModel

/**
 * Whether the stream is up, and to what. **Canon:** iOS `NetworkSettingsView` — its status half.
 * The rest of that screen is VEIL (censorship protection), which Android does not have yet; it
 * is left out rather than shown as rows that do nothing.
 */
@Composable
fun NetworkScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.settings_row_network), showBack = true, onBack = onNavigateBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            CTSettingsSectionHeader(title = stringResource(R.string.network_status))
            CTSectionGroup {
                CTSettingsRow(
                    label = stringResource(R.string.network_status).uppercase(),
                    value = stringResource(ui.connection.label()),
                    valueColor = CTColor.textDim,
                    icon = Icons.Default.Public,
                    status = ui.connection.toStatus(),
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.network_server).uppercase(),
                    value = "${GrpcClient.HOST}:${GrpcClient.PORT}",
                    valueColor = CTColor.textDim,
                    icon = Icons.Default.Dns,
                )
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.network_transport).uppercase(),
                    // Technical names, the same in every language.
                    value = "gRPC · HTTP/2 · TLS",
                    valueColor = CTColor.textDim,
                    icon = Icons.Default.Lock,
                )
            }
        }
    }
}

private fun ConnectionStatus.label(): Int = when (this) {
    ConnectionStatus.CONNECTED -> R.string.network_connected
    ConnectionStatus.CONNECTING -> R.string.network_connecting
    ConnectionStatus.DISCONNECTED -> R.string.network_offline
    ConnectionStatus.UNKNOWN -> R.string.network_unknown
}
