package com.construct.messenger.data.model

/**
 * What a contact's security event was. Stored as [code]; a code this build does not know reads
 * as [NONE] rather than failing the row.
 *
 * 2 was "a device the contact's account did not have" from 2026-09-29 to 09-30. It could not tell
 * a device the contact linked from one the server added, so every honest link warned every contact.
 * It returns once a new device carries a signature by one we already know
 * (`decisions/new-device-alarm-waits-for-cross-signing.md`). Stored 2 reads as [NONE]; the code is
 * not reused.
 */
enum class SecurityNotice(val code: Int) {
    NONE(0),

    /** They named a different account address than the one pinned (card or invite). */
    ADDRESS_CHANGED(1),
    ;

    companion object {
        fun of(code: Int): SecurityNotice = entries.firstOrNull { it.code == code } ?: NONE
    }
}
