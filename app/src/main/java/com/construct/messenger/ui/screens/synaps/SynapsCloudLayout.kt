package com.construct.messenger.ui.screens.synaps

import com.construct.messenger.data.model.ChatActivity
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Lays the Synapses cloud out as a hexagonal spiral from the centre, as the Apple Watch home screen
 * does: one contact in the middle, then rings of 6, 12, 18, … The most active contact
 * ([ContactMetrics.frequencyScore]) takes the centre and activity falls off ring by ring, so the
 * people someone talks to are where the eye lands.
 *
 * **Canon:** iOS `SynapsLayoutEngine.swift` → `SynapsCloudLayout` (vault TODO 133). The numbers are
 * the same so the cloud looks the same on both platforms. Positions are in dp around the cloud's
 * centre at zoom 1 — the screen size plays no part, so the same contacts keep the same places.
 */
object SynapsCloudLayout {
    /** Centre-to-centre distance of two neighbours in a row, in dp at zoom 1. */
    const val PITCH = 96f

    /**
     * Rows sit a little further apart than pure hex packing (√3/2): the names near the centre are
     * drawn under their circles and must clear the ring below.
     */
    const val ROW_STRETCH = 1.15f

    /** A cell's offset from the cloud's centre, in dp at zoom 1. */
    data class Point(val x: Float, val y: Float)

    /** Axial directions of a pointy-top grid, in walking order around a ring. */
    private val DIRECTIONS = listOf(1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1)

    /**
     * Most active first; ties keep a stable order by id, so a contact does not wander between
     * launches while nothing about them changed, whatever order the contacts came in.
     */
    fun order(ids: List<String>, metrics: Map<String, ContactMetrics>): List<String> =
        ids.sortedWith(
            compareByDescending<String> { metrics[it]?.frequencyScore ?: 0f }.thenBy { it },
        )

    /** The first [count] cells of a hexagonal spiral, centre first, ring by ring. */
    fun spiral(count: Int): List<Point> {
        if (count <= 0) return emptyList()
        val cells = mutableListOf(0 to 0)
        var ring = 1
        while (cells.size < count) {
            // A ring starts `ring` steps out along direction 4 and walks its six sides.
            var q = DIRECTIONS[4].first * ring
            var r = DIRECTIONS[4].second * ring
            for (side in 0 until 6) {
                repeat(ring) {
                    cells += q to r
                    q += DIRECTIONS[side].first
                    r += DIRECTIONS[side].second
                }
            }
            ring++
        }
        return cells.take(count).map { (q, r) -> point(q, r) }
    }

    fun point(q: Int, r: Int): Point = Point(
        x = PITCH * (q + r / 2f),
        y = PITCH * (sqrt(3f) / 2f) * ROW_STRETCH * r,
    )

    /** A circle's diameter in dp at zoom 1: more active, a little larger. */
    fun diameter(frequencyScore: Float): Float = PITCH * (0.50f + 0.16f * frequencyScore)

    /** Half the cloud's extent on each axis, one cell's margin included — how far a pan may go. */
    fun halfExtent(points: List<Point>): Point = Point(
        x = (points.maxOfOrNull { kotlin.math.abs(it.x) } ?: 0f) + PITCH / 2,
        y = (points.maxOfOrNull { kotlin.math.abs(it.y) } ?: 0f) + PITCH / 2,
    )
}

/**
 * The Apple Watch lens over the cloud: a contact keeps its size near the middle of the visible
 * area and shrinks towards the edge, pulled in as it goes so the cloud reads as one round shape
 * rather than as the grid it is laid out on. It works on where a contact is on screen, so panning
 * moves contacts through the lens.
 *
 * The lens is an oval filling the visible area: a phone's visible area is twice as tall as it is
 * wide, and a circle as wide as the screen left the top and bottom thirds empty. Distances are
 * measured in units of the radius along each axis, so "how far out" is one number, `rim`: 0 in the
 * middle, 1 at the oval's edge.
 *
 * **Canon:** iOS `SynapsLayoutEngine.swift` → `SynapsLens`.
 */
class SynapsLens(
    /** Half the oval's width, in screen units. */
    private val radiusX: Float,
    /** Half the oval's height, in screen units. */
    private val radiusY: Float,
) {
    /** Where a point at ([x], [y]) is drawn around the lens centre ([cx], [cy]), and how far out (0…1). */
    data class Drawn(val x: Float, val y: Float, val rim: Float)

    fun draw(x: Float, y: Float, cx: Float, cy: Float): Drawn {
        if (radiusX <= 0f || radiusY <= 0f) return Drawn(x, y, 0f)
        val ux = (x - cx) / radiusX
        val uy = (y - cy) / radiusY
        val rim = hypot(ux, uy)
        if (rim <= 0f) return Drawn(x, y, 0f)
        val drawn = drawnRim(rim)
        val k = drawn / rim
        return Drawn(cx + ux * k * radiusX, cy + uy * k * radiusY, drawn)
    }

    companion object {
        /** The size of a contact at the rim, relative to one in the middle. */
        const val RIM_SCALE = 0.35f

        /** The opacity of a contact at the rim: the outermost ring crowds against the edge. */
        const val RIM_OPACITY = 0.35f

        /** The oval's inset from the visible area's edges, in dp. */
        const val INSET = 14f

        /** How far out a contact `rim` units away is drawn: unchanged near the middle, never past the edge. */
        fun drawnRim(rim: Float): Float = tanh(rim)

        /** Size at a drawn rim distance: 1 in the inner third, [RIM_SCALE] at the edge. */
        fun scale(rim: Float): Float = 1f - (1f - RIM_SCALE) * smoothstep(0.3f, 1f, rim)

        /** The circle fades only in the outermost band. */
        fun opacity(rim: Float): Float = 1f - (1f - RIM_OPACITY) * smoothstep(0.82f, 0.98f, rim)

        /**
         * Only the middle contact and the ring around it are named. [distance] is in dp on screen
         * from the lens centre, not in rim units: the oval is narrow across, and by rim the first
         * ring's left and right neighbours lost their names while the second ring's upper and lower
         * ones kept theirs half-drawn over the circles below.
         */
        fun labelOpacity(distance: Float): Float {
            val pitch = SynapsCloudLayout.PITCH
            return 1f - smoothstep(pitch * 1.15f, pitch * 1.4f, distance)
        }

        fun smoothstep(lo: Float, hi: Float, x: Float): Float {
            val t = min(max((x - lo) / (hi - lo), 0f), 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/**
 * Locally derived activity signals for one contact — no server data, no social graph. They place
 * and size the contact in the cloud and colour its ring.
 *
 * **Canon:** iOS `SynapsLayoutEngine.swift` → `ContactMetrics`.
 */
data class ContactMetrics(
    /** Message count normalised across all contacts: 0 = none or fewest, 1 = most active. */
    val frequencyScore: Float = 0f,
    val recency: Recency = Recency.NONE,
    /** Unread messages over this contact's chats. */
    val unreadCount: Int = 0,
) {
    enum class Recency {
        /** Last message under 24 h ago. */
        FRESH,

        /** Last message under 7 days ago. */
        RECENT,
        NONE,
    }

    /** A soft halo for "live" contacts: unread, or fresh. */
    val showsHalo: Boolean get() = unreadCount > 0 || recency == Recency.FRESH

    /** The ring's width in dp — unread wins over recency. */
    val ringWidth: Float get() = if (unreadCount > 0) 2.25f else if (recency == Recency.FRESH) 2f else 1.5f

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = 7 * DAY_MS

        /**
         * Each contact's metrics from their chats. A contact with several chats counts the largest
         * one, sums the unread and takes the latest message — as iOS `ContactMetrics.byContact`.
         */
        fun byContact(ids: List<String>, chats: List<ChatActivity>, now: Long): Map<String, ContactMetrics> {
            if (ids.isEmpty()) return emptyMap()
            val byPeer = chats.groupBy { it.contactId }
            val counts = ids.associateWith { id -> byPeer[id]?.maxOfOrNull { it.messages } ?: 0 }
            val maxCount = counts.values.maxOrNull() ?: 0
            return ids.associateWith { id ->
                val own = byPeer[id].orEmpty()
                val last = own.mapNotNull { it.lastMessageTime }.maxOrNull()
                val recency = when {
                    last == null -> Recency.NONE
                    now - last < DAY_MS -> Recency.FRESH
                    now - last < WEEK_MS -> Recency.RECENT
                    else -> Recency.NONE
                }
                ContactMetrics(
                    frequencyScore = if (maxCount > 0) counts.getValue(id).toFloat() / maxCount else 0f,
                    recency = recency,
                    unreadCount = own.sumOf { it.unreadCount },
                )
            }
        }
    }
}
