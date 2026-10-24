package com.construct.messenger.security

import com.construct.messenger.service.SessionManager.PeerBundle
import uniffi.construct_core.BinaryKeyBundle
import uniffi.construct_core.DeviceLinkEvidence
import uniffi.construct_core.OrchestratorCore
import uniffi.construct_core.createCryptoCore
import uniffi.construct_core.createOrchestratorCoreFromKeys
import uniffi.construct_core.deriveDeviceId
import uniffi.construct_core.historyQrFingerprint

/** A device with real keys from the core, and its bundle as the directory would serve it. */
internal class CoreTestDevice private constructor(val core: OrchestratorCore, val id: String, val bundle: BinaryKeyBundle) {

    fun served(certificate: ByteArray? = null, accountId: String = ACCOUNT) =
        PeerBundle(accountId = accountId, deviceId = id, identityPublic = bundle.identityPublic, bundle = bundle, deviceCertificate = certificate)

    /** `history_qr_fingerprint` of this device's keys — the `fp` its flow B QR carries. */
    val fingerprint: ByteArray get() = historyQrFingerprint(bundle.identityPublic, bundle.hybridIdentityKey!!)

    /** This device vouches for [subject] in flow B: the fingerprint from the QR names the directory's keys. */
    fun vouch(subject: CoreTestDevice): ByteArray =
        core.issueDeviceCertificate(PeerDeviceVetting.subjectKeys(subject.served())!!, DeviceLinkEvidence.QrFingerprint(subject.fingerprint))

    companion object {
        const val ACCOUNT = "e7a4e3d2-0000-4000-8000-000000000001"

        fun create(): CoreTestDevice {
            val keys = createCryptoCore()
            val fields = keys.getRegistrationBundleFields()
            val id = deriveDeviceId(fields.identityPublic)
            val core = createOrchestratorCoreFromKeys(keys.exportPrivateKeys(), id)
            val hybrid = core.ensureHybridSignatureKey()
            val binding = core.signWithDeviceKey(core.buildHybridIdentityBindMessage(hybrid))
            val bundle = BinaryKeyBundle(
                identityPublic = fields.identityPublic,
                signedPrekeyPublic = fields.signedPrekeyPublic,
                signature = fields.signature,
                verifyingKey = fields.verifyingKey,
                suiteId = fields.suiteId,
                oneTimePrekeyPublic = null,
                oneTimePrekeyId = null,
                spkUploadedAt = 0uL,
                spkRotationEpoch = 0u,
                kyberSpkUploadedAt = 0uL,
                kyberSpkRotationEpoch = 0u,
                kyberPreKeyPublic = null,
                kyberOneTimePrekeyPublic = null,
                kyberOneTimePrekeyId = null,
                hybridIdentityKey = hybrid,
                hybridIdentitySignature = binding,
            )
            return CoreTestDevice(core, id, bundle)
        }
    }
}
