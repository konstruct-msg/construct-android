package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CornerRadius
import com.construct.messenger.ui.theme.HairlineBorder

/**
 * Full-width primary button.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTButton`.
 * - `CTFont.bodyEmphasis`, full width, 14dp vertical padding, corner radius 8.
 * - Normal: fg `bg`, bg `accent`. Destructive: fg white, bg `danger`.
 * - Disabled: fg `textDim`, bg `disabledBg`, with a 0.5dp `noise` border.
 */
@Composable
fun CTButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
) {
    val fgColor = when {
        !enabled -> CTColor.textDim
        isDestructive -> Color.White
        else -> CTColor.bg
    }
    val bgColor = when {
        !enabled -> CTColor.disabledBg
        isDestructive -> CTColor.danger
        else -> CTColor.accent
    }
    val shape = RoundedCornerShape(CornerRadius.control)

    Text(
        text = label,
        style = CTFont.bodyEmphasis,
        color = fgColor,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .background(bgColor)
            .then(
                if (enabled) Modifier
                else Modifier.border(HairlineBorder, CTColor.noise, shape)
            )
            .padding(vertical = 14.dp),
    )
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 320)
@Composable
private fun CTButtonPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        CTButton(label = "CREATE IDENTITY", onClick = {})
        CTButton(label = "DELETE", onClick = {}, isDestructive = true)
        CTButton(label = "DISABLED", onClick = {}, enabled = false)
    }
}
