package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.Spacing

/**
 * Chat composer: text field + send. Send is visible only when the field is not empty.
 *
 * **Canon:** iOS `MessageInputView` / `ANDROID_ONBOARDING.md` §5.4.
 */
@Composable
fun MessageInputView(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CTColor.bg)
            .padding(horizontal = CTLayout.edgePad, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CTTextField(
            placeholder = stringResource(R.string.chat_input_placeholder),
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotBlank()) {
            Spacer(Modifier.width(Spacing.small))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send),
                tint = if (enabled) CTColor.accent else CTColor.textDim,
                modifier = Modifier
                    .size(CTLayout.navIconSizeLg)
                    .clickable(enabled = enabled, onClick = onSend),
            )
        }
    }
}

@Preview
@Composable
private fun MessageInputViewPreview() {
    MessageInputView(value = "hello", onValueChange = {}, onSend = {})
}
