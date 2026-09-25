package com.construct.messenger.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular

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
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CTColor.outMsgBg,
        shape = RoundedCornerShape(CornerRadius.medium),
        title = { Text(title, style = ctBold(15), color = CTColor.text) },
        text = { Text(message, style = ctRegular(13), color = CTColor.textDim) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = ctBold(13),
                    color = if (isDestructive) CTColor.danger else CTColor.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, style = ctRegular(13), color = CTColor.textDim)
            }
        },
    )
}
