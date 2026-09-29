package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.construct.messenger.R
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.viewmodel.SecurityNoticeViewModel

/**
 * App-wide notice for a contact's security event, over whatever screen is up; Open goes to their
 * chat, where the banner stays until acknowledged. **Canon:** iOS `KeyChangeUX` notices.
 */
@Composable
fun SecurityNoticeHost(
    onOpenChat: (String) -> Unit,
    viewModel: SecurityNoticeViewModel = hiltViewModel(),
) {
    val host = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.announcements.collect { event ->
            val message = when (event.notice) {
                SecurityNotice.NONE -> return@collect
                SecurityNotice.ADDRESS_CHANGED -> context.getString(R.string.address_change_toast_fmt, event.name)
                SecurityNotice.NEW_DEVICE -> context.getString(R.string.new_device_toast_fmt, event.name)
            }
            val result = host.showSnackbar(
                message = message,
                actionLabel = context.getString(R.string.key_change_toast_open),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenChat(event.userId)
        }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        SnackbarHost(host)
    }
}
