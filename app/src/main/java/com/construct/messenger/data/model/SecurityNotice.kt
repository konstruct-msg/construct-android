package com.construct.messenger.data.model

/**
 * What a contact's security event was. Stored as [code]; a code this build does not know reads
 * as [NONE] rather than failing the row.
 *
 * 2 was "a device the contact's account did not have" from 2026-09-29 to 09-30. It could not tell
 * a device the contact linked from one the server added, so every honest link warned every contact.
 * Stored 2 reads as [NONE]; the code is not reused. Its successor is [UNVOUCHED_DEVICE], which only
 * fires for a device none of the account's trusted devices signed
 * (`decisions/devices-of-an-account-sign-each-other.md`).
 */
enum class SecurityNotice(val code: Int) {
    NONE(0),

    /** They named a different account address than the one pinned (card or invite). */
    ADDRESS_CHANGED(1),

    /**
     * Their account has a device none of its devices we trust vouched for — the server may have
     * added it. Only the alarm: the device still receives copies (owner, 2026-10-23). The same 3
     * as iOS `SecurityNotice.unvouchedDevice`.
     */
    UNVOUCHED_DEVICE(3),
    ;

    companion object {
        fun of(code: Int): SecurityNotice = entries.firstOrNull { it.code == code } ?: NONE
    }
}
