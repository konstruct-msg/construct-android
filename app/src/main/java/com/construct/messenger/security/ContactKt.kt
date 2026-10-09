package com.construct.messenger.security

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.stealth.ServerKeysProvider
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundleResponse
import uniffi.construct_core.KtVerdict

/**
 * Judges the KT proofs a fetched bundle carries and records the verdict on the contact. Not
 * blocking: a failed proof is shown to the user and does not refuse the bundle — the checks a
 * session depends on are the core's, at init. **Canon:** iOS `KeyServiceClient.getPreKeyBundle`
 * and `updateContactKTStatus`.
 */
@Singleton
class ContactKt @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val serverKeys: ServerKeysProvider,
    private val contacts: ContactStore,
    private val log: KtLog,
) {
    suspend fun judge(accountId: String, response: GetPreKeyBundleResponse) {
        if (!response.hasKtProof()) return
        val bundle = response.bundle
        val hybrid = response.hasHybridKtProof() && bundle.hasHybridIdentityKey() && !bundle.hybridIdentityKey.isEmpty
        val verdicts = runCatching {
            cryptoManager.verifyKtProofs(
                deviceId = response.deviceId,
                identityKey = bundle.identityKey.toByteArray(),
                identityProof = KtCheck.proofOf(response.ktProof),
                hybridIdentityKey = if (hybrid) bundle.hybridIdentityKey.toByteArray() else null,
                hybridProof = if (hybrid) KtCheck.proofOf(response.hybridKtProof) else null,
                treeHead = if (response.hasTreeHead()) KtCheck.headOf(response.treeHead) else null,
                trust = serverKeys.trust(),
            )
        }.getOrElse {
            Log.w(TAG, "KT: the core refused to judge ${response.deviceId.take(8)}…", it)
            null
        }
        val status = KtCheck.statusOf(verdicts?.identity)
        when (verdicts?.identity) {
            KtVerdict.VERIFIED -> log.recordVerified()
            KtVerdict.UNAVAILABLE, null -> Unit
            else -> {
                log.recordFailure()
                Log.e(TAG, "KT: proof FAILED for device ${response.deviceId.take(8)}… — ${verdicts.identity}")
            }
        }
        // The hybrid key's proof is defence in depth and logged only, as on iOS.
        verdicts?.hybrid?.takeIf { it != KtVerdict.VERIFIED && it != KtVerdict.UNAVAILABLE }
            ?.let { Log.e(TAG, "KT(hybrid): proof FAILED for device ${response.deviceId.take(8)}… — $it") }
        status?.let { contacts.setKtStatus(accountId, it.code) }
    }

    private companion object {
        const val TAG = "KT"
    }
}
