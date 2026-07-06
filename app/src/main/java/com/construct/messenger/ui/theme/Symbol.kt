package com.construct.messenger.ui.theme

/**
 * Decorative terminal chrome only.
 *
 * Per the design-system revision (2026-06-22), action/status glyphs and
 * functional ASCII brackets have been removed. Use Material Icons
 * (`androidx.compose.material.icons`) for all interactive controls and
 * `CTStatusBadge` for status. ASCII lives here only as unobtrusive chrome:
 * the 8-point star, dashed separators, and the `>` system-message prefix.
 */
object CTSymbol {
    /** Decorative 8-point star. */
    const val star8 = "✷"

    // Separators (CTSep) — ASCII dashed/double lines.
    fun thin(count: Int = 25) = "- ".repeat(count)
    fun thick(count: Int = 25) = "= ".repeat(count)
}