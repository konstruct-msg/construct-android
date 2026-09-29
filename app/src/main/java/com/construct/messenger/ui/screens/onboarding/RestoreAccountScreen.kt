package com.construct.messenger.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.RestoreAccountViewModel

/**
 * Sign in to an existing account with its recovery phrase. **Canon:** iOS account recovery
 * (`AccountRecoveryViewModel.submitRecover`).
 */
@Composable
fun RestoreAccountScreen(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    viewModel: RestoreAccountViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) {
        if (ui.done) onRestored()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        CTNavBar(title = stringResource(R.string.restore_nav_title), showBack = true, onBack = onBack)
        CTSep()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CTLayout.edgePad, vertical = Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(stringResource(R.string.restore_title), style = ctBold(17), color = CTColor.text)
            Text(stringResource(R.string.restore_body), style = ctRegular(14), color = CTColor.textDim)
            CTTextField(
                placeholder = stringResource(R.string.restore_identifier_placeholder),
                value = ui.identifier,
                onValueChange = viewModel::setIdentifier,
            )
            CTTextField(
                placeholder = stringResource(R.string.recovery_confirm_placeholder),
                value = ui.phrase,
                onValueChange = viewModel::setPhrase,
            )
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = CTColor.danger, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.restore_other_devices), style = ctRegular(13), color = CTColor.textDim)
            }
            ui.errorRes?.let { Text(stringResource(it), style = ctRegular(13), color = CTColor.danger) }
            if (ui.working) {
                CircularProgressIndicator(color = CTColor.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                CTButton(
                    label = stringResource(R.string.restore_action),
                    onClick = viewModel::submit,
                    enabled = ui.identifier.isNotBlank() && ui.phrase.isNotBlank(),
                )
            }
        }
    }
}
