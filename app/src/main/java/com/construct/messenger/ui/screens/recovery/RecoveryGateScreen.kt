package com.construct.messenger.ui.screens.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.recovery.RecoveryStage
import com.construct.messenger.recovery.RecoveryUiState
import com.construct.messenger.recovery.RecoveryViewModel
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.Spacing
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular

/**
 * Shows [content] once this device knows the account's address, the recovery gate until then.
 *
 * Wraps the surfaces that make or take a contact invite — the own QR and the scanner — and
 * nothing else. **Canon:** iOS `RecoveryGated`; decision `invite-carries-the-account-address.md`.
 */
@Composable
fun RecoveryGated(
    onBack: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    if (ui.stage == RecoveryStage.Ready) {
        content()
    } else {
        RecoveryGateScreen(
            ui = ui,
            viewModel = viewModel,
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = onBack,
        )
    }
}

/**
 * Offered once, right after the first orientation. [onDone] runs when the device knows the
 * address or the user chose "later" — registration itself stays one step.
 */
@Composable
fun RecoveryPromptScreen(
    onDone: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.stage) {
        if (ui.stage == RecoveryStage.Ready) onDone()
    }
    if (ui.stage != RecoveryStage.Ready) {
        RecoveryGateScreen(
            ui = ui,
            viewModel = viewModel,
            dismissLabel = stringResource(R.string.recovery_gate_later),
            onDismiss = onDone,
        )
    }
}

@Composable
private fun RecoveryGateScreen(
    ui: RecoveryUiState,
    viewModel: RecoveryViewModel,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // iOS `RecoveryGateView`: no back arrow — "Cancel" (or "Later" after registration) on the
        // right, dim, and no rule under the bar.
        Box(modifier = Modifier.fillMaxWidth()) {
            CTNavBar(title = stringResource(R.string.recovery_gate_nav_title))
            Text(
                text = dismissLabel,
                style = ctBold(13),
                color = CTColor.textDim,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = Spacing.small)
                    .clip(RoundedCornerShape(CornerRadius.small))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = Spacing.small, vertical = Spacing.small),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(CTLayout.edgePad),
            verticalArrangement = Arrangement.spacedBy(CTLayout.sectionGap),
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = CTColor.accent,
                modifier = Modifier.size(22.dp),
            )
            Text(stringResource(R.string.recovery_gate_title), style = ctBold(18), color = CTColor.text)
            Text(stringResource(R.string.recovery_gate_why), style = ctRegular(13), color = CTColor.textDim)

            when (val stage = ui.stage) {
                RecoveryStage.Loading, RecoveryStage.Working, RecoveryStage.Ready ->
                    CircularProgressIndicator(
                        color = CTColor.accent,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                RecoveryStage.Explain -> {
                    Text(stringResource(R.string.recovery_gate_setup_note), style = ctRegular(12), color = CTColor.textDim)
                    CTButton(label = stringResource(R.string.recovery_gate_setup_action), onClick = viewModel::startSetup)
                }
                is RecoveryStage.ShowWords -> {
                    Text(stringResource(R.string.recovery_write_down), style = ctBold(14), color = CTColor.text)
                    WordGrid(stage.words)
                    Text(stringResource(R.string.recovery_never_share), style = ctRegular(12), color = CTColor.danger)
                    CTButton(label = stringResource(R.string.recovery_words_written), onClick = viewModel::toQuiz)
                }
                is RecoveryStage.Quiz -> {
                    Text(stringResource(R.string.recovery_quiz_prompt), style = ctRegular(13), color = CTColor.textDim)
                    stage.indices.forEach { i ->
                        CTTextField(
                            placeholder = stringResource(R.string.recovery_quiz_word_n, i + 1),
                            value = ui.quizAnswers[i].orEmpty(),
                            onValueChange = { viewModel.setQuizAnswer(i, it) },
                        )
                    }
                    ErrorLine(ui.errorRes)
                    CTButton(label = stringResource(R.string.recovery_confirm_action), onClick = viewModel::submitSetup)
                }
                RecoveryStage.Confirm -> {
                    Text(stringResource(R.string.recovery_gate_confirm_note), style = ctRegular(12), color = CTColor.textDim)
                    CTTextField(
                        placeholder = stringResource(R.string.recovery_confirm_placeholder),
                        value = ui.confirmPhrase,
                        onValueChange = viewModel::setConfirmPhrase,
                    )
                    ErrorLine(ui.errorRes)
                    CTButton(
                        label = stringResource(R.string.recovery_confirm_action),
                        onClick = viewModel::submitConfirm,
                        enabled = ui.confirmPhrase.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun WordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        words.chunked(3).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                chunk.forEachIndexed { col, word ->
                    Text(
                        text = "${row * 3 + col + 1}. $word",
                        style = ctRegular(14),
                        color = CTColor.text,
                        modifier = Modifier
                            .weight(1f)
                            .background(CTColor.bgMsg)
                            .padding(horizontal = Spacing.small, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorLine(errorRes: Int?) {
    if (errorRes == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = CTColor.danger, modifier = Modifier.size(16.dp))
        Text(stringResource(errorRes), style = ctRegular(13), color = CTColor.danger)
    }
}
