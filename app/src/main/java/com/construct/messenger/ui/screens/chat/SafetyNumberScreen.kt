package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsSectionHeader
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.DeviceSafetyNumber
import com.construct.messenger.viewmodel.SafetyNumberViewModel

/**
 * Safety numbers with a contact, one block per device of theirs. **Canon:** iOS
 * `SafetyNumberView`; the number is the core's (`computeSafetyNumber`).
 */
@Composable
fun SafetyNumberScreen(
    onNavigateBack: () -> Unit,
    viewModel: SafetyNumberViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.safety_numbers), showBack = true, onBack = onNavigateBack)
        CTSep()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            CTSettingsSectionHeader(title = stringResource(R.string.safety_numbers_verify_title))
            Text(
                text = stringResource(R.string.safety_numbers_instruction, ui.contactName),
                style = ctRegular(14),
                color = CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad, vertical = Spacing.small),
            )
            CTSep()
            when {
                ui.loading -> CircularProgressIndicator(
                    color = CTColor.accent,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(Spacing.large),
                )
                ui.devices.isEmpty() -> Unavailable()
                else -> ui.devices.forEach { device ->
                    DeviceBlock(device, showDevice = ui.devices.size > 1)
                    CTSep()
                }
            }
            CTSettingsSectionHeader(
                title = stringResource(R.string.safety_numbers_mismatch_header),
                color = CTColor.textDim,
            )
            Text(
                text = stringResource(R.string.safety_numbers_mismatch_body),
                style = ctRegular(13),
                color = CTColor.textDim,
                modifier = Modifier.padding(horizontal = CTLayout.edgePad, vertical = Spacing.small),
            )
        }
    }
}

@Composable
private fun DeviceBlock(device: DeviceSafetyNumber, showDevice: Boolean) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(device.number) { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(horizontal = CTLayout.edgePad, vertical = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        if (showDevice) {
            Text(
                text = stringResource(R.string.safety_numbers_device, device.fingerprint),
                style = ctRegular(12),
                color = CTColor.textDim,
            )
        }
        val number = device.number
        if (number == null) {
            Unavailable()
            return@Column
        }
        number.split(" ").filter { it.isNotBlank() }.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                row.forEach { chunk ->
                    Text(
                        text = chunk,
                        style = ctBold(16),
                        color = CTColor.text,
                        modifier = Modifier
                            .weight(1f)
                            .background(CTColor.bgMsg)
                            .padding(vertical = Spacing.small),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    clipboard.setText(AnnotatedString(number))
                    copied = true
                }
                .padding(vertical = Spacing.small),
        ) {
            Text(
                text = stringResource(if (copied) R.string.safety_numbers_copied else R.string.safety_numbers_copy),
                style = ctRegular(14),
                color = if (copied) CTColor.accent else CTColor.text,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = null,
                tint = if (copied) CTColor.accent else CTColor.textDim,
                modifier = Modifier.size(CTLayout.navIconSize),
            )
        }
    }
}

@Composable
private fun Unavailable() {
    Text(
        text = stringResource(R.string.safety_numbers_unavailable),
        style = ctRegular(13),
        color = CTColor.textDim,
        modifier = Modifier.padding(horizontal = CTLayout.edgePad, vertical = Spacing.medium),
    )
}
