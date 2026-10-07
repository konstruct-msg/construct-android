package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont

/**
 * System message rendered as terminal-style `> text`.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSystemMessage`.
 * - `>` prefix in `CTFont.ui(12, FontWeight.Bold)` + `accent`.
 * - Body in `CTFont.secondary` + `accent`.
 * - Padding: horizontal 12dp, vertical 2dp.
 *
 * The `>` prefix is decorative terminal chrome; it is not a functional control.
 */
@Composable
fun CTSystemMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Text(
            text = ">",
            style = CTFont.ui(12, FontWeight.Bold),
            color = CTColor.accent,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = CTFont.secondary,
            color = CTColor.accent,
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTSystemMessagePreview() {
    CTSystemMessage(text = "Session initialized")
}
