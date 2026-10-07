package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.ctBackground
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.viewmodel.DeviceSafetyNumber
import com.construct.messenger.viewmodel.SafetyNumberViewModel
import kotlinx.coroutines.delay

/** iOS `SafetyNumberView` insets: blocks 20 from the edges, 16 above and below. */
private val EDGE = 20.dp

/**
 * Safety numbers with a contact, one block per device of theirs. **Canon:** iOS
 * `SafetyNumberView`; the number is the core's (`computeSafetyNumber`). With several devices
 * each block is headed by that device's key fingerprint, where iOS prints its id — the
 * fingerprint is what the profile shows, so the two can be matched.
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
            .ctBackground()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CTNavBar(title = stringResource(R.string.safety_numbers), showBack = true, onBack = onNavigateBack)
        Divider()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = EDGE, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tracked("> ${stringResource(R.string.safety_numbers_verify_title).uppercase()}", CTFont.ui(12, FontWeight.Bold), CTColor.accent)
                Text(
                    text = stringResource(R.string.safety_numbers_instruction, ui.contactName),
                    style = CTFont.body.copy(lineHeight = 21.sp),
                    color = CTColor.textDim,
                )
            }
            Divider()
            when {
                ui.loading -> CircularProgressIndicator(
                    color = CTColor.accent,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(CTSpace.xl),
                )
                ui.devices.isEmpty() -> {
                    Unavailable()
                    Divider()
                }
                else -> ui.devices.forEach { device ->
                    DeviceBlock(device, showDevice = ui.devices.size > 1)
                    Divider(alpha = 0.4f)
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = EDGE, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tracked(
                    "! ${stringResource(R.string.safety_numbers_mismatch_header).uppercase()}",
                    CTFont.badge,
                    CTColor.accent.copy(alpha = 0.7f),
                )
                Text(
                    text = stringResource(R.string.safety_numbers_mismatch_body),
                    style = CTFont.secondary.copy(lineHeight = 20.sp),
                    color = CTColor.textDim,
                )
            }
        }
    }
}

@Composable
private fun DeviceBlock(device: DeviceSafetyNumber, showDevice: Boolean) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(device.number) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    if (showDevice) {
        Tracked(
            text = stringResource(R.string.safety_numbers_device, device.fingerprint),
            style = CTFont.badge,
            color = CTColor.textDim,
            modifier = Modifier.padding(start = EDGE, end = EDGE, top = 14.dp),
        )
    }
    val number = device.number
    if (number == null) {
        Unavailable()
        return
    }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        number.split(" ").filter { it.isNotBlank() }.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { chunk ->
                    Text(
                        text = chunk,
                        style = CTFont.ui(16, FontWeight.Bold),
                        color = CTColor.text,
                        modifier = Modifier
                            .weight(1f)
                            .background(CTColor.noise.copy(alpha = 0.25f))
                            .padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    Divider()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(number))
                copied = true
            }
            .padding(horizontal = EDGE, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(if (copied) R.string.safety_numbers_copied else R.string.safety_numbers_copy),
            style = CTFont.body,
            color = if (copied) CTColor.accent else CTColor.text,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Outlined.ContentCopy,
            contentDescription = null,
            tint = if (copied) CTColor.accent else CTColor.textDim,
            modifier = Modifier.size(CTIcon.nav),
        )
    }
}

@Composable
private fun Unavailable() {
    Text(
        text = stringResource(R.string.safety_numbers_unavailable),
        style = CTFont.body,
        color = CTColor.textDim,
        modifier = Modifier.padding(horizontal = EDGE, vertical = 14.dp),
    )
}

/** iOS headers here are tracked 2 pt. */
@Composable
private fun Tracked(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Text(text = text, style = style, color = color, letterSpacing = 2.sp, modifier = modifier)
}

/** A full-width `noise` rule, 1 dp — this screen's only separator. */
@Composable
private fun Divider(alpha: Float = 1f) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CTColor.noise.copy(alpha = alpha)),
    )
}
