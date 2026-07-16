package com.construct.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ctRegular

/**
 * CT-styled segmented control for selecting between modes.
 *
 * **Canon:** iOS `ConstructTheme.swift` → `struct CTModeSelector`.
 * - No spacing between segments.
 * - Accent fill on the selected segment, dim text otherwise.
 * - Rounded rectangle border with subtle accent stroke.
 * - `ctRegular(12)` labels.
 *
 * @param selected Currently selected option.
 * @param options Ordered list of selectable options.
 * @param labels Map from option to display label.
 * @param onSelection Called when an option is tapped.
 * @param width Total width of the control. Defaults to 180dp; pass null to fill parent.
 */
@Composable
fun <T> CTModeSelector(
    selected: T,
    options: List<T>,
    labels: Map<T, String>,
    onSelection: (T) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 180.dp,
) {
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .then(if (width != null) Modifier.defaultMinSize(minWidth = width) else Modifier.fillMaxWidth())
            .height(32.dp)
            .clip(shape)
            .border(width = 0.5.dp, color = CTColor.accent.copy(alpha = 0.4f), shape = shape),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val label = labels[option].orEmpty()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (isSelected) CTColor.accent else Color.Transparent)
                    .clickable { onSelection(option) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = ctRegular(12),
                    color = if (isSelected) CTColor.bg else CTColor.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private enum class PreviewMode {
    OFF,
    AUTO,
    ON,
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 360)
@Composable
private fun CTModeSelectorPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        CTModeSelector(
            selected = PreviewMode.AUTO,
            options = PreviewMode.entries,
            labels = mapOf(
                PreviewMode.OFF to "OFF",
                PreviewMode.AUTO to "AUTO",
                PreviewMode.ON to "ON",
            ),
            onSelection = {},
        )
    }
}
