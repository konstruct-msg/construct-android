package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.local.PendingInviteStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Hands a scanned invite to the path a tapped `konstruct://add` link takes: [PendingInviteStore],
 * redeemed by Synaps. One redemption path, whichever way the invite arrived.
 */
@HiltViewModel
class QrScannerViewModel @Inject constructor(
    private val pendingInvites: PendingInviteStore,
) : ViewModel() {
    fun deliver(link: String) = pendingInvites.offer(link)
}
