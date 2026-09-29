package com.construct.messenger.ui.screens.settings

import com.construct.messenger.transport.TransportRoute
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import com.construct.messenger.viewmodel.NetworkViewModel
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.components.CTStatus
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Shield
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
    val net by networkViewModel.uiState.collectAsStateWithLifecycle()
    val route = net.route
    val onVeil = route is TransportRoute.State.VeilActive
    val probing = route == TransportRoute.State.VeilProbing
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
                VeilMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) CTSep()
                    ModeRow(mode = mode, selected = net.mode == mode, onSelect = { networkViewModel.setMode(mode) })
                }
            }
            CTSectionGroup(modifier = Modifier.padding(top = 12.dp)) {
                CTSettingsRow(
                    label = stringResource(R.string.veil_path).uppercase(),
                    value = stringResource(if (onVeil) R.string.veil_path_veil else R.string.veil_path_direct),
                    valueColor = if (onVeil) CTColor.accent else CTColor.textDim,
                    icon = Icons.Default.VpnLock,
                    status = if (onVeil) CTStatus.OK else null,
                )
                if (onVeil) {
                    CTSep()
                    CTSettingsRow(
                        label = stringResource(R.string.veil_front).uppercase(),
                        value = (route as TransportRoute.State.VeilActive).relay,
                        valueColor = CTColor.textDim,
                        icon = Icons.Default.Shield,
                    )
                    net.info.latencyMs?.let { ms ->
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
            val failed = !onVeil && !probing && net.mode != VeilMode.OFF && net.info.lastError != null &&
                (net.mode == VeilMode.ON || route is TransportRoute.State.VeilCooldown)
            Text(
                text = when {
                    probing -> stringResource(R.string.veil_establishing)
                    failed -> stringResource(R.string.veil_last_error) + ": " + net.info.lastError
                    net.mode == VeilMode.ON -> stringResource(R.string.censorship_protection_footer_on)
                    net.mode == VeilMode.AUTO -> stringResource(R.string.censorship_protection_footer_auto)
                    else -> stringResource(R.string.censorship_protection_footer_off)
                },
                style = ctRegular(11),
                color = if (failed) CTColor.danger else CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ModeRow(mode: VeilMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                when (mode) {
                    VeilMode.OFF -> R.string.veil_mode_off
                    VeilMode.AUTO -> R.string.veil_mode_auto
                    VeilMode.ON -> R.string.veil_mode_on
                },
            ),
            style = ctRegular(13),
            color = CTColor.text,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = CTColor.accent, unselectedColor = CTColor.textDim),
        )
    }
}

private fun ConnectionStatus.label(): Int = when (this) {
    ConnectionStatus.CONNECTED -> R.string.network_connected
    ConnectionStatus.CONNECTING -> R.string.network_connecting
    ConnectionStatus.DISCONNECTED -> R.string.network_offline
    ConnectionStatus.UNKNOWN -> R.string.network_unknown
}
