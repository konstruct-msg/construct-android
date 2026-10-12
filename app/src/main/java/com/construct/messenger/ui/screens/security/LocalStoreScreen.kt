package com.construct.messenger.ui.screens.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.theme.CTSpace

/** Over everything while Room's rows move into the core's store (TODO 136): the screens below wait for it. */
@Composable
fun LocalStoreImportingScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(CTSpace.xl),
            verticalArrangement = Arrangement.spacedBy(CTSpace.l, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.local_store_importing),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.local_store_importing_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The core's store is on this device and would not open. **Canon:** iOS `KeysRecoveryView`,
 * reason `.storeUnreadable`: try again, or start over. Nothing is created over the store, and the
 * recovery phrase is not offered — it brings back the account, not what this device kept.
 */
@Composable
fun LocalStoreUnreadableScreen(onRetry: () -> Unit, onErase: () -> Unit) {
    var confirmErase by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(CTSpace.xl),
            verticalArrangement = Arrangement.spacedBy(CTSpace.l),
        ) {
            Text(
                stringResource(R.string.local_store_unreadable_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.local_store_unreadable_body), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(
                stringResource(R.string.local_store_unreadable_retry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            HorizontalDivider()
            Text(
                stringResource(R.string.local_store_unreadable_erase_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { confirmErase = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.local_store_unreadable_erase)) }
        }
    }
    if (confirmErase) {
        CTConfirmDialog(
            title = stringResource(R.string.local_store_unreadable_erase),
            message = stringResource(R.string.local_store_unreadable_erase_confirm),
            confirmLabel = stringResource(R.string.pin_reset_action),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = onErase,
            onDismiss = { confirmErase = false },
            isDestructive = true,
        )
    }
}

@Preview
@Composable
private fun LocalStoreImportingScreenPreview() {
    LocalStoreImportingScreen()
}

@Preview
@Composable
private fun LocalStoreUnreadableScreenPreview() {
    LocalStoreUnreadableScreen(onRetry = {}, onErase = {})
}
