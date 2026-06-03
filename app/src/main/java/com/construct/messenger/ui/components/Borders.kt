package com.construct.messenger.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTColor

/** 0.5dp separator line on the bottom edge — iOS `ctBorderBottom()`. */
fun Modifier.ctBorderBottom(): Modifier = drawBehind {
    val stroke = 0.5.dp.toPx()
    drawLine(
        color = CTColor.noise,
        start = Offset(0f, size.height - stroke / 2f),
        end = Offset(size.width, size.height - stroke / 2f),
        strokeWidth = stroke,
    )
}

/** 0.5dp separator line on the top edge — iOS `ctBorderTop()`. */
fun Modifier.ctBorderTop(): Modifier = drawBehind {
    val stroke = 0.5.dp.toPx()
    drawLine(
        color = CTColor.noise,
        start = Offset(0f, stroke / 2f),
        end = Offset(size.width, stroke / 2f),
        strokeWidth = stroke,
    )
}
