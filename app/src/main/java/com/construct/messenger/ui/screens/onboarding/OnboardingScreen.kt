package com.construct.messenger.ui.screens.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.diagnostics.Diagnostics
import com.construct.messenger.domain.usecase.RegistrationStep
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.components.ConvergingSignal
import com.construct.messenger.ui.components.ctBackground
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.AliasStatus
import com.construct.messenger.viewmodel.OnboardingEvent
import com.construct.messenger.viewmodel.OnboardingUiState
import com.construct.messenger.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onInitialized: () -> Unit,
    onExistingIdentity: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.NavigateToMain -> onInitialized()
            }
        }
    }

    OnboardingContent(
        uiState = uiState,
        onAliasChange = viewModel::setAlias,
        onInitialize = viewModel::initializeIdentity,
        onContinue = viewModel::continueToMain,
        onDismissError = viewModel::dismissError,
        onExistingIdentity = onExistingIdentity,
    )
}

/**
 * **Canon:** iOS `OnboardingView` (the form) and `RegistrationFlowView.RegistrationStageView`
 * (everything after CREATE IDENTITY). [uiState]'s `step` `null` is the form; otherwise the
 * iOS stages — preparing (every pre-complete step as one converging signal), complete (welcome
 * + username/device-id), error (try again).
 *
 * iOS's "Can't connect?" is not here: it imports a VEIL access code, which Android cannot do yet.
 */
@Composable
private fun OnboardingContent(
    uiState: OnboardingUiState,
    onAliasChange: (String) -> Unit,
    onInitialize: () -> Unit,
    onContinue: () -> Unit,
    onDismissError: () -> Unit,
    onExistingIdentity: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .ctBackground()
            // Edge-to-edge: the last line under CREATE IDENTITY was drawn under the navigation bar.
            .systemBarsPadding()
            .imePadding()
    ) {
        when (val step = uiState.step) {
            null -> IdentityFormContent(
                alias = uiState.alias,
                aliasStatus = uiState.aliasStatus,
                canProceed = uiState.canProceed,
                onAliasChange = onAliasChange,
                onInitialize = onInitialize,
                onExistingIdentity = onExistingIdentity,
            )
            else -> RegistrationStageContent(
                step = step,
                username = uiState.username,
                deviceId = uiState.deviceId,
                onContinue = onContinue,
                onDismiss = onDismissError,
            )
        }
    }
}

@Composable
private fun IdentityFormContent(
    alias: String,
    aliasStatus: AliasStatus,
    canProceed: Boolean,
    onAliasChange: (String) -> Unit,
    onInitialize: () -> Unit,
    onExistingIdentity: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier.padding(horizontal = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(60.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_konstruct_logo),
                contentDescription = null,
                tint = CTColor.text,
                modifier = Modifier.size(100.dp)
            )
            Text(
                text = stringResource(R.string.brand_name),
                style = ctBold(26),
                color = CTColor.text,
                letterSpacing = 8.sp
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = ctRegular(12),
                color = CTColor.textDim,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            // iOS: `.frame(maxWidth: 360).padding(.horizontal, 24)` — the gutter sits outside
            // the cap, so on a phone the field is as wide as the button.
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CTTextField(
                placeholder = stringResource(R.string.onboarding_alias_placeholder),
                value = alias,
                onValueChange = onAliasChange,
                textAlign = TextAlign.Center,
            )
            aliasStatus.line()?.let { (text, color) ->
                Text(text = text, style = ctRegular(11), color = color)
            }
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CTButton(
                label = stringResource(R.string.onboarding_init_action),
                onClick = onInitialize,
                enabled = canProceed,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(bottom = 12.dp),
            )
            Text(
                text = stringResource(R.string.onboarding_already_have),
                style = ctRegular(13),
                color = CTColor.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(onClick = onExistingIdentity),
            )

            // Before any account exists: a failed registration or restore is diagnosed from these
            // logs, and Settings is out of reach. Debug builds only, as on iOS.
            if (Diagnostics.isEnabled) {
                val context = LocalContext.current
                Row(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable { Diagnostics.share(context) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = null,
                        tint = CTColor.textDim,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_share_logs),
                        style = ctRegular(11),
                        color = CTColor.textDim,
                    )
                }
            }
        }
    }
}

/**
 * The line under the alias field, or none. iOS sets "available" in `accentDim`; as text that
 * misses 4.5:1 (see `CTColor.accentDim`), so it is `accent` here.
 */
@Composable
private fun AliasStatus.line(): Pair<String, Color>? = when (this) {
    AliasStatus.NONE -> null
    AliasStatus.TOO_SHORT -> stringResource(R.string.username_too_short) to CTColor.danger
    AliasStatus.INVALID_CHARS -> stringResource(R.string.username_invalid_chars) to CTColor.danger
    AliasStatus.CHECKING -> stringResource(R.string.username_checking) to CTColor.textDim
    AliasStatus.AVAILABLE -> stringResource(R.string.username_available) to CTColor.accent
    AliasStatus.UNAVAILABLE -> stringResource(R.string.username_unavailable) to CTColor.danger
    AliasStatus.CHECK_FAILED -> stringResource(R.string.username_check_failed) to CTColor.danger
}

/**
 * **Canon:** iOS `RegistrationStageView`. Content centred between two spacers, the action at
 * the bottom; on success the signal collapses first and the welcome fades in 0.65 s later.
 */
@Composable
private fun RegistrationStageContent(
    step: RegistrationStep,
    username: String?,
    deviceId: String?,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val complete = step is RegistrationStep.Complete
    var showComplete by remember { mutableStateOf(false) }
    LaunchedEffect(complete) {
        if (complete) {
            delay(COMPLETE_REVEAL_DELAY_MS)
            showComplete = true
        } else {
            showComplete = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        when {
            step is RegistrationStep.Error -> RegistrationErrorContent(step.message)
            complete && showComplete -> RegistrationCompleteContent(username, deviceId)
            else -> RegistrationPreparingContent(step = step, collapsed = complete)
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.padding(bottom = 20.dp)) {
            when {
                complete -> CTButton(label = stringResource(R.string.reg_continue).uppercase(), onClick = onContinue)
                step is RegistrationStep.Error -> CTButton(
                    label = stringResource(R.string.reg_try_again).uppercase(),
                    onClick = onDismiss,
                    isDestructive = true,
                )
                else -> Text(
                    text = stringResource(R.string.reg_cancel),
                    style = ctRegular(13),
                    color = CTColor.textDim,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/** Unifies generatingKeys/fetchingChallenge/computingPow/submittingRegistration like iOS does. */
@Composable
private fun RegistrationPreparingContent(step: RegistrationStep, collapsed: Boolean) {
    val progress = when (step) {
        RegistrationStep.GeneratingKeys -> 0.04
        RegistrationStep.FetchingChallenge -> 0.12
        is RegistrationStep.ComputingPow -> 0.18 + step.progress * 0.76
        RegistrationStep.SubmittingRegistration -> 0.97
        else -> 1.0
    }
    val phaseLabel = when (step) {
        RegistrationStep.GeneratingKeys, RegistrationStep.FetchingChallenge ->
            stringResource(R.string.reg_phase_entropy)
        is RegistrationStep.ComputingPow ->
            stringResource(if (step.progress >= 0.98f) R.string.reg_phase_solution else R.string.reg_phase_nonce)
        RegistrationStep.SubmittingRegistration -> stringResource(R.string.reg_phase_solution)
        else -> ""
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(44.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.reg_establishing_trust),
                style = ctBold(18),
                color = CTColor.text,
            )
            Text(
                text = stringResource(R.string.reg_joining_network),
                style = ctRegular(13),
                color = CTColor.textDim,
            )
        }
        ConvergingSignal(
            progress = progress,
            collapsed = collapsed,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        )
        Crossfade(targetState = phaseLabel, animationSpec = tween(400), label = "phase") { label ->
            Text(text = label, style = ctRegular(11), color = CTColor.textDim)
        }
    }
}

@Composable
private fun RegistrationCompleteContent(username: String?, deviceId: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(
            text = stringResource(R.string.reg_welcome),
            style = ctBold(24),
            color = CTColor.text,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CTColor.bgMsg)
                .border(HairlineBorder, CTColor.noise),
        ) {
            if (!username.isNullOrEmpty()) {
                DetailRow(label = stringResource(R.string.reg_label_username), value = "@$username")
            } else {
                DetailRow(
                    label = stringResource(R.string.reg_label_mode),
                    value = stringResource(R.string.reg_mode_anonymous),
                )
            }
            CTSep()
            if (!deviceId.isNullOrEmpty()) {
                DetailRow(label = stringResource(R.string.reg_label_device_id), value = "${deviceId.take(16)}…")
            }
        }
    }
}

/** iOS `RegistrationStageView.DetailRow`: label in `secondary`, value bold 12. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CTLayout.edgePad, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = ctRegular(12), color = CTColor.textDim, modifier = Modifier.weight(1f))
        Text(text = value, style = ctBold(12), color = CTColor.text)
    }
}

@Composable
private fun RegistrationErrorContent(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.reg_error_title),
            style = ctBold(18),
            color = CTColor.danger,
        )
        Text(
            text = message,
            style = ctRegular(13),
            color = CTColor.textDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** iOS waits for the signal's collapse before the welcome fades in. */
private const val COMPLETE_REVEAL_DELAY_MS = 650L

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingScreenPreview() {
    KonstructMessengerTheme(darkTheme = true) {
        OnboardingContent(
            uiState = OnboardingUiState(),
            onAliasChange = {},
            onInitialize = {},
            onContinue = {},
            onDismissError = {},
            onExistingIdentity = {},
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingScreenCompletePreview() {
    KonstructMessengerTheme(darkTheme = true) {
        OnboardingContent(
            uiState = OnboardingUiState(
                step = RegistrationStep.Complete(),
                username = "alice",
                deviceId = "f3b8d583698feab0",
            ),
            onAliasChange = {},
            onInitialize = {},
            onContinue = {},
            onDismissError = {},
            onExistingIdentity = {},
        )
    }
}
