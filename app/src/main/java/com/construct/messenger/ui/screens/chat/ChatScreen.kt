package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

@Composable
fun ChatScreen(contactId: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CTColor.bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = contactId,
            style = ctRegular(14),
            color = CTColor.textDim
        )
    }
}
