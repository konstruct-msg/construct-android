package com.construct.messenger.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.construct.messenger.R
import com.construct.messenger.ui.components.MicSwitch
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTLayout
import com.construct.messenger.ui.theme.ctBold
import com.construct.messenger.ui.theme.ctRegular
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The chat header's actions — search, call, video call — behind one button, so the header keeps
 * its quiet on a small screen. **Canon:** iOS `ChatActionPalette.swift`;
 * `decisions/chat-header-actions-are-one-palette.md`.
 *
 * Two ways in, one palette. A tap opens it with every action named, to be tapped: that is how the
 * actions are found. A hold opens it under the finger, which slides toward an action and lets go:
 * that is how a hand that knows them works without looking (a marking menu). Each action keeps one
 * direction — search to the left, call on the diagonal, video straight down — so the hand can learn it.
 */

enum class ChatAction(val dx: Float, val dy: Float, val icon: ImageVector, val label: Int) {
    SEARCH(-1f, 0f, Icons.Filled.Search, R.string.chat_action_search),
    CALL(-0.7071f, 0.7071f, Icons.Outlined.Phone, R.string.chat_action_call),
    VIDEO_CALL(0f, 1f, Icons.Filled.Videocam, R.string.chat_action_video);

    companion object {
        /** Search always; calls with a callable contact. */
        fun available(canCall: Boolean): List<ChatAction> = if (canCall) listOf(SEARCH, CALL, VIDEO_CALL) else listOf(SEARCH)
    }
}

/** The palette while it is open. */
sealed interface ChatActionPaletteState {
    /** Opened by a tap: it stays, every action is a button, a tap elsewhere closes it. */
    data object Tapped : ChatActionPaletteState

    /** Opened by a hold: it follows the finger; [selected] is the action under it, if any. */
    data class Held(val selected: ChatAction?) : ChatActionPaletteState
}

/** Where things sit, in dp from the button's centre. Pure. */
object ChatActionPaletteGeometry {
    /**
     * Far enough that the thumb on the button does not cover the action it slides to, and that
     * neighbours 45° apart leave more than half a fingertip between them.
     */
    val RADIUS = 110.dp
    val ITEM_SIZE = 52.dp
    /** A finger that has barely moved has chosen nothing yet. */
    val DEAD_ZONE = 28.dp
    /** A finger this far past the arc has given up; letting go there starts nothing. */
    val CANCEL_BEYOND = 64.dp
    /**
     * Half the angle between neighbours: closer than this to an action's direction picks it. Not
     * wider: at 45° "down" without video would pick the call, and a direction must mean one action
     * whatever else is offered.
     */
    const val SECTOR_HALF_ANGLE = 22.5

    fun offset(action: ChatAction): Pair<Dp, Dp> = RADIUS * action.dx to RADIUS * action.dy

    /** The action a finger at ([dx], [dy]) dp from the button's centre chooses, or null. */
    fun action(dx: Float, dy: Float, among: List<ChatAction>): ChatAction? {
        val distance = hypot(dx, dy)
        if (distance < DEAD_ZONE.value || distance > RADIUS.value + CANCEL_BEYOND.value) return null
        val angle = atan2(dy.toDouble(), dx.toDouble())
        val nearest = among.minByOrNull { angularDistance(angle, it) } ?: return null
        return nearest.takeIf { angularDistance(angle, it) <= SECTOR_HALF_ANGLE * PI / 180 }
    }

    private fun angularDistance(angle: Double, action: ChatAction): Double {
        val target = atan2(action.dy.toDouble(), action.dx.toDouble())
        val d = abs(angle - target) % (2 * PI)
        return min(d, 2 * PI - d)
    }
}

/**
 * The header's action button: vertical dots where the magnifier was. With a single action (no
 * callable contact) it is that action's own button — a palette of one is a detour.
 */
@Composable
fun ChatActionButton(actions: List<ChatAction>, onAction: (ChatAction) -> Unit) {
    val act by rememberUpdatedState(onAction)
    if (actions.size == 1) {
        val only = actions.single()
        Box(
            modifier = Modifier.size(CTLayout.hitTarget).clickable { act(only) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(only.icon, contentDescription = stringResource(only.label), tint = CTColor.text, modifier = Modifier.size(CTLayout.navIconSize))
        }
        return
    }
    var palette by remember { mutableStateOf<ChatActionPaletteState?>(null) }
    var center by remember { mutableStateOf(Offset.Zero) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val label = stringResource(R.string.chat_actions)
    val actionLabels = actions.map { stringResource(it.label) }
    Box(
        modifier = Modifier
            .size(CTLayout.hitTarget)
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                center = Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f)
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { palette = if (palette == null) ChatActionPaletteState.Tapped else null; true }
                // TalkBack cannot hold and slide; every action is a named action on the button.
                customActions = actions.mapIndexed { i, a -> CustomAccessibilityAction(actionLabels[i]) { act(a); true } }
            }
            .pointerInput(actions) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val wasOpen = palette != null
                    // Only letting go ends the wait: a finger already on its way to an action is
                    // still a hold (iOS: the press timer runs whatever the finger does).
                    val released = withTimeoutOrNull(MicSwitch.PRESS_DELAY_MS) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                        true
                    } == true
                    if (released || wasOpen) {
                        // A tap: open the named palette, or close it if it was open.
                        if (released) palette = if (wasOpen) null else ChatActionPaletteState.Tapped
                        else while (awaitPointerEvent().changes.any { it.pressed }) Unit
                        return@awaitEachGesture
                    }
                    palette = ChatActionPaletteState.Held(null)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val origin = Offset(size.width / 2f, size.height / 2f)
                    var selected: ChatAction? = null
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        val d = (change.position - origin) / density.density
                        val next = ChatActionPaletteGeometry.action(d.x, d.y, actions)
                        if (next != null && next != selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selected = next
                        palette = ChatActionPaletteState.Held(next)
                        change.consume()
                        if (!change.pressed) break
                    }
                    palette = null
                    selected?.let(act)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = CTColor.accent, modifier = Modifier.size(CTLayout.navIconSizeLg))
    }
    palette?.let { state ->
        ChatActionPaletteView(
            state = state,
            actions = actions,
            centerPx = center,
            onAction = {
                palette = null
                act(it)
            },
            onDismiss = { palette = null },
        )
    }
}

/**
 * Drawn over the whole window, centred on the button. Held, it is only a picture — the finger's
 * drag belongs to the button. Tapped, its actions are buttons and the dimmed rest closes it.
 */
@Composable
private fun ChatActionPaletteView(
    state: ChatActionPaletteState,
    actions: List<ChatAction>,
    centerPx: Offset,
    onAction: (ChatAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val tapped = state == ChatActionPaletteState.Tapped
    val selected = (state as? ChatActionPaletteState.Held)?.selected
    // The chat's window, read here: inside the popup LocalView is the popup's own, empty at first.
    val root = LocalView.current.rootView
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = tapped, clippingEnabled = false),
    ) {
        val density = LocalDensity.current
        // The whole window, bars and composer included. A popup's own constraints are the visible
        // display frame — the screen less the system bars — so fillMaxSize, placed from the top
        // edge, stopped short and dimmed the composer only partly.
        val fullWidth = with(density) { root.width.toDp() }
        val fullHeight = with(density) { root.height.toDp() }
        BoxWithConstraints(
            Modifier
                .requiredSize(fullWidth, fullHeight)
                .background(SCRIM)
                .then(
                    if (tapped) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    else Modifier,
                ),
        ) {
            val cx = with(density) { centerPx.x.toDp() }
            val cy = with(density) { centerPx.y.toDp() }
            for (action in actions) {
                val (ox, oy) = ChatActionPaletteGeometry.offset(action)
                val isSelected = selected == action
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .offset { centeredAt(cx + ox, cy + oy, ChatActionPaletteGeometry.ITEM_SIZE, density.density) }
                        .wrapContentSize(unbounded = true)
                        .scale(if (isSelected) 1.15f else 1f)
                        .then(if (tapped) Modifier.clickable { onAction(action) } else Modifier),
                ) {
                    // Lifted off the dimmed chat: the page's own colour, a shadow and a hairline —
                    // in the light theme the grey message colour was lost against it.
                    Box(
                        modifier = Modifier
                            .size(ChatActionPaletteGeometry.ITEM_SIZE)
                            .shadow(8.dp, CircleShape)
                            .background(if (isSelected) CTColor.accent else CTColor.bg, CircleShape)
                            .border(1.dp, CTColor.textDim.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(action.icon, contentDescription = null, tint = if (isSelected) CTColor.bg else CTColor.accent, modifier = Modifier.size(CTLayout.navIconSize))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(action.label),
                        style = ctRegular(11),
                        color = CTColor.text,
                        modifier = Modifier.background(CTColor.bg, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            // The action under the finger, named away from the hand: below the header on the left.
            if (selected != null) {
                Text(
                    text = stringResource(selected.label),
                    style = ctBold(15),
                    color = CTColor.text,
                    modifier = Modifier
                        .offset { IntOffset((maxWidth * 0.3f).roundToPx(), (cy + ChatActionPaletteGeometry.RADIUS).roundToPx()) }
                        .shadow(8.dp, CircleShape)
                        .background(CTColor.bg, CircleShape)
                        .padding(horizontal = CTLayout.edgePad, vertical = CTLayout.inlinePad),
                )
            }
        }
    }
}

/** Darker than iOS's 0.25: over a material blur that is enough, over a flat chat it was not. */
private val SCRIM = Color.Black.copy(alpha = 0.45f)

/** An item's top-left so that its circle is centred on ([x], [y]). */
private fun centeredAt(x: Dp, y: Dp, item: Dp, density: Float) =
    IntOffset(((x - item / 2).value * density).roundToInt(), ((y - item / 2).value * density).roundToInt())

/** The popup covers the window from its top-left corner; everything inside is placed in window coordinates. */
private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}
