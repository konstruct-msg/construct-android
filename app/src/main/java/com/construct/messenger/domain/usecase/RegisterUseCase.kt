package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.DevicePublicKeys
import shared.proto.services.v1.AuthServiceOuterClass.GetPowChallengeRequest
import shared.proto.services.v1.AuthServiceOuterClass.RegisterDeviceRequest
import shared.proto.services.v1.UserServiceOuterClass.SetDiscoverableRequest
import shared.proto.services.v1.AuthServiceOuterClass.PowSolution as PowSolutionProto
import javax.inject.Inject

/** [AuthTokensResponse] plus the [deviceId] used to obtain it — the device id is derived
 * from the freshly generated identity key, not chosen by the caller, so [RegisterUseCase]
 * is the only place that knows it until it's returned here. */
data class RegistrationResult(val tokens: AuthTokensResponse, val deviceId: String)

/**
 * Device registration flow.
 *
 * **Canon:** `docs/IMPLEMENTATION_PLAN.md` → Phase 3.1 "Registration Flow"; stage names
 * mirror iOS `RegistrationFlowView.RegistrationStep`.
 * 1. [CryptoManager.loadOrCreate] generates a fresh identity/SPK bundle; `deviceId` is
 *    derived from the identity public key ([CryptoManager.deriveDeviceId]), matching iOS
 *    `CryptoManager.generateRegistrationBundle()` — never an arbitrary caller-supplied id.
 * 2. Solve the server's PoW challenge (anti-spam).
 * 3. `RegisterDevice` with the bundle + PoW solution -> auth tokens.
 * 4. Upload an initial batch of one-time pre-keys (`KeyService.UploadPreKeys`) so other
 *    devices can X3DH a session with this one. Runs *after* [RegistrationStep.Complete] is
 *    reported — matching iOS `RegistrationFlowView`, where the UI already shows the
 *    "complete" screen while this upload finishes in the background — and failure here is
 *    non-fatal: registration has already succeeded, the device just won't be reachable
 *    until the next replenishment.
 *
 * Crypto suite label is the literal `"Curve25519+Ed25519"` string, matching iOS
 * `AuthServiceClient.registerDevice` — `DevicePublicKeys.crypto_suite` is a free-form
 * display string, independent of the `CryptoSuite` enum used elsewhere.
 */
class RegisterUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
    private val uploadPreKeysUseCase: UploadPreKeysUseCase,
) {
    suspend operator fun invoke(
        username: String?,
        onStep: (RegistrationStep) -> Unit = {},
    ): RegistrationResult {
        onStep(RegistrationStep.GeneratingKeys)
        // A new identity starts with no Kyber prekeys; the old identity's must not be imported
        // into it. The publish record is keyed by device id, so it starts over by itself.
        keystoreManager.deleteKyberPrekeys()
        val bundle = cryptoManager.loadOrCreate()
        val deviceId = cryptoManager.deriveDeviceId(bundle)

        onStep(RegistrationStep.FetchingChallenge)
        val challenge = grpcClient.auth.getPowChallenge(GetPowChallengeRequest.getDefaultInstance())

        onStep(RegistrationStep.ComputingPow(0f))
        val solution = cryptoManager.computePow(challenge.challenge, challenge.difficulty) { progress ->
            onStep(RegistrationStep.ComputingPow(progress))
        }

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

        onStep(RegistrationStep.SubmittingRegistration)
        val response = grpcClient.auth.registerDevice(requestBuilder.build())
        cryptoManager.setLocalUserId(response.tokens.userId)
        keystoreManager.saveTokens(response.tokens, deviceId)
        keystoreManager.savePrivateKeys(cryptoManager.exportPrivateKeys())

        onStep(RegistrationStep.Complete(deviceId))
        uploadInitialOneTimePrekeys(deviceId)
        if (!username.isNullOrEmpty()) {
            runCatching {
                grpcClient.user.setDiscoverable(
                    SetDiscoverableRequest.newBuilder().setDiscoverable(true).build(),
                )
            }
        }

        return RegistrationResult(response.tokens, deviceId)
    }

    /** Non-fatal: delegates to [UploadPreKeysUseCase] — a failed upload is logged and
     * swallowed, not surfaced as a registration error. */
    private suspend fun uploadInitialOneTimePrekeys(deviceId: String) {
        uploadPreKeysUseCase(deviceId, count = INITIAL_OTPK_COUNT, replaceExisting = true)
    }

    private companion object {
        const val CLASSIC_CRYPTO_SUITE_LABEL = "Curve25519+Ed25519"
        const val INITIAL_OTPK_COUNT = 100
    }
}

private fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)
