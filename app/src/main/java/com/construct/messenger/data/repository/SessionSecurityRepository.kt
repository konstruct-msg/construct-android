package com.construct.messenger.data.repository

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.PeerDeviceRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.PqHandshake

/**
 * What protects the conversation with a contact, for the profile's security section.
 *
 * [suiteId] is the weakest suite across their devices that have a session — the one a reader
 * should assume. [pqDegraded]: some device's session began before PQXDH v2, so its first key
 * has no ML-KEM (or only the deferred contribution) until the upgrade sweep replaces it.
 * [identityPublic]: the key pinned for their first known device — what the fingerprint is of.
 */
data class SessionSecurity(
    val identityPublic: ByteArray?,
    val hasSession: Boolean,
    val suiteId: Int,
    val pqDegraded: Boolean,
)

/**
 * **Canon:** iOS `UserProfileView.refreshSessionSecurityState` over
 * `CryptoManager.sessionSuiteIdAcrossDevices` and `getSessionHealth`. Everything handed to the
 * core is a device id, never the account id.
 */
@Singleton
class SessionSecurityRepository @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val registry: PeerDeviceRegistry,
) {
    suspend fun of(accountId: String): SessionSecurity = withContext(Dispatchers.Default) {
        if (registry.knownDevices(accountId).isEmpty()) registry.resolveDeviceId(accountId)
        val known = registry.knownDevices(accountId)
        val devices = known.map { it.deviceId }
        val withSession = devices.filter(cryptoManager::hasSession)
        val suite = withSession.map(cryptoManager::sessionSuiteId).filter { it > 0 }.minOrNull() ?: 0
        val degraded = withSession.any { device ->
            cryptoManager.sessionHealth(device)?.let { it.pqHandshake != PqHandshake.INITIAL_V2 } ?: false
        }
        SessionSecurity(
            identityPublic = known.firstOrNull()?.identityPublic,
            hasSession = withSession.isNotEmpty(),
            suiteId = suite,
            pqDegraded = degraded,
        )
    }
}
