package com.construct.messenger.domain.usecase

import android.os.Build
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.api.SendFailureReading
import com.construct.messenger.data.api.StreamCursorStore
import com.construct.messenger.data.auth.authenticateDevice
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.google.protobuf.ByteString
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import shared.proto.services.v1.AuthServiceOuterClass.AuthTokensResponse
import shared.proto.services.v1.AuthServiceOuterClass.CheckJoinRequestStatusRequest
import shared.proto.services.v1.AuthServiceOuterClass.ConfirmDeviceLinkRequest
import shared.proto.services.v1.AuthServiceOuterClass.DevicePublicKeys
import shared.proto.services.v1.AuthServiceOuterClass.JoinRequestPayload
import uniffi.construct_core.DeviceJoinEnd
import uniffi.construct_core.DeviceJoinStep
import uniffi.construct_core.DeviceJoinWait
import uniffi.construct_core.DeviceLinkQr
import uniffi.construct_core.RegistrationBundleFields
import uniffi.construct_core.SendFailureKind
import uniffi.construct_core.deviceJoinRequestQr
import uniffi.construct_core.deviceLinkStartCursor

/**
 * Join an existing account as a new device.
 * **Canon:** iOS `DeviceLinkViewModel.confirmLink` / `generateJoinRequestQR` / `finishLink`.
 *
 * Fresh device keys as at registration, and the hybrid identity key with them before any QR
 * (`CryptoManager.prepareLinkIdentity`): the account's device vouches for exactly the keys the QR
 * names (`decisions/devices-of-an-account-sign-each-other.md`).
 * - Flow A ([confirm]): the account device's QR is scanned; `ConfirmDeviceLink` carries the proof
 *   of our keys under its secret and returns this device's tokens.
 * - Flow B ([submitJoinRequest] + [awaitJoin]): our QR names our keys (`fp`); the account's device
 *   approves, and we sign in with the key we asked to join with. When to ask, when to sign in and
 *   how it ends is the core's (construct-rules `device_join`).
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
        val qr = deviceJoinRequestQr(identity.identityPublic, identity.hybridPublic, name, PLATFORM)
        return qr to identity.deviceId
    }

    /**
     * Flow B, from the moment our QR is on the screen: null once signed in, after [onSignedIn];
     * otherwise how it ended. A sign-in is not cut short by the screen going away.
     */
    suspend fun awaitJoin(
        deviceId: String,
        nowMs: () -> Long = System::currentTimeMillis,
        onSignedIn: suspend () -> Unit,
    ): DeviceJoinEnd? = DeviceJoinWait(nowMs()).use { wait ->
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val step = wait.next(nowMs())) {
                is DeviceJoinStep.Wait -> delay(step.ms)
                DeviceJoinStep.Ask -> try {
                    wait.onStatus(joinRequestStatus(deviceId), nowMs())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "join request status: ${e.message}")
                    wait.onStatusFailed(nowMs())
                }
                DeviceJoinStep.SignIn -> {
                    val failure = withContext(NonCancellable) {
                        signInAsJoined(deviceId).also { if (it == null) onSignedIn() }
                    } ?: return null
                    wait.onSignInFailed(failure, nowMs())
                }
                is DeviceJoinStep.Ended -> return step.end
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** Null once this device is signed in; otherwise why not. */
    private suspend fun signInAsJoined(deviceId: String): SendFailureKind? {
        val tokens = try {
            authenticateDevice(cryptoManager, grpcClient, deviceId)
        } catch (e: Exception) {
            Log.i(TAG, "join sign-in: ${e.message}")
            return SendFailureReading.kindOf(e)
        }
        return try {
            finish(tokens, deviceId)
            null
        } catch (e: Exception) {
            // Signed in; what followed did not get through, and is not a refusal of the sign-in.
            Log.e(TAG, "joined, not set up: ${e.message}", e)
            SendFailureKind.NoAnswer
        }
    }

    /** `CheckJoinRequestStatusResponse.Status`, by its number. */
    private suspend fun joinRequestStatus(deviceId: String): Int = grpcClient.deviceLink.checkJoinRequestStatus(
        CheckJoinRequestStatusRequest.newBuilder().setPendingDeviceId(deviceId).build(),
    ).statusValue

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
        // stream at the moment the server let us in (construct-core `session_token`).
        deviceLinkStartCursor(tokens.accessToken)?.let(streamCursor::save)
            ?: Log.e(TAG, "no stream checkpoint from the token — the stream may ask for the whole backlog")
        uploadPreKeysUseCase(deviceId, count = INITIAL_OTPK_COUNT, replaceExisting = true)
    }

    internal companion object {
        const val TAG = "DeviceLink"
        const val PLATFORM = "android"
        const val CLASSIC_CRYPTO_SUITE_LABEL = "Curve25519+Ed25519"
        const val INITIAL_OTPK_COUNT = 100
    }
}
