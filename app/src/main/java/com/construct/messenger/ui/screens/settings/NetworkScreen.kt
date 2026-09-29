package com.construct.messenger.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.BuildConfig
import com.construct.messenger.R
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.transport.TransportRoute
import com.construct.messenger.ui.components.CTModeSelector
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSectionGroup
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.components.CTStatus
import com.construct.messenger.ui.components.CTStatusBadge
import com.construct.messenger.ui.components.ConnectionStatus
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.viewmodel.NetworkUiState
import com.construct.messenger.viewmodel.NetworkViewModel
import kotlinx.coroutines.delay

/**
 * Internal builds only, as iOS's `DEBUG || INTERNAL_TOOLS`: the server, path, front, latency and
 * errors. `decisions/silent-transport-ui`: the public build discloses no reachability coordinate
 * — a censor who installs the app must not be able to read the working front off this screen.
 */
private val SHOW_TRANSPORT_DETAIL = BuildConfig.DEBUG

/**
 * Network.
 *
 * **Canon:** iOS `NetworkSettingsView` in the external build — status with a neutral "Protected",
 * the last heartbeat, and "Censorship protection" off / auto / on with a plain footer. No host,
 * path, transport, front or the word VEIL. Debug builds add the technical section beneath, as
 * iOS internal builds do.
 */
@Composable
fun NetworkScreen(
    onNavigateBack: () -> Unit,
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    NetworkContent(ui = ui, onNavigateBack = onNavigateBack, onMode = viewModel::setMode)
}

@Composable
private fun NetworkContent(ui: NetworkUiState, onNavigateBack: () -> Unit, onMode: (VeilMode) -> Unit) {
    val route = ui.route
    val connecting = ui.connection == ConnectionStatus.CONNECTING ||
        route == TransportRoute.State.VeilProbing || route is TransportRoute.State.VeilCooldown
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CTLayout.edgePad, vertical = CTLayout.edgePad),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CTLayout.edgePad),
                ) {
                    CTStatusBadge(status = ui.connection.toStatus(), size = 20.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(ui.connection.label()), style = ctRegular(14), color = CTColor.text)
                        SelectionContainer {
                            Text(
                                text = if (SHOW_TRANSPORT_DETAIL) {
                                    technicalPath(route)
                                } else {
                                    // Direct and VEIL collapse to one information-free state.
                                    stringResource(if (connecting) R.string.net_status_connecting else R.string.net_status_protected)
                                },
                                style = ctRegular(11),
                                color = CTColor.textDim,
                            )
                        }
                    }
                }
                ui.lastHeartbeatAt?.let { at ->
                    CTSep()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CTLayout.edgePad, vertical = CTLayout.chromeGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.network_last_heartbeat),
                            style = ctRegular(13),
                            color = CTColor.textDim,
                            modifier = Modifier.weight(1f),
                        )
                        Text(relativeTime(at), style = ctRegular(13), color = CTColor.textDim)
                    }
                }
            }

            CTSettingsSectionHeader(title = stringResource(R.string.censorship_protection))
            CTSectionGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CTLayout.edgePad, vertical = CTLayout.edgePad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.censorship_protection),
                        style = ctRegular(13),
                        color = CTColor.text,
                        modifier = Modifier.weight(1f),
                    )
                    CTModeSelector(
                        selected = ui.mode,
                        options = VeilMode.entries,
                        labels = mapOf(
                            VeilMode.OFF to stringResource(R.string.veil_mode_off),
                            VeilMode.AUTO to stringResource(R.string.veil_mode_auto),
                            VeilMode.ON to stringResource(R.string.veil_mode_on),
                        ),
                        onSelection = onMode,
                    )
                }
            }
            Text(
                text = stringResource(
                    when (ui.mode) {
                        VeilMode.OFF -> R.string.censorship_protection_footer_off
                        VeilMode.AUTO -> R.string.censorship_protection_footer_auto
                        VeilMode.ON -> R.string.censorship_protection_footer_on
                    },
                ),
                style = ctRegular(11),
                color = CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = 8.dp),
            )

            if (SHOW_TRANSPORT_DETAIL) TransportDetail(ui)
        }
    }
}

/** iOS "Traffic protection" + diagnostics, internal builds only. */
@Composable
private fun TransportDetail(ui: NetworkUiState) {
    val route = ui.route
    val onVeil = route is TransportRoute.State.VeilActive
    CTSettingsSectionHeader(title = "TRAFFIC PROTECTION (INTERNAL)", color = CTColor.warning)
    CTSectionGroup {
        CTSettingsRow(
            label = stringResource(R.string.network_server).uppercase(),
            value = "${GrpcClient.HOST}:${GrpcClient.PORT}",
            valueColor = CTColor.textDim,
            icon = Icons.Default.Dns,
        )
        CTSep()
        CTSettingsRow(
            label = stringResource(R.string.veil_path).uppercase(),
            value = stringResource(if (onVeil) R.string.veil_path_veil else R.string.veil_path_direct),
            valueColor = if (onVeil) CTColor.accent else CTColor.textDim,
            icon = Icons.Default.VpnLock,
            status = if (onVeil) CTStatus.OK else null,
        )
        if (route is TransportRoute.State.VeilActive) {
            CTSep()
            CTSettingsRow(
                label = stringResource(R.string.veil_front).uppercase(),
                value = route.relay,
                valueColor = CTColor.textDim,
                icon = Icons.Default.Shield,
            )
            ui.info.latencyMs?.let { ms ->
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
    ui.info.lastError?.let { error ->
        Text(
            text = stringResource(R.string.veil_last_error) + ": " + error,
            style = ctRegular(11),
            color = CTColor.danger,
            modifier = Modifier.padding(horizontal = CTLayout.edgePad * 2, vertical = 8.dp),
        )
    }
}

/** Internal builds: iOS `VeilTrafficPath.displayDetail`. */
private fun technicalPath(route: TransportRoute.State): String = when (route) {
    is TransportRoute.State.VeilActive -> "TLS 1.3 → veil-front → ${route.relay}"
    TransportRoute.State.VeilProbing -> "Establishing veil-front tunnel…"
    is TransportRoute.State.VeilCooldown -> "Reconnecting via VEIL…"
    else -> "TLS 1.3 ${GrpcClient.HOST}:${GrpcClient.PORT}"
}

/** "12 seconds ago", refreshed every few seconds so it does not freeze on screen. */
@Composable
private fun relativeTime(at: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.SECOND_IN_MILLIS).toString()
}

private fun ConnectionStatus.label(): Int = when (this) {
    ConnectionStatus.CONNECTED -> R.string.network_connected
    ConnectionStatus.CONNECTING -> R.string.network_connecting
    ConnectionStatus.DISCONNECTED -> R.string.network_offline
    ConnectionStatus.UNKNOWN -> R.string.network_unknown
}
