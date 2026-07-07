package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

/**
 * Search input field.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSearchBar`.
 * - Leading magnifying-glass icon in `textDim`.
 * - `ctRegular(13)` input text in `text`; placeholder in `textDim`.
 * - `bgMsg` background with a 0.5dp `noise` bottom border.
 * - Clear button appears when the query is non-empty.
 */
@Composable
fun CTSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = stringResource(R.string.search_prompt),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CTColor.bgMsg)
            .ctBorderBottom()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = CTColor.textDim,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = ctRegular(13).copy(color = CTColor.text),
            cursorBrush = SolidColor(CTColor.accent),
            singleLine = true,
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = ctRegular(13),
                        color = CTColor.textDim,
                    )
                }
                innerTextField()
            }
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = null,
                    tint = CTColor.textDim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTSearchBarEmptyPreview() {
    CTSearchBar(
        query = "",
        onQueryChange = {},
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true)
@Composable
private fun CTSearchBarFilledPreview() {
    CTSearchBar(
        query = "silent fox",
        onQueryChange = {},
    )
}
