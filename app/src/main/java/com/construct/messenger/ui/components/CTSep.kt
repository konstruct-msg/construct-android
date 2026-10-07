package com.construct.messenger.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTSpace
import com.construct.messenger.ui.theme.CTSymbol

/** Separator weight — thin (`- - -`) or thick (`= = =`). */
enum class CTSepStyle { THIN, THICK }

/**
 * ASCII separator line.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTSep`.
 * - `CTFont.micro`, `noise` color, leading-aligned, 12dp horizontal padding.
 */
@Composable
fun CTSep(
    modifier: Modifier = Modifier,
    style: CTSepStyle = CTSepStyle.THIN,
) {
    Text(
        text = if (style == CTSepStyle.THIN) CTSymbol.thin() else CTSymbol.thick(),
        style = CTFont.micro,
        color = CTColor.noise,
        textAlign = TextAlign.Start,
        maxLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CTSpace.m),
    )
}
