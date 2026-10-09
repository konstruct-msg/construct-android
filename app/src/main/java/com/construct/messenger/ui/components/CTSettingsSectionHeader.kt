package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.construct.messenger.ui.theme.CTSpace

/**
 * A section's title above its [CTSectionGroup], as `> TITLE`.
 *
 * Material's list subheader (`docs/MATERIAL3_MIGRATION.md`, step 2): `labelLarge` in `primary`.
 * The `>` prefix is ours and stays. Padding: horizontal 12 (the card's edge), top 16 (the gap
 * between sections), bottom 4.
 */
@Composable
fun CTSettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.labelLarge,
        LocalContentColor provides color,
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = CTSpace.m)
                .padding(top = CTSpace.l, bottom = CTSpace.xs),
        ) {
            Text(text = ">")
            Spacer(Modifier.width(CTSpace.s))
            Text(text = title.uppercase())
        }
    }
}
