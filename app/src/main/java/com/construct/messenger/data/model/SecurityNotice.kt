package com.construct.messenger.data.model

/**
 * What a contact's security event was. Stored as [code]; a code this build does not know reads
 * as [NONE] rather than failing the row.
 */
enum class SecurityNotice(val code: Int) {
    NONE(0),

    /** They named a different account address than the one pinned (card or invite). */
    ADDRESS_CHANGED(1),

    /** Their account has a device its listed set did not name — possibly one the server added. */
    NEW_DEVICE(2),
    ;

    companion object {
        fun of(code: Int): SecurityNotice = entries.firstOrNull { it.code == code } ?: NONE
    }
}
