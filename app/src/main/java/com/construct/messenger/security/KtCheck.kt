package com.construct.messenger.security

import com.construct.messenger.data.model.KtStatus
import shared.proto.services.v1.KeyServiceOuterClass
import uniffi.construct_core.KtInclusionProof
import uniffi.construct_core.KtSignedTreeHead
import uniffi.construct_core.KtVerdict

/**
 * A bundle's Key Transparency proofs as the core takes them, and what its verdict means for the
 * contact. The judging is the core's (`verify_kt_proofs`); there is no Merkle code here.
 * **Canon:** iOS `KeyServiceClient.getPreKeyBundle` (since construct-messenger PR #60).
 */
object KtCheck {

    /**
     * The status to record, or null to leave the stored one: [KtVerdict.UNAVAILABLE] says the
     * server sent nothing to judge, which is not a verdict on the contact.
     */
    fun statusOf(identity: KtVerdict?): KtStatus? = when (identity) {
        KtVerdict.VERIFIED -> KtStatus.VERIFIED
        KtVerdict.UNAVAILABLE, null -> null
        KtVerdict.MALFORMED_PROOF, KtVerdict.INCLUSION_PROOF_INVALID, KtVerdict.SIGNATURE_INVALID -> KtStatus.FAILED
    }

    fun proofOf(p: KeyServiceOuterClass.KtInclusionProof) = KtInclusionProof(
        leafIndex = p.leafIndex.toULong(),
        treeSize = p.treeSize.toULong(),
        rootHash = p.rootHash.toByteArray(),
        proofHashes = p.proofHashesList.map { it.toByteArray() },
        treeHeadSignature = p.treeHeadSignature.toByteArray(),
    )

    fun headOf(h: KeyServiceOuterClass.SignedTreeHead) = KtSignedTreeHead(
        treeSize = h.treeSize.toULong(),
        rootHash = h.rootHash.toByteArray(),
        kid = h.kid.toByteArray(),
        signature = h.signature.toByteArray(),
    )
}
