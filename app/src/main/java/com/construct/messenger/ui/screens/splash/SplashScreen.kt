package com.construct.messenger.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.viewmodel.SplashRoute
import com.construct.messenger.viewmodel.SplashViewModel

@Composable
fun SplashScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToMain: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.decideNextRoute()
    }

    LaunchedEffect(uiState.route) {
        when (uiState.route) {
            SplashRoute.Onboarding -> onNavigateToOnboarding()
            SplashRoute.Main -> onNavigateToMain()
            null -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.brand_name),
            style = ctBold(24),
            color = CTColor.text,
            letterSpacing = 8.sp
        )
    }
}