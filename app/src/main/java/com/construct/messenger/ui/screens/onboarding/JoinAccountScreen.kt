package com.construct.messenger.ui.screens.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.devicelink.DeviceLinkQr
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.InviteQrImage
import com.construct.messenger.ui.components.inviteQrSize
import com.construct.messenger.ui.screens.invite.ScannerViewport
import com.construct.messenger.ui.screens.invite.hasCameraPermission
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.JoinAccountViewModel

/**
 * Join an existing account as a new device — debug builds only (the route exists only when
 * `BuildConfig.DEBUG`). Scan the QR the account's device shows (flow A), or show one for it to
 * scan (flow B). **Canon:** iOS `DeviceLinkMethodView` / `DeviceLinkScanView` / `DeviceLinkShowQRView`.
 */
@Composable
fun JoinAccountRoute(
    onBack: () -> Unit,
    onLinked: () -> Unit,
    viewModel: JoinAccountViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) viewModel.startScan()
    }
    LaunchedEffect(ui.linked) { if (ui.linked) onLinked() }

    ui.error?.let { message ->
        CTConfirmDialog(
            title = stringResource(R.string.error),
            message = stringResource(message),
            confirmLabel = stringResource(R.string.ok),
            dismissLabel = null,
            onConfirm = viewModel::dismissError,
            onDismiss = viewModel::dismissError,
        )
    }

    if (ui.scanning && granted) {
        Box(Modifier.fillMaxSize().background(CTColor.mediaGround)) {
            ScannerViewport(
                onNotAnInvite = {},
                onInvite = viewModel::onScanned,
                recognise = { text, _ -> text.trim().takeIf(DeviceLinkQr::isLink) },
            )
            OutlinedButton(
                onClick = viewModel::stopScan,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(CTSpace.xl),
            ) { Text(stringResource(R.string.action_cancel), color = CTColor.onMedia) }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.device_join_title), showBack = true, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(CTLayout.edgePad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CTSpace.l),
        ) {
            Text(stringResource(R.string.device_join_intro), style = CTFont.body, color = CTColor.textDim, textAlign = TextAlign.Center)
            ui.joinQr?.let { qr ->
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    InviteQrImage(payload = qr, size = inviteQrSize(maxWidth), contentDescription = stringResource(R.string.device_join_show_qr))
                }
                Text(stringResource(R.string.device_join_waiting), style = CTFont.body, color = CTColor.textDim, textAlign = TextAlign.Center)
            }
            if (ui.busy || ui.joinQr != null) {
                CircularProgressIndicator(color = CTColor.accent, modifier = Modifier.size(CTIcon.control))
            }
            Button(
                onClick = { if (granted) viewModel.startScan() else launcher.launch(Manifest.permission.CAMERA) },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.device_join_scan_qr)) }
            OutlinedButton(onClick = viewModel::showJoinRequest, enabled = !ui.busy && ui.joinQr == null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.device_join_show_qr))
            }
        }
    }
}
