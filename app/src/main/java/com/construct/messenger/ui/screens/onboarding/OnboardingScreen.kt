package com.construct.messenger.ui.screens.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.domain.usecase.RegistrationStep
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTSep
import com.construct.messenger.ui.components.CTSettingsRow
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.HairlineBorder
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.OnboardingEvent
import com.construct.messenger.viewmodel.OnboardingUiState
import com.construct.messenger.viewmodel.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onInitialized: () -> Unit,
    onRestore: (() -> Unit)? = null,
    onLinkDevice: (() -> Unit)? = null,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var publicAlias by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.NavigateToMain -> onInitialized()
            }
        }
    }

    OnboardingContent(
        uiState = uiState,
        publicAlias = publicAlias,
        onPublicAliasChange = { publicAlias = it },
        onInitialize = { viewModel.initializeIdentity(publicAlias) },
        onContinue = viewModel::continueToMain,
        onDismissError = viewModel::dismissError,
        onRestore = onRestore,
        onLinkDevice = onLinkDevice,
    )
}

/**
 * **Canon:** iOS `RegistrationFlowView.RegistrationStageView`. [uiState]'s `step` `null` is
 * the idle form; otherwise this mirrors the iOS stages — preparing (all pre-complete steps
 * unified), complete (welcome + username/device-id detail), error (try again / cancel).
 * The bespoke `ConvergingSignalView` canvas animation is not ported — a plain progress bar
 * stands in for it.
 */
@Composable
private fun OnboardingContent(
    uiState: OnboardingUiState,
    publicAlias: String,
    onPublicAliasChange: (String) -> Unit,
    onInitialize: () -> Unit,
    onContinue: () -> Unit,
    onDismissError: () -> Unit,
    onRestore: (() -> Unit)?,
    onLinkDevice: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            // Edge-to-edge: the last line under CREATE IDENTITY was drawn under the navigation bar.
            .systemBarsPadding()
            .padding(horizontal = 28.dp, vertical = 36.dp)
    ) {
        when (val step = uiState.step) {
            null -> IdentityFormContent(
                publicAlias = publicAlias,
                onPublicAliasChange = onPublicAliasChange,
                onInitialize = onInitialize,
                onRestore = onRestore,
                onLinkDevice = onLinkDevice,
            )
            is RegistrationStep.Complete -> RegistrationCompleteContent(
                username = uiState.username,
                deviceId = uiState.deviceId,
                onContinue = onContinue,
            )
            is RegistrationStep.Error -> RegistrationErrorContent(
                message = step.message,
                onDismiss = onDismissError,
            )
            else -> RegistrationPreparingContent(step = step, onCancel = onDismissError)
        }
    }
}

@Composable
private fun IdentityFormContent(
    publicAlias: String,
    onPublicAliasChange: (String) -> Unit,
    onInitialize: () -> Unit,
    onRestore: (() -> Unit)?,
    onLinkDevice: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(164.dp))

        Icon(
            painter = painterResource(R.drawable.ic_konstruct_logo),
            contentDescription = null,
            tint = CTColor.text,
            modifier = Modifier.size(84.dp)
        )

        Spacer(modifier = Modifier.height(44.dp))

        Text(
            text = stringResource(R.string.brand_name),
            style = ctBold(20),
            color = CTColor.text,
            letterSpacing = 7.sp
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = ctRegular(14),
            color = CTColor.textDim,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        CTTextField(
            placeholder = stringResource(R.string.onboarding_alias_placeholder),
            value = publicAlias,
            onValueChange = onPublicAliasChange,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(20.dp))

        CTButton(
            label = stringResource(R.string.onboarding_init_action),
            onClick = onInitialize,
        )

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onboarding_restore_action),
                style = ctRegular(14),
                color = CTColor.accent,
                letterSpacing = 0.5.sp,
                modifier = Modifier.clickable(enabled = onRestore != null) {
                    onRestore?.invoke()
                }
            )
            Text(
                text = stringResource(R.string.onboarding_link_device_action),
                style = ctRegular(14),
                color = CTColor.textDim,
                letterSpacing = 0.5.sp,
                modifier = Modifier.clickable(enabled = onLinkDevice != null) {
                    onLinkDevice?.invoke()
                }
            )
        }
    }
}

/** Unifies generatingKeys/fetchingChallenge/computingPow/submittingRegistration like iOS does. */
@Composable
private fun RegistrationPreparingContent(step: RegistrationStep, onCancel: () -> Unit) {
    val progress = when (step) {
        RegistrationStep.GeneratingKeys -> 0.04f
        RegistrationStep.FetchingChallenge -> 0.12f
        is RegistrationStep.ComputingPow -> 0.18f + step.progress * 0.76f
        RegistrationStep.SubmittingRegistration -> 0.97f
        else -> 1f
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
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.reg_establishing_trust),
            style = ctBold(18),
            color = CTColor.text,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.reg_joining_network),
            style = ctRegular(13),
            color = CTColor.textDim,
        )
        Spacer(modifier = Modifier.height(44.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = CTColor.accent,
            trackColor = CTColor.noise,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = phaseLabel,
            style = ctRegular(11),
            color = CTColor.textDim,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.reg_cancel),
            style = ctRegular(13),
            color = CTColor.textDim,
            modifier = Modifier.clickable(onClick = onCancel),
        )
    }
}

@Composable
private fun RegistrationCompleteContent(
    username: String?,
    deviceId: String?,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.reg_welcome),
            style = ctBold(24),
            color = CTColor.text,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CTColor.bgMsg)
                .border(HairlineBorder, CTColor.noise),
        ) {
            if (!username.isNullOrEmpty()) {
                CTSettingsRow(label = stringResource(R.string.reg_label_username), value = "@$username")
            } else {
                CTSettingsRow(
                    label = stringResource(R.string.reg_label_mode),
                    value = stringResource(R.string.reg_mode_anonymous),
                )
            }
            if (!deviceId.isNullOrEmpty()) {
                CTSep()
                CTSettingsRow(
                    label = stringResource(R.string.reg_label_device_id),
                    value = "${deviceId.take(16)}…",
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        CTButton(
            label = stringResource(R.string.reg_continue),
            onClick = onContinue,
        )
    }
}

@Composable
private fun RegistrationErrorContent(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.reg_error_title),
            style = ctBold(18),
            color = CTColor.danger,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = message,
            style = ctRegular(13),
            color = CTColor.textDim,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.weight(1f))
        CTButton(
            label = stringResource(R.string.reg_try_again),
            onClick = onDismiss,
            isDestructive = true,
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingScreenPreview() {
    KonstructMessengerTheme(darkTheme = true) {
        OnboardingContent(
            uiState = OnboardingUiState(),
            publicAlias = "",
            onPublicAliasChange = {},
            onInitialize = {},
            onContinue = {},
            onDismissError = {},
            onRestore = {},
            onLinkDevice = {},
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun OnboardingScreenCompletePreview() {
    KonstructMessengerTheme(darkTheme = true) {
        OnboardingContent(
            uiState = OnboardingUiState(
                step = RegistrationStep.Complete,
                username = "alice",
                deviceId = "f3b8d583698feab0",
            ),
            publicAlias = "alice",
            onPublicAliasChange = {},
            onInitialize = {},
            onContinue = {},
            onDismissError = {},
            onRestore = {},
            onLinkDevice = {},
        )
    }
}
