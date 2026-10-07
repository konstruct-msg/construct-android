package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.R
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace

/**
 * Search input field.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSearchBar`.
 * - Leading magnifying-glass icon in `textDim`.
 * - `CTFont.body` input text in `text`; placeholder in `textDim`.
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
    // iOS `CTSearchBar`: a capsule of `.ultraThinMaterial` with a 15 % white hairline — the
    // platform search-field shape in the CT palette. Material blur has no cheap Compose
    // equivalent; a translucent card fill reads the same over the noise background.
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CTColor.bgMsg.copy(alpha = 0.72f))
            .border(1.dp, CTColor.fieldStroke, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = CTColor.textDim,
            modifier = Modifier.size(CTIcon.row),
        )
        Spacer(Modifier.width(CTSpace.s))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = CTFont.body.copy(color = CTColor.text),
            cursorBrush = SolidColor(CTColor.accent),
            singleLine = true,
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = CTFont.body,
                        color = CTColor.textDim,
                    )
                }
                innerTextField()
            }
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(CTSpace.s))
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.search_clear),
                    tint = CTColor.textDim,
                    modifier = Modifier.size(CTIcon.row),
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
