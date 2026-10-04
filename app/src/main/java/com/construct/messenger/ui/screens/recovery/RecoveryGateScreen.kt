package com.construct.messenger.ui.screens.recovery

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.recovery.RecoveryPhraseVault
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
 * Right after the first orientation. The key is made without a screen
 * ([RecoveryViewModel.provisionFirst]); the setup shows only when it could not be — no screen
 * lock, or the account has a key this device has not seen. [onDone] runs when that is settled or
 * the user chose "later" — registration itself stays one step.
 */
@Composable
fun RecoveryPromptScreen(
    onDone: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    remember(viewModel) { viewModel.provisionFirst() }
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

/**
 * The Settings entry: the copy of a silently made key when one waits on this device (unlock →
 * words → pick three → the phrase leaves the device), the gate's own flow otherwise. [onDone] runs
 * when there is nothing left to do. **Canon:** iOS `RecoverySetupView` (`create` / `backupHeld`).
 */
@Composable
fun RecoverySetupScreen(
    onDone: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    remember(viewModel) { viewModel.prepareSetup() }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.stage) {
        if (ui.stage == RecoveryStage.Ready) onDone()
    }
    if (ui.stage != RecoveryStage.Ready) {
        RecoveryGateScreen(
            ui = ui,
            viewModel = viewModel,
            dismissLabel = stringResource(R.string.action_cancel),
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
            val stage = ui.stage
            val backup = stage == RecoveryStage.BackupIntro ||
                (stage as? RecoveryStage.ShowWords)?.backup == true ||
                (stage as? RecoveryStage.Quiz)?.backup == true
            Icon(
                imageVector = if (stage == RecoveryStage.BackupLost) Icons.Default.Warning else Icons.Default.Key,
                contentDescription = null,
                tint = if (stage == RecoveryStage.BackupLost) CTColor.danger else CTColor.accent,
                modifier = Modifier.size(22.dp),
            )
            val (title, body) = when {
                stage == RecoveryStage.BackupLost -> R.string.recovery_backup_lost_title to R.string.recovery_backup_lost_body
                backup -> R.string.recovery_backup_intro_title to R.string.recovery_backup_intro_body
                else -> R.string.recovery_gate_title to R.string.recovery_gate_why
            }
            Text(stringResource(title), style = ctBold(18), color = CTColor.text)
            Text(stringResource(body), style = ctRegular(13), color = CTColor.textDim)

            when (stage) {
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
                        WordChoice(
                            label = stringResource(R.string.recovery_quiz_word_n, i + 1),
                            options = stage.options[i].orEmpty(),
                            picked = ui.quizAnswers[i],
                            onPick = { viewModel.setQuizAnswer(i, it) },
                        )
                    }
                    ErrorLine(ui.errorRes)
                    CTButton(
                        label = stringResource(R.string.recovery_confirm_action),
                        onClick = viewModel::submitSetup,
                        enabled = stage.indices.all { ui.quizAnswers[it] != null },
                    )
                }
                RecoveryStage.BackupIntro -> {
                    val unlock = rememberHeldUnlock(viewModel)
                    ErrorLine(ui.errorRes)
                    CTButton(label = stringResource(R.string.recovery_backup_show), onClick = unlock)
                }
                RecoveryStage.BackupLost -> Unit
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

/** One of the three checks: which of these is word N. Picking, not typing. */
@Composable
private fun WordChoice(label: String, options: List<String>, picked: String?, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(label, style = ctRegular(12), color = CTColor.textDim)
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                pair.forEach { word ->
                    val chosen = word == picked
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(CornerRadius.small))
                            .background(CTColor.bgMsg)
                            .border(1.dp, if (chosen) CTColor.accent else CTColor.noise, RoundedCornerShape(CornerRadius.small))
                            .semantics { selected = chosen }
                            .clickable { onPick(word) }
                            .padding(horizontal = Spacing.small, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(word, style = ctRegular(14), color = CTColor.text, modifier = Modifier.weight(1f))
                        if (chosen) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = CTColor.accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                // An odd last row keeps its cell the width of the others.
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Opens the held phrase after the person authenticates. API 30+: the system prompt with the key's
 * cipher as its `CryptoObject`, strong biometric or the device credential. API 26–29: the device
 * credential first, always — a key unlocked by time would otherwise open within seconds of the
 * phone being unlocked, with nobody asked.
 */
@Composable
private fun rememberHeldUnlock(viewModel: RecoveryViewModel): () -> Unit {
    val context = LocalContext.current
    val reason = stringResource(R.string.recovery_backup_auth_reason)
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        when (val unlock = viewModel.unlock()) {
            is RecoveryPhraseVault.Unlock.Prompt -> viewModel.openHeld(unlock.cipher)
            RecoveryPhraseVault.Unlock.Lost -> viewModel.heldLost()
            RecoveryPhraseVault.Unlock.Credential -> viewModel.openFailed()
        }
    }
    return remember(viewModel, context) {
        {
            val unlock = viewModel.unlock()
            when {
                unlock == RecoveryPhraseVault.Unlock.Lost -> viewModel.heldLost()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && unlock is RecoveryPhraseVault.Unlock.Prompt -> {
                    val activity = context as? FragmentActivity
                    if (activity == null) {
                        viewModel.openFailed()
                    } else {
                        BiometricPrompt(
                            activity,
                            ContextCompat.getMainExecutor(activity),
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    result.cryptoObject?.cipher?.let(viewModel::openHeld) ?: viewModel.openFailed()
                                }
                            },
                        ).authenticate(
                            BiometricPrompt.PromptInfo.Builder()
                                .setTitle(reason)
                                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                                .build(),
                            BiometricPrompt.CryptoObject(unlock.cipher),
                        )
                    }
                }
                else -> context.getSystemService(KeyguardManager::class.java)
                    ?.createConfirmDeviceCredentialIntent(reason, null)
                    ?.let(credential::launch)
                    ?: viewModel.openFailed()
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
