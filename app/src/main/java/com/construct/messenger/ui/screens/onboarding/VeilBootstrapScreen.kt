package com.construct.messenger.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.construct.messenger.R
import com.construct.messenger.ui.screens.settings.ConfigPasteDialog
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.viewmodel.VeilBootstrapViewModel

/**
 * The "Can't connect?" door. **Canon:** iOS `VeilBootstrapScanView`. A fresh install in a blocked
 * network cannot reach registration, so it cannot reach Settings either, where the configuration
 * paste lives: someone already connected gives a one-off access code, scanned or pasted here.
 * Reached by a quiet text under the first screen's actions, never a peer of "Create identity".
 *
 * The front's address is never shown: the screen is a public surface.
 */
@Composable
fun VeilBootstrapScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    viewModel: VeilBootstrapViewModel = hiltViewModel(),
) {
    VeilBootstrapContent(onBack = onBack, onScan = onScan, onImport = viewModel::redeem)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VeilBootstrapContent(onBack: () -> Unit, onScan: () -> Unit, onImport: (String) -> Int) {
    var pasting by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(CTColor.bg).systemBarsPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.veil_bootstrap_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CTLayout.edgePad)
                .widthIn(max = 480.dp)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(CTSpace.l),
        ) {
            Text(
                stringResource(R.string.veil_bootstrap_intro),
                style = CTFont.body,
                color = CTColor.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = CTSpace.l),
            )
            if (configured) {
                Column(
                    modifier = Modifier.fillMaxWidth().card().padding(CTSpace.l),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CTSpace.s),
                ) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = CTColor.accent, modifier = Modifier.size(CTIcon.navLg))
                    Text(stringResource(R.string.veil_bootstrap_ok), style = CTFont.body, color = CTColor.text, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.veil_bootstrap_ok_hint), style = CTFont.caption, color = CTColor.textDim, textAlign = TextAlign.Center)
                }
            } else {
                ActionCard(Icons.Filled.QrCodeScanner, R.string.veil_config_scan, R.string.veil_bootstrap_scan_subtitle, onScan)
                ActionCard(Icons.Filled.ContentPaste, R.string.veil_config_paste, R.string.veil_bootstrap_paste_subtitle) { pasting = true }
            }
        }
    }
    if (pasting) {
        ConfigPasteDialog(
            onImport = { text -> onImport(text).also { if (it == R.string.veil_config_import_ok) configured = true } },
            onDismiss = { pasting = false },
        )
    }
}

@Composable
private fun Modifier.card(): Modifier {
    val shape = RoundedCornerShape(CornerRadius.medium)
    return background(CTColor.bgMsg, shape).border(0.5.dp, CTColor.noise, shape)
}

@Composable
private fun ActionCard(icon: ImageVector, title: Int, subtitle: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().card().clickable(onClick = onClick).padding(horizontal = CTLayout.edgePad, vertical = CTSpace.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CTSpace.m),
    ) {
        Icon(icon, contentDescription = null, tint = CTColor.accent, modifier = Modifier.width(32.dp).size(CTIcon.navLg))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CTSpace.xs)) {
            Text(stringResource(title).uppercase(), style = CTFont.body, color = CTColor.text)
            Text(stringResource(subtitle), style = CTFont.caption, color = CTColor.textDim)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = CTColor.textDim, modifier = Modifier.size(CTIcon.row))
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VeilBootstrapScreenPreview() {
    VeilBootstrapContent(onBack = {}, onScan = {}, onImport = { R.string.veil_config_import_ok })
}
