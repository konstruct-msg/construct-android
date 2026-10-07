package com.construct.messenger.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.components.CTConfirmDialog
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.viewmodel.Deletion

/** iOS `DeleteAccountSheetLayout.abortWindowSeconds`. */
private const val ABORT_SECONDS = 10

/**
 * Delete the account.
 *
 * **Canon:** iOS `DeleteAccountConfirmationView` — title, the irreversible consequence, one red
 * button. Tapping it opens a ten-second window in which the same button aborts (no separate
 * cancel beside it — a "cancel" next to "abort" would read as "go ahead"); then the server is
 * asked, and on its yes everything on this device is erased. If the server cannot confirm, a
 * local-only erase is offered, with the warning that the account may live on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountSheet(
    deletion: Deletion,
    onDelete: () -> Unit,
    onAbort: () -> Unit,
    onDeleteLocally: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmLocal by remember { mutableStateOf(false) }
    val busy = deletion is Deletion.Counting || deletion is Deletion.Requesting
    if (confirmLocal) {
        CTConfirmDialog(
            title = stringResource(R.string.delete_account_local_title),
            message = stringResource(R.string.delete_account_local_warning),
            confirmLabel = stringResource(R.string.delete_account_local_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            onConfirm = { confirmLocal = false; onDeleteLocally() },
            onDismiss = { confirmLocal = false },
        )
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { !busy },
        ),
        containerColor = CTColor.bg,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.delete_account_title), style = CTFont.ui(20, FontWeight.Bold), color = CTColor.text)
            Text(
                text = stringResource(R.string.delete_account_message),
                style = CTFont.ui(14),
                color = CTColor.textDim,
                textAlign = TextAlign.Center,
            )
            if (deletion is Deletion.Failed) {
                Text(
                    text = deletion.message?.let { stringResource(R.string.delete_account_failed, it) }
                        ?: stringResource(R.string.delete_account_not_available),
                    style = CTFont.secondary,
                    color = CTColor.danger,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.delete_account_local_only),
                    style = CTFont.secondary.copy(textDecoration = TextDecoration.Underline),
                    color = CTColor.danger.copy(alpha = 0.8f),
                    modifier = Modifier.clickable { confirmLocal = true },
                )
            }
            val shape = RoundedCornerShape(CornerRadius.small)
            when (deletion) {
                Deletion.Requesting -> Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CTColor.danger, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                is Deletion.Counting -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(CTColor.danger.copy(alpha = 0.08f), shape)
                            .border(1.dp, CTColor.danger.copy(alpha = 0.5f), shape)
                            .clickable(onClick = onAbort),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.delete_account_abort_hint, deletion.secondsLeft),
                            style = CTFont.ui(15, FontWeight.Bold),
                            color = CTColor.danger,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { 1f - (deletion.secondsLeft - 1).toFloat() / ABORT_SECONDS },
                        color = CTColor.danger,
                        trackColor = CTColor.noise,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(CTColor.danger.copy(alpha = 0.15f), shape)
                        .border(1.dp, CTColor.danger, shape)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.delete_account_button), style = CTFont.ui(16, FontWeight.Bold), color = CTColor.danger)
                }
            }
            if (!busy) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = CTFont.ui(15),
                    color = CTColor.textDim,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}
