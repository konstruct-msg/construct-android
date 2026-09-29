package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor

/**
 * A hairline between rows of a choice list.
 *
 * **Canon:** iOS `ConstructRowComponents.swift` → `ConstructRowDivider` — a system divider tinted
 * `noise`, inset from the leading edge (54 by default; Appearance uses 52). Unlike [CTSep] it is
 * a line, not text, so it adds no height to the rows it separates.
 */
@Composable
fun CTRowDivider(indent: Dp = 54.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(start = indent)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(CTColor.noise),
    )
}
