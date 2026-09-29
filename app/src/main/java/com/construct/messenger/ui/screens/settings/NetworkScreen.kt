package com.construct.messenger.ui.screens.settings

import com.construct.messenger.viewmodel.NetworkViewModel
import com.construct.messenger.veil.VeilState
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.components.CTStatus
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Shield
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
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
 * Whether the stream is up, to what, and the censorship protection. **Canon:** iOS
 * `NetworkSettingsView`: status, then VEIL. Android has Off and On; iOS's Auto waits for the
 * routing decision to live in the core.
 */
@Composable
fun NetworkScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    networkViewModel: NetworkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val veil by networkViewModel.veilState.collectAsStateWithLifecycle()
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
                    // Through VEIL the socket goes to the front; the server is still this one.
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

            CTSettingsSectionHeader(title = stringResource(R.string.censorship_protection))
            CTSectionGroup {
                VeilSwitchRow(state = veil, onChange = networkViewModel::setVeil)
                if (veil.running) {
                    CTSep()
                    CTSettingsRow(
                        label = stringResource(R.string.veil_front).uppercase(),
                        value = veil.relay.orEmpty(),
                        valueColor = CTColor.textDim,
                        icon = Icons.Default.Shield,
                        status = CTStatus.OK,
                    )
                    veil.latencyMs?.let { ms ->
                        CTSep()
                        CTSettingsRow(
                            label = stringResource(R.string.veil_latency).uppercase(),
                            value = "$ms ms",
                            valueColor = CTColor.textDim,
                            icon = Icons.Default.Speed,
                        )
                    }
                }
            }
            Text(
                text = when {
                    veil.starting -> stringResource(R.string.veil_establishing)
                    veil.mode == VeilMode.ON && !veil.running && veil.lastError != null ->
                        stringResource(R.string.veil_last_error) + ": " + veil.lastError
                    veil.mode == VeilMode.ON -> stringResource(R.string.censorship_protection_footer_on)
                    else -> stringResource(R.string.censorship_protection_footer_off)
                },
                style = ctRegular(11),
                color = if (veil.mode == VeilMode.ON && !veil.running && !veil.starting && veil.lastError != null) CTColor.danger else CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun VeilSwitchRow(state: VeilState, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.VpnLock,
            contentDescription = null,
            tint = if (state.mode == VeilMode.ON) CTColor.accent else CTColor.textDim,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(13.dp))
        Text(
            text = stringResource(R.string.censorship_protection),
            style = ctRegular(13),
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
        if (state.starting) {
            CircularProgressIndicator(
                color = CTColor.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(12.dp).size(20.dp),
            )
        } else {
            Switch(
                checked = state.mode == VeilMode.ON,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CTColor.bg,
                    checkedTrackColor = CTColor.accent,
                    uncheckedThumbColor = CTColor.textDim,
                    uncheckedTrackColor = CTColor.outMsgBg,
                    uncheckedBorderColor = CTColor.noise,
                ),
            )
        }
    }
}

private fun ConnectionStatus.label(): Int = when (this) {
    ConnectionStatus.CONNECTED -> R.string.network_connected
    ConnectionStatus.CONNECTING -> R.string.network_connecting
    ConnectionStatus.DISCONNECTED -> R.string.network_offline
    ConnectionStatus.UNKNOWN -> R.string.network_unknown
}
