package com.maxeliseyev.konstructmessenger.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                title = "STREAM",
                showSearch = true,
                onSearch = {},
                onSettings = {}
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    text = "No active streams",
                    style = ctRegular(14),
                    color = CTColor.textDim
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Search for identity to start messaging",
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
                        text = "Start Stream",
                        style = ctRegular(14),
                        color = CTColor.text
                    )
                }
            }
        }
    }
}

@Composable
fun CTNavBar(
    title: String,
    showSearch: Boolean = false,
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSearch) {
            Text(
                text = CTSymbol.search,
                style = ctBold(14),
                color = CTColor.accent,
                modifier = Modifier.clickable { onSearch() }
            )
        } else {
            Spacer(modifier = Modifier.size(24.dp))
        }

        Text(
            text = title.uppercase(),
            style = ctBold(13),
            color = CTColor.text,
            letterSpacing = 4.sp
        )

        Text(
            text = CTSymbol.settings,
            style = ctBold(14),
            color = CTColor.textDim,
            modifier = Modifier.clickable { onSettings() }
        )
    }
}