package com.construct.messenger.calls

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Telecom's way in: it binds this when a call of ours is added or placed and asks for the
 * connection. Everything is [CallTelecom]'s; this only hands the requests over. Exported, as
 * Telecom requires, and guarded by `BIND_TELECOM_CONNECTION_SERVICE`, which only the system holds.
 */
@AndroidEntryPoint
class CallConnectionService : ConnectionService() {

    @Inject lateinit var telecom: CallTelecom

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection? =
        telecom.incomingConnection(request.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString(CallTelecom.EXTRA_CALL_ID)
            ?: request.extras?.getString(CallTelecom.EXTRA_CALL_ID))
            ?: Connection.createFailedConnection(android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR))

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection? =
        telecom.outgoingConnection(request.extras?.getString(CallTelecom.EXTRA_CALL_ID))
            ?: Connection.createFailedConnection(android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR))

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        telecom.connectionFailed(request.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString(CallTelecom.EXTRA_CALL_ID)
            ?: request.extras?.getString(CallTelecom.EXTRA_CALL_ID), incoming = true)
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        telecom.connectionFailed(request.extras?.getString(CallTelecom.EXTRA_CALL_ID), incoming = false)
    }
}
