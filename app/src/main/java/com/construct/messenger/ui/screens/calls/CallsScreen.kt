package com.construct.messenger.ui.screens.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

@Composable
fun CallsScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.calls_empty_title),
            style = ctRegular(14),
            color = CTColor.textDim
        )
    }
}
