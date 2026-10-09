package com.construct.messenger.ui.theme

/**
 * Decorative terminal chrome only.
 *
 * Per the design-system revision (2026-06-22), action/status glyphs and
 * functional ASCII brackets have been removed. Use Material Icons
 * (`androidx.compose.material.icons`) for all interactive controls and
 * `CTStatusBadge` for status. ASCII lives here only as unobtrusive chrome:
 * the 8-point star and the `>` system-message prefix. A separator is Material's `HorizontalDivider`.
 */
object CTSymbol {
    /** Decorative 8-point star. */
    const val star8 = "✷"
}