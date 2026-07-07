package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
            .padding(top = 24.dp)
    ) {
        CTNavBar(
            title = stringResource(R.string.settings_title),
            showBack = true,
            onBack = onNavigateBack,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = ctRegular(14),
                color = CTColor.textDim
            )
        }
    }
}
