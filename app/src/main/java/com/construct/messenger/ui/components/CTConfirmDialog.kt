package com.construct.messenger.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * A yes/no question before an action with consequences.
 *
 * Material `AlertDialog` as the theme draws it — container, corners, title, text and buttons all
 * come from `MaterialTheme` (`docs/MATERIAL3_MIGRATION.md`, step 2). What this adds is the one
 * thing Material has no variant for: [isDestructive] sets the confirm button in `error`. Both
 * buttons are otherwise the accent, as Material and iOS alerts both draw a cancel.
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
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            DialogButton(confirmLabel, onConfirm, isDestructive)
        },
        dismissButton = dismissLabel?.let { label ->
            { DialogButton(label, onDismiss) }
        },
    )
}

/** A dialog's action: the theme's text button, or in `error` when it destroys something. */
@Composable
fun DialogButton(label: String, onClick: () -> Unit, isDestructive: Boolean = false) {
    TextButton(
        onClick = onClick,
        colors = if (isDestructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(label)
    }
}

// Previews named …DialogPreview: the screenshot test captures the dialog's window for them.

@Preview
@Composable
private fun CTConfirmDialogPreview() {
    CTConfirmDialog(
        title = "Revoke this device?",
        message = "It stops receiving messages for this account at once.",
        confirmLabel = "Revoke",
        dismissLabel = "Cancel",
        onConfirm = {},
        onDismiss = {},
        isDestructive = true,
    )
}

@Preview
@Composable
private fun CTConfirmNoticeDialogPreview() {
    CTConfirmDialog(
        title = "Profile shared",
        message = "They can see your name and avatar now.",
        confirmLabel = "Close",
        dismissLabel = null,
        onConfirm = {},
        onDismiss = {},
    )
}
