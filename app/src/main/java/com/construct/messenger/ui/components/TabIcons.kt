package com.construct.messenger.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Tab glyphs drawn to the shapes of the iOS tab bar — `message` and `circle.grid.cross` — where
 * Material has nothing close. Drawn, not copied: SF Symbols are licensed for Apple platforms only.
 * Outline when idle, filled when selected, as the iOS tab bar does.
 */
object TabIcons {
    val Chats: ImageVector by lazy { bubble(filled = false) }
    val ChatsSelected: ImageVector by lazy { bubble(filled = true) }
    val Synaps: ImageVector by lazy { crossGrid(filled = false) }
    val SynapsSelected: ImageVector by lazy { crossGrid(filled = true) }

    private const val STROKE = 1.6f

    /** An oval speech bubble with its tail at the lower left. */
    private fun bubble(filled: Boolean) = icon(if (filled) "TabChatsFilled" else "TabChats") {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Ellipse centred (12, 11), radii 9 × 7.5; the tail leaves it between 110° and 135°.
            moveTo(8.92f, 18.05f)
            arcTo(9f, 7.5f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 5.64f, y1 = 16.3f)
            lineTo(4.2f, 20.4f)
            close()
        }
    }

    /** Five dots in a cross — the Synapses cloud in miniature. */
    private fun crossGrid(filled: Boolean) = icon(if (filled) "TabSynapsFilled" else "TabSynaps") {
        val r = if (filled) 3.0f else 2.6f
        for ((cx, cy) in listOf(12f to 4.5f, 4.5f to 12f, 12f to 12f, 19.5f to 12f, 12f to 19.5f)) {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = STROKE,
            ) { circle(cx, cy, r) }
        }
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()
}
