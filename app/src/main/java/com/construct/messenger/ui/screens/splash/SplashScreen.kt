package com.construct.messenger.ui.screens.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.components.CTLogoView
import com.construct.messenger.ui.components.CTNoise
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.viewmodel.SplashRoute
import com.construct.messenger.viewmodel.SplashViewModel

@Composable
fun SplashScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToOrientation: () -> Unit,
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
            SplashRoute.Orientation -> onNavigateToOrientation()
            SplashRoute.Main -> onNavigateToMain()
            null -> Unit
        }
    }

    // iOS `SplashView`: the logo in the accent over terminal noise. iOS fades the logo in too; here
    // the window background (`window_splash`) already shows it in the same place from the first
    // frame, so only the noise fades — fading the logo would blink it out and back.
    val noiseAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        noiseAlpha.animateTo(1f, tween(durationMillis = 500, easing = FastOutLinearInEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg),
        contentAlignment = Alignment.Center
    ) {
        CTNoise(
            rows = 48,
            cols = 24,
            modifier = Modifier
                .fillMaxSize()
                .alpha(noiseAlpha.value),
        )
        CTLogoView(size = 72.dp, color = CTColor.accent)
    }
}
