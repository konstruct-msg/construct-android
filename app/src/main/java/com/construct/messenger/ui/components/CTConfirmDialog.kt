package com.construct.messenger.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CornerRadius

/**
 * A yes/no question before an action with consequences.
 *
 * **Canon:** iOS `.alert(title, isPresented:)` with a confirm button and a `.cancel` one.
 * Material `AlertDialog` in CT colours; [isDestructive] paints the confirm button `danger`.
 */
@Composable
fun CTConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    /** Null for a one-button notice. */
    dismissLabel: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CTColor.outMsgBg,
        shape = RoundedCornerShape(CornerRadius.medium),
        title = { Text(title, style = CTFont.ui(15, FontWeight.Bold), color = CTColor.text) },
        text = { Text(message, style = CTFont.body, color = CTColor.textDim) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = CTFont.bodyEmphasis,
                    color = if (isDestructive) CTColor.danger else CTColor.accent,
                )
            }
        },
        dismissButton = dismissLabel?.let { label ->
            {
                TextButton(onClick = onDismiss) {
                    Text(label, style = CTFont.body, color = CTColor.textDim)
                }
            }
        },
    )
}
