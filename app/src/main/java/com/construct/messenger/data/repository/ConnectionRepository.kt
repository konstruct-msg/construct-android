package com.construct.messenger.data.repository

import com.construct.messenger.service.MessagingRuntime
import com.construct.messenger.ui.components.ConnectionStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/** Whether the message stream is up — for status rows, not for delivery decisions. */
interface ConnectionRepository {
    val status: StateFlow<ConnectionStatus>

    /** When the server last answered a heartbeat (epoch ms); null before the first. */
    val lastHeartbeatAt: StateFlow<Long?>
}

@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    runtime: MessagingRuntime,
) : ConnectionRepository {
    override val status: StateFlow<ConnectionStatus> = runtime.connectionStatus
    override val lastHeartbeatAt: StateFlow<Long?> = runtime.lastHeartbeatAt
}
