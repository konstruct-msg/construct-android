package com.maxeliseyev.konstructmessenger.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.maxeliseyev.konstructmessenger.R
import com.maxeliseyev.konstructmessenger.ui.theme.CTColor
import com.maxeliseyev.konstructmessenger.ui.theme.ctBold
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToMain: () -> Unit
) {
    val isRegistered = false

    LaunchedEffect(Unit) {
        delay(1000)
        if (isRegistered) {
            onNavigateToMain()
        } else {
            onNavigateToOnboarding()
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