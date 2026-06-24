package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.DevicePublicKeys
import shared.proto.services.v1.AuthServiceOuterClass.GetPowChallengeRequest
import shared.proto.services.v1.AuthServiceOuterClass.RegisterDeviceRequest
import shared.proto.services.v1.AuthServiceOuterClass.PowSolution as PowSolutionProto
import javax.inject.Inject

/**
 * Device registration flow.
 *
 * **Canon:** `docs/IMPLEMENTATION_PLAN.md` → Phase 3.1 "Registration Flow".
 * 1. [CryptoManager.loadOrCreate] generates a fresh identity/SPK bundle.
 * 2. Solve the server's PoW challenge (anti-spam).
 * 3. `RegisterDevice` with the bundle + PoW solution -> auth tokens.
 *
 * Crypto suite label is the literal `"Curve25519+Ed25519"` string, matching iOS
 * `AuthServiceClient.registerDevice` — `DevicePublicKeys.crypto_suite` is a free-form
 * display string, independent of the `CryptoSuite` enum used elsewhere.
 */
class RegisterUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
) {
    suspend operator fun invoke(username: String?, deviceId: String): AuthTokensResponse {
        val bundle = cryptoManager.loadOrCreate()

        val challenge = grpcClient.auth.getPowChallenge(GetPowChallengeRequest.getDefaultInstance())
        val solution = cryptoManager.computePow(challenge.challenge, challenge.difficulty)

        val publicKeys = DevicePublicKeys.newBuilder()
            .setVerifyingKey(bundle.verifyingKey.toByteString())
            .setIdentityPublic(bundle.identityPublic.toByteString())
            .setSignedPrekeyPublic(bundle.signedPrekeyPublic.toByteString())
            .setSignedPrekeySignature(bundle.signature.toByteString())
            .setCryptoSuite(CLASSIC_CRYPTO_SUITE_LABEL)
            .build()

        val powSolution = PowSolutionProto.newBuilder()
            .setChallenge(challenge.challenge)
            .setNonce(solution.nonce.toLong())
            .setHash(solution.hash)
            .build()

        val requestBuilder = RegisterDeviceRequest.newBuilder()
            .setDeviceId(deviceId)
            .setPublicKeys(publicKeys)
            .setPowSolution(powSolution)
        if (!username.isNullOrEmpty()) {
            requestBuilder.username = username
        }

        val response = grpcClient.auth.registerDevice(requestBuilder.build())
        cryptoManager.setLocalUserId(response.tokens.userId)
        keystoreManager.saveTokens(response.tokens, deviceId)
        return response.tokens
    }

    private companion object {
        const val CLASSIC_CRYPTO_SUITE_LABEL = "Curve25519+Ed25519"
    }
}

private fun List<UByte>.toByteString(): ByteString = ByteString.copyFrom(ByteArray(size) { this[it].toByte() })
