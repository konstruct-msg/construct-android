package com.construct.messenger.domain.usecase

import android.os.Build
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.api.StreamCursorStore
import com.construct.messenger.data.auth.TokenUtils
import com.construct.messenger.data.auth.authenticateDevice
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.devicelink.DeviceLinkQr
import com.construct.messenger.diagnostics.Log
import com.google.protobuf.ByteString
import io.grpc.Status
import javax.inject.Inject
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusRequest
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusResponse
import shared.proto.services.v1.AuthServiceOuterClass.ConfirmDeviceLinkRequest
import shared.proto.services.v1.AuthServiceOuterClass.DevicePublicKeys
import shared.proto.services.v1.AuthServiceOuterClass.JoinRequestPayload
import uniffi.construct_core.RegistrationBundleFields

/** The join request was turned down on the account's device, or ran out before anyone answered. */
class JoinRequestEnded(val rejected: Boolean) : Exception(if (rejected) "rejected" else "expired")

/** Approved, and then refused at sign-in in a way that would repeat: a clock out by minutes, a wrong key. */
class JoinSignInRefused(cause: Throwable) : Exception("joined, sign-in refused: ${cause.message}", cause)

/**
 * Join an existing account as a new device.
 * **Canon:** iOS `DeviceLinkViewModel.confirmLink` / `generateJoinRequestQR` / `finishLink`.
 *
 * Fresh device keys as at registration, and the hybrid identity key with them before any QR
 * (`CryptoManager.prepareLinkIdentity`): the account's device vouches for exactly the keys the QR
 * names (`decisions/devices-of-an-account-sign-each-other.md`).
 * - Flow A ([confirm]): the account device's QR is scanned; `ConfirmDeviceLink` carries the proof
 *   of our keys under its secret and returns this device's tokens.
 * - Flow B ([submitJoinRequest] + [pollJoinRequest]): our QR names our keys (`fp`); the account's
 *   device approves, and we sign in with the key we asked to join with.
 */
class LinkDeviceUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
    private val streamCursor: StreamCursorStore,
    private val uploadPreKeysUseCase: UploadPreKeysUseCase,
) {
    /** Flow A. Returns this device's id once it is an account device. */
    suspend fun confirm(offer: DeviceLinkQr.Offer): String {
        val (bundle, identity) = freshKeys(offer.secret)
        val response = grpcClient.deviceLink.confirmDeviceLink(
            ConfirmDeviceLinkRequest.newBuilder()
                .setLinkToken(offer.token)
                .setDeviceId(identity.deviceId)
                .setPublicKeys(publicKeys(bundle))
                .setLinkKeyProof(ByteString.copyFrom(identity.keyProof ?: ByteArray(0)))
                .build(),
        )
        finish(response.tokens, identity.deviceId)
        return identity.deviceId
    }

    /** Flow B: register the join request and return the QR the account's device scans, with our id. */
    suspend fun submitJoinRequest(): Pair<String, String> {
        val (bundle, identity) = freshKeys(linkSecret = null)
        val name = Build.MODEL.orEmpty().ifEmpty { "Android" }
        grpcClient.deviceLink.submitJoinRequest(
            JoinRequestPayload.newBuilder()
                .setPendingDeviceId(identity.deviceId)
                .setIdentityPublic(ByteString.copyFrom(bundle.identityPublic))
                .setVerifyingKey(ByteString.copyFrom(bundle.verifyingKey))
                .setSignedPrekeyPublic(ByteString.copyFrom(bundle.signedPrekeyPublic))
                .setSignedPrekeySignature(ByteString.copyFrom(bundle.signature))
                .setDeviceName(name)
                .setPlatform(PLATFORM)
                .build(),
        )
        val qr = DeviceLinkQr.joinRequest(identity.deviceId, bundle.identityPublic, identity.fingerprint, name, PLATFORM)
        return qr to identity.deviceId
    }

    /** The device the account let in: the server forgets an approval sooner than the QR expires. */
    private var approved: String? = null

    /** Flow B: true once approved and signed in; false while pending. Throws what a later poll may get past. */
    suspend fun pollJoinRequest(deviceId: String): Boolean {
        if (approved != deviceId && !isApproved(deviceId)) return false
        approved = deviceId
        val tokens = try {
            authenticateDevice(cryptoManager, grpcClient, deviceId)
        } catch (e: Exception) {
            throw if (signInIsRefused(e)) JoinSignInRefused(e) else e
        }
        finish(tokens, deviceId)
        return true
    }

    /** Reads the cause of a `DeviceAuthRefused` too. */
    private fun signInIsRefused(e: Exception): Boolean = Status.fromThrowable(e).code in REFUSALS

    private suspend fun isApproved(deviceId: String): Boolean {
        val r = grpcClient.deviceLink.checkJoinRequestStatus(
            CheckJoinRequestStatusRequest.newBuilder().setPendingDeviceId(deviceId).build(),
        )
        return when (r.status) {
            CheckJoinRequestStatusResponse.Status.STATUS_APPROVED -> true
            CheckJoinRequestStatusResponse.Status.STATUS_REJECTED -> throw JoinRequestEnded(rejected = true)
            CheckJoinRequestStatusResponse.Status.STATUS_EXPIRED -> throw JoinRequestEnded(rejected = false)
            else -> false
        }
    }

    private fun freshKeys(linkSecret: ByteArray?): Pair<RegistrationBundleFields, CryptoManager.LinkIdentity> {
        keystoreManager.deleteKyberPrekeys()
        val bundle = cryptoManager.loadOrCreate()
        val identity = cryptoManager.prepareLinkIdentity(linkSecret)
        // Saved before anything about the keys is shown: see `prepareLinkIdentity`.
        check(keystoreManager.savePrivateKeys(identity.privateKeys)) { "link keys not persisted" }
        return bundle to identity
    }

    /** As at registration; the hybrid key goes up with the first prekey upload, as iOS. */
    private fun publicKeys(bundle: RegistrationBundleFields): DevicePublicKeys = DevicePublicKeys.newBuilder()
        .setVerifyingKey(ByteString.copyFrom(bundle.verifyingKey))
        .setIdentityPublic(ByteString.copyFrom(bundle.identityPublic))
        .setSignedPrekeyPublic(ByteString.copyFrom(bundle.signedPrekeyPublic))
        .setSignedPrekeySignature(ByteString.copyFrom(bundle.signature))
        .setCryptoSuite(CLASSIC_CRYPTO_SUITE_LABEL)
        .build()

    private suspend fun finish(tokens: AuthTokensResponse, deviceId: String) {
        cryptoManager.setLocalUserId(tokens.userId)
        keystoreManager.saveTokens(tokens, deviceId)
        keystoreManager.savePrivateKeys(cryptoManager.exportPrivateKeys())
        // The account's mail from before this device existed is not ours to open: start the
        // stream at the moment the server let us in (iOS `DeviceLinkStreamCursorPolicy`).
        checkpointCursor(tokens.accessToken)?.let(streamCursor::save)
            ?: Log.e(TAG, "no stream checkpoint from the token — the stream may ask for the whole backlog")
        uploadPreKeysUseCase(deviceId, count = INITIAL_OTPK_COUNT, replaceExisting = true)
    }

    internal companion object {
        const val TAG = "DeviceLink"
        const val PLATFORM = "android"
        const val CLASSIC_CRYPTO_SUITE_LABEL = "Curve25519+Ed25519"
        const val INITIAL_OTPK_COUNT = 100
        val REFUSALS = setOf(Status.Code.INVALID_ARGUMENT, Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED)

        /**
         * `"<iat ms>-0"` from the access token's `iat`, or null. PASETO v4.public only — Android
         * reads no JWT (`docs/ANDROID_ONBOARDING.md` §13). **Canon:** iOS `DeviceLinkStreamCursorPolicy.checkpointCursor`.
         */
        fun checkpointCursor(accessToken: String): String? {
            val iat = TokenUtils.extractIssuedAt(accessToken) ?: return null
            if (iat <= 0 || iat > Long.MAX_VALUE / 1000) return null
            return "${iat * 1000}-0"
        }
    }
}
