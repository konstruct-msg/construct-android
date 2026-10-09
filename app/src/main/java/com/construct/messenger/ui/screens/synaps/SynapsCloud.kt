package com.construct.messenger.ui.screens.synaps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.construct.messenger.data.model.Contact
import com.construct.messenger.ui.components.CTAvatar
import com.construct.messenger.ui.components.rememberAvatar
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.CTFont
import com.construct.messenger.ui.theme.CTSpace
import kotlin.math.hypot

private const val MIN_ZOOM = 0.20f
private const val MAX_ZOOM = 3.0f

/**
 * The Synapses cloud: every contact as a circle on a hexagonal spiral, the most active in the
 * middle, seen through the lens ([SynapsCloudLayout], [SynapsLens]). Pinch zooms (around the
 * fingers), a drag pans the cloud through the lens, a tap opens the profile. A drag that ends over a
 * circle does not open it: the drag consumes the gesture.
 *
 * The canvas fills its parent; [topInset] is how much of it is covered at the top (the bar, search
 * and requests drawn over the cloud). The lens and the cloud's centre sit in the middle of what is
 * left. The cloud opens at zoom 1 — fitting everyone in would shrink them twice, once by the zoom
 * and once by the lens.
 *
 * **Canon:** iOS `SynapsView` (vault TODO 133).
 */
@Composable
fun SynapsCloud(
    contacts: List<Contact>,
    metrics: Map<String, ContactMetrics>,
    blockedIds: Set<String>,
    topInset: Dp,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(contacts, metrics) {
        val byId = contacts.associateBy { it.userId }
        SynapsCloudLayout.order(byId.keys.toList(), metrics).map { byId.getValue(it) }
    }
    val points = remember(ordered.size) { SynapsCloudLayout.spiral(ordered.size) }
    val extent = remember(points) { SynapsCloudLayout.halfExtent(points) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    val px = density.density
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            // On the container, not on a layer under the circles: a drag that starts on a circle
            // must pan too. The circles still get a plain tap; a drag consumes it, so a drag that
            // ends over a circle does not open its profile.
            .pointerInput(extent, topInset) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val next = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    // The zoom grows from between the fingers: what is under them stays under them.
                    // From the lens centre (as iOS anchors it), a pinch over a contact near the edge
                    // pushed it out from under the fingers and into the lens's squeeze, and the
                    // pinch seemed to work only in the middle.
                    val topPx = topInset.toPx()
                    val lensCentre = Offset(size.width / 2f, topPx + (size.height - topPx) / 2f)
                    val scaled = centroid - lensCentre - (centroid - lensCentre - pan) * (next / zoom) + panChange
                    val maxX = extent.x * px * next
                    val maxY = extent.y * px * next
                    pan = Offset(scaled.x.coerceIn(-maxX, maxX), scaled.y.coerceIn(-maxY, maxY))
                    zoom = next
                }
            },
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val top = with(density) { topInset.toPx() }
        val cx = width / 2
        val cy = top + (height - top) / 2
        val inset = SynapsLens.INSET * px
        val lens = SynapsLens(radiusX = width / 2 - inset, radiusY = (height - top) / 2 - inset)

        ordered.forEachIndexed { i, contact ->
            val point = points[i]
            val m = metrics[contact.userId] ?: ContactMetrics()
            val d0 = SynapsCloudLayout.diameter(m.frequencyScore).dp
            // Where the lens draws this contact — read in the draw phase, so a gesture redraws
            // without recomposing the cloud.
            fun drawn(): SynapsLens.Drawn = lens.draw(
                x = cx + pan.x + point.x * px * zoom,
                y = cy + pan.y + point.y * px * zoom,
                cx = cx,
                cy = cy,
            )
            CloudNode(
                contact = contact,
                metrics = m,
                blocked = contact.userId in blockedIds,
                diameter = d0,
                onClick = { onOpen(contact.userId) },
                place = {
                    val p = drawn()
                    val s = zoom * SynapsLens.scale(p.rim)
                    NodePlacement(
                        x = p.x,
                        y = p.y,
                        scale = s,
                        alpha = SynapsLens.opacity(p.rim),
                        labelAlpha = SynapsLens.labelOpacity(hypot(p.x - cx, p.y - cy) / px),
                    )
                },
            )
        }
    }
}

/** Where one node is drawn: its circle's centre on screen, its scale and its opacities. */
private class NodePlacement(val x: Float, val y: Float, val scale: Float, val alpha: Float, val labelAlpha: Float)

/** The name sits this close under its circle (iOS: 2 pt). */
private val LABEL_GAP = 2.dp

/** A node's width: room for a two-line name under the circle. */
private val NODE_WIDTH = (SynapsCloudLayout.PITCH - 8).dp

@Composable
private fun CloudNode(
    contact: Contact,
    metrics: ContactMetrics,
    blocked: Boolean,
    diameter: Dp,
    onClick: () -> Unit,
    place: () -> NodePlacement,
) {
    val ring = when {
        blocked -> CTColor.danger.copy(alpha = 0.55f)
        metrics.unreadCount > 0 -> CTColor.accent
        metrics.recency == ContactMetrics.Recency.FRESH -> CTColor.accent.copy(alpha = 0.90f)
        metrics.recency == ContactMetrics.Recency.RECENT -> CTColor.accent.copy(alpha = 0.45f)
        else -> CTColor.textDim.copy(alpha = 0.50f)
    }
    val halo = !blocked && metrics.showsHalo
    val ringWidth = metrics.ringWidth.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(NODE_WIDTH)
            .graphicsLayer {
                val p = place()
                // Scaled about the top centre, then moved so the circle's centre lands on (x, y).
                transformOrigin = TransformOrigin(0.5f, 0f)
                scaleX = p.scale
                scaleY = p.scale
                translationX = p.x - size.width / 2
                translationY = p.y - diameter.toPx() * p.scale / 2
                alpha = p.alpha
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter)
                // A soft halo for a live contact, outside the circle: drawn before the clip.
                .drawBehind {
                    if (halo) {
                        val w = 3.dp.toPx()
                        drawCircle(CTColor.accent.copy(alpha = 0.25f), radius = size.minDimension / 2 + w, style = Stroke(w))
                    }
                }
                .clip(CircleShape)
                .clickable(onClick = onClick),
        ) {
            CTAvatar(
                userId = contact.userId,
                displayName = contact.displayName,
                image = rememberAvatar(contact.avatar),
                size = diameter,
                strokeWidth = ringWidth,
            )
            Canvas(Modifier.size(diameter)) {
                val w = ringWidth.toPx()
                drawCircle(ring, radius = (size.minDimension - w) / 2, style = Stroke(w))
            }
        }
        Text(
            text = contact.displayName.ifBlank { contact.username },
            style = CTFont.ui(11),
            color = if (blocked) CTColor.textDim else CTColor.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = LABEL_GAP)
                .graphicsLayer { alpha = place().labelAlpha },
        )
    }
}

@Preview(backgroundColor = 0xFF090909, showBackground = true, widthDp = 390, heightDp = 760)
@Composable
private fun SynapsCloudPreview() {
    val names = listOf(
        "Alice", "Bob", "Carol", "Dan", "Eve", "Frank", "Grace", "Heidi", "Ivan", "Judy",
        "Mallory", "Niaj", "Olivia", "Peggy", "Rupert", "Sybil", "Trent", "Victor", "Walter", "Silent Fox",
        "Swift Wolf", "Deprecated Printer", "Recursive Rabbit", "Mike", "Nora", "Oscar", "Pia", "Quinn", "Rosa", "Sam",
    )
    val contacts = names.mapIndexed { i, n -> Contact(userId = "u%02d".format(i), displayName = n) }
    val metrics = contacts.mapIndexed { i, c ->
        c.userId to ContactMetrics(
            frequencyScore = 1f - i / names.size.toFloat(),
            recency = when (i % 3) { 0 -> ContactMetrics.Recency.FRESH; 1 -> ContactMetrics.Recency.RECENT; else -> ContactMetrics.Recency.NONE },
            unreadCount = if (i % 7 == 0) 1 else 0,
        )
    }.toMap()
    SynapsCloud(
        contacts = contacts,
        metrics = metrics,
        blockedIds = setOf("u27"),
        topInset = CTSpace.xs,
        onOpen = {},
    )
}
