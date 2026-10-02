package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.local.PendingInviteStore
import com.construct.messenger.veil.VeilConfigImporter
import com.construct.messenger.veil.VeilConfigLink
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Hands a scanned invite to the path a tapped `konstruct://add` link takes: [PendingInviteStore],
 * redeemed by Synaps. One redemption path, whichever way the invite arrived.
 *
 * A VEIL configuration code is redeemed here instead — "scan this in Konstruct" sends people to
 * the camera they know. **Canon:** iOS `VeilVoucherRedemption.messageIfVoucher`.
 */
@HiltViewModel
class QrScannerViewModel @Inject constructor(
    private val pendingInvites: PendingInviteStore,
    private val veilConfig: VeilConfigImporter,
) : ViewModel() {
    /** Null for an invite; for a VEIL configuration, the message saying how it went. */
    fun deliver(link: String): Int? {
        if (VeilConfigLink.isLink(link)) return VeilConfigImporter.messageOf(veilConfig.redeem(link))
        pendingInvites.offer(link)
        return null
    }
}
