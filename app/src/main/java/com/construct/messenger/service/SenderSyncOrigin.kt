package com.construct.messenger.service

/**
 * Which session a SENDER_SYNC is read on, from what its certificate names (todo/273).
 *
 * **Canon:** iOS `SenderSyncOrigin` in `DeviceCopyWireId.swift`.
 */
internal sealed interface SenderSyncOrigin {
    /** One of our other devices, as far as this device can tell. */
    data class Device(val id: String) : SenderSyncOrigin

    /** No certificate, so nothing says which session it belongs to. */
    data object Unnamed : SenderSyncOrigin

    /**
     * Another account, a device recorded under another account, or this device. A server signs
     * certificates and nothing checks one on an existing session, so it could hand a contact's
     * message back as a copy. Read on the contact's session it opens, and its frames would be
     * applied as ours.
     */
    data object NotOurs : SenderSyncOrigin

    companion object {
        /**
         * A device this phone has no record of passes: a new sibling is one, and the session it
         * would be read on either is not there or was opened from a certificate the core checked.
         */
        inline fun of(
            certifiedAccount: String?,
            device: String?,
            myUserId: String?,
            myDeviceId: String?,
            accountOfDevice: (String) -> String?,
        ): SenderSyncOrigin {
            if (certifiedAccount == null || device.isNullOrEmpty()) return Unnamed
            if (!certifiedAccount.equals(myUserId, ignoreCase = true)) return NotOurs
            if (device.equals(myDeviceId, ignoreCase = true)) return NotOurs
            val recorded = accountOfDevice(device)
            if (recorded != null && !recorded.equals(myUserId, ignoreCase = true)) return NotOurs
            return Device(device)
        }
    }
}
