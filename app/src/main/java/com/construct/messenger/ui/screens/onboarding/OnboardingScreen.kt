package com.construct.messenger.ui.screens.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import com.construct.messenger.ui.components.CTButton
import com.construct.messenger.ui.components.CTTextField
import com.construct.messenger.ui.theme.CTColor
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
        onInitialize = viewModel::initializeIdentity,
        onRestore = onRestore,
        onLinkDevice = onLinkDevice,
    )
}

@Composable
private fun OnboardingContent(
    uiState: OnboardingUiState,
    publicAlias: String,
    onPublicAliasChange: (String) -> Unit,
    onInitialize: () -> Unit,
    onRestore: (() -> Unit)?,
    onLinkDevice: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .padding(horizontal = 28.dp, vertical = 36.dp)
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
                label = if (uiState.isInitializing) {
                    stringResource(R.string.onboarding_initializing_action)
                } else {
                    stringResource(R.string.onboarding_init_action)
                },
                onClick = onInitialize,
                enabled = !uiState.isInitializing,
            )

            if (uiState.errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = uiState.errorMessage,
                    style = ctRegular(12),
                    color = CTColor.danger,
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(modifier = Modifier.height(28.dp))
            }

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
            onRestore = {},
            onLinkDevice = {},
        )
    }
}
