package com.construct.messenger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.HairlineBorder

/**
 * The card one settings section's rows sit in.
 *
 * Material `OutlinedCard` (`docs/MATERIAL3_MIGRATION.md`, step 2): shape and colours come from the
 * theme — the container is `surfaceContainer`, where Material puts a grouped list, and the corners
 * the theme's `medium`. The outline stays a hairline, as everywhere else in the app; Material's own
 * is 1 dp. Wrap the rows of one section, not the [CTSettingsSectionHeader] above them: the
 * header's top padding is the gap between sections.
 */
@Composable
fun CTSectionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier.padding(horizontal = CTSpace.m),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(HairlineBorder, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}
