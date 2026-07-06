package com.construct.messenger.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTNavBar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import com.construct.messenger.viewmodel.MainViewModel

@Composable
fun MainScreen(
    onNavigateToChat: (String) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val canStartStream = uiState.suggestedContactId.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 24.dp)
        ) {
            CTNavBar(
                title = stringResource(R.string.nav_streams),
                trailingIcon = Icons.Default.Settings,
                trailingSecondaryIcon = Icons.Default.Search,
                onTrailingAction = {},
                onTrailingSecondaryAction = {},
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.main_empty_title),
                    style = ctRegular(14),
                    color = CTColor.textDim
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.main_empty_subtitle),
                    style = ctRegular(12),
                    color = CTColor.textDim,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canStartStream) {
                        onNavigateToChat(uiState.suggestedContactId)
                    }
                    .background(CTColor.bgMsg)
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = CTColor.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.main_start_stream),
                        style = ctRegular(14),
                        color = CTColor.text
                    )
                }
            }
        }
    }
}
