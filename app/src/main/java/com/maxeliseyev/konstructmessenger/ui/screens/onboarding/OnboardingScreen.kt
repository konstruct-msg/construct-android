package com.maxeliseyev.konstructmessenger.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxeliseyev.konstructmessenger.R
import com.maxeliseyev.konstructmessenger.ui.theme.CTColor
import com.maxeliseyev.konstructmessenger.ui.theme.CTSymbol
import com.maxeliseyev.konstructmessenger.ui.theme.ctBold
import com.maxeliseyev.konstructmessenger.ui.theme.ctRegular

@Composable
fun OnboardingScreen(
    onInitialized: () -> Unit
) {
    var state by remember { mutableStateOf("initial") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.brand_name),
                    style = ctBold(18),
                    color = CTColor.text,
                    letterSpacing = 6.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = ctBold(14),
                    color = CTColor.textDim,
                    letterSpacing = 2.sp
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state = "generating" }
                    .background(CTColor.bgMsg)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state == "generating") CTSymbol.loading
                           else stringResource(R.string.onboarding_init_action),
                    style = ctBold(14),
                    color = CTColor.accent,
                    letterSpacing = 2.sp
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.onboarding_security_title),
                    style = ctBold(10),
                    color = CTColor.textDim,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_security_body),
                    style = ctRegular(12),
                    color = CTColor.textDim,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}