package com.construct.messenger.ui.screens.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTIcon
import com.construct.messenger.ui.theme.CTSpace
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One button behind a row. Colour is the button's fill; the icon and label are drawn in [tint]. */
data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val tint: Color,
    val onClick: () -> Unit,
)

/** iOS swipe buttons are about this wide each. */
private val ACTION_WIDTH = 74.dp

/**
 * A row with buttons behind it, revealed by a horizontal swipe. **Canon:** iOS `.swipeActions`:
 * [trailing] behind the right edge (the first is the outermost), no full swipe; [leading]
 * behind the left edge, and a full swipe to the right runs it. The buttons widen with the
 * finger, one row is open at a time ([open] / [onOpenChange]), and a tap on an open row closes it.
 *
 * TalkBack cannot swipe a row; the same actions are on it as custom accessibility actions.
 */
@Composable
fun SwipeActionsRow(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    leading: SwipeAction?,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable (onTapWhenOpen: (() -> Unit)?) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val actionPx = with(density) { ACTION_WIDTH.toPx() }
    val leadingPx = if (leading != null) actionPx else 0f
    val trailingPx = actionPx * trailing.size
    val currentOpen by rememberUpdatedState(open)
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val currentLeading by rememberUpdatedState(leading)

    fun settle(to: Float) = scope.launch { offset.animateTo(to, spring(dampingRatio = 0.85f)) }

    // Another row opened: this one closes.
    LaunchedEffect(open) {
        if (!open && offset.value != 0f) offset.animateTo(0f, spring(dampingRatio = 0.85f))
    }

    val run: (SwipeAction) -> Unit = { action ->
        action.onClick()
        settle(0f)
        onOpenChange(false)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = listOfNotNull(leading, *trailing.toTypedArray()).map { action ->
                    CustomAccessibilityAction(action.label) { action.onClick(); true }
                }
            },
    ) {
        val revealed = offset.value
        // Sized to the row once the row is measured, so the buttons can fill its height.
        Box(modifier = Modifier.matchParentSize()) {
            if (revealed > 0f && leading != null) {
                ActionButton(
                    action = leading,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(with(density) { revealed.toDp() })
                        .fillMaxHeight(),
                    onClick = { run(leading) },
                )
            }
            if (revealed < 0f && trailing.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(with(density) { (-revealed).toDp() })
                        .fillMaxHeight(),
                ) {
                    // iOS lists the outermost first; drawn left to right, it goes last.
                    trailing.asReversed().forEach { action ->
                        ActionButton(action, Modifier.weight(1f).fillMaxHeight(), onClick = { run(action) })
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(leadingPx, trailingPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val x = offset.value
                            val full = width * 0.5f
                            when {
                                currentLeading != null && x > full -> {
                                    currentLeading?.onClick?.invoke()
                                    settle(0f)
                                    currentOnOpenChange(false)
                                }
                                leadingPx > 0f && x > leadingPx / 2 -> {
                                    settle(leadingPx)
                                    currentOnOpenChange(true)
                                }
                                trailingPx > 0f && x < -trailingPx / 2 -> {
                                    settle(-trailingPx)
                                    currentOnOpenChange(true)
                                }
                                else -> {
                                    settle(0f)
                                    if (currentOpen) currentOnOpenChange(false)
                                }
                            }
                        },
                        onDragCancel = { settle(0f) },
                    ) { change, dx ->
                        change.consume()
                        // The leading side runs on to a full swipe; the trailing side stops at its buttons.
                        val max = if (leadingPx > 0f) width * 0.9f else 0f
                        scope.launch { offset.snapTo((offset.value + dx).coerceIn(-trailingPx, max)) }
                    }
                },
        ) {
            content(
                if (offset.value != 0f) {
                    { settle(0f); currentOnOpenChange(false) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ActionButton(action: SwipeAction, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(action.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(ACTION_WIDTH).padding(horizontal = CTSpace.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CTSpace.xs),
        ) {
            Icon(action.icon, contentDescription = null, tint = action.tint, modifier = Modifier.size(CTIcon.nav))
            Text(
                text = action.label,
                style = CTFont.caption,
                color = action.tint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
