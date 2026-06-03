package com.maxeliseyev.konstructmessenger.ui.screens.main

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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxeliseyev.konstructmessenger.R
import com.maxeliseyev.konstructmessenger.ui.components.CTNavBar
import com.maxeliseyev.konstructmessenger.ui.theme.CTColor
import com.maxeliseyev.konstructmessenger.ui.theme.CTSymbol
import com.maxeliseyev.konstructmessenger.ui.theme.ctBold
import com.maxeliseyev.konstructmessenger.ui.theme.ctRegular

@Composable
fun MainScreen(
    onNavigateToChat: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
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
                    .clickable { onNavigateToChat("test_contact") }
                    .background(CTColor.bgMsg)
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = CTSymbol.add,
                        style = ctBold(14),
                        color = CTColor.accent
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
