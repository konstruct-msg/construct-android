package com.construct.messenger.data.auth

import com.construct.messenger.data.local.KeystoreManager
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * gRPC [ClientInterceptor] that injects auth metadata into every authenticated RPC.
 *
 * **Canon:** `docs/TOKEN_AUTH.md` §3.2; mirrors iOS `AuthInterceptor.swift`.
 *
 * ## What it does
 *
 * For **authenticated** methods, adds three headers:
 * ```
 * Authorization: Bearer <access_token>
 * x-user-id:     <userId>        // from Keystore
 * x-device-id:   <deviceId>      // from Keystore
 * ```
 *
 * For **unauthenticated** methods, passes through without modification. The full list
 * of unauthenticated methods:
 * - `GetPowChallenge`, `RegisterDevice`, `AuthenticateDevice` — onboarding/auth
 * - `RefreshToken` — token refresh (no valid token to send)
 * - `CheckUsernameAvailability` — registration UX
 * - `SendSealedMessage` — stealth/sealed sender (per Stealth v2 decision doc, must NOT
 *   carry sender identity on the transport to preserve anonymity)
 *
 * ## Thread-safety
 *
 * [KeystoreManager] reads are thread-safe (backed by `SharedPreferences`). The interceptor
 * does not cache or coordinate writes — that's the [TokenRefreshCoordinator]'s job.
 *
 * ## Design decisions
 *
 * - **No token refresh in the interceptor.** The interceptor is a stateless header injector.
 *   If the server returns `UNAUTHENTICATED`, the caller handles it (typically by calling
 *   [TokenRefreshCoordinator.refreshIfPossible] and retrying, or falling through to device
 *   re-auth on permanent failure).
 * - **Missing values are silently skipped.** If a token, userId, or deviceId is not yet
 *   persisted (pre-registration), the interceptor simply omits the corresponding header
 *   rather than failing. This simplifies onboarding flows that call unauthenticated RPCs.
 * - **Method matching** uses [MethodDescriptor.getFullMethodName] — the gRPC canonical
 *   form `package.Service/MethodName`. We check the method name portion after the last `/`.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val keystoreManager: KeystoreManager,
) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        // gRPC full method name is "package.Service/MethodName"
        val methodName = method.fullMethodName.substringAfterLast('/')

        // Pass through for unauthenticated methods — no headers added.
        if (methodName in UNAUTHENTICATED_METHODS) {
            return next.newCall(method, callOptions)
        }

        // Wrap the call to inject auth headers before the RPC is sent.
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions),
        ) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                keystoreManager.getAccessToken()?.let { token ->
                    headers.put(KEY_AUTHORIZATION, "Bearer $token")
                }
                keystoreManager.getUserId()?.let { userId ->
                    headers.put(KEY_USER_ID, userId)
                }
                keystoreManager.getDeviceId()?.let { deviceId ->
                    headers.put(KEY_DEVICE_ID, deviceId)
                }
                super.start(responseListener, headers)
            }
        }
    }

    private companion object {
        /** Methods that must NOT carry auth headers. */
        val UNAUTHENTICATED_METHODS: Set<String> = setOf(
            "GetPowChallenge",
            "RegisterDevice",
            "AuthenticateDevice",
            "RefreshToken",
            "CheckUsernameAvailability",
            "SendSealedMessage",
        )

        val KEY_AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
        val KEY_USER_ID: Metadata.Key<String> =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER)
        val KEY_DEVICE_ID: Metadata.Key<String> =
            Metadata.Key.of("x-device-id", Metadata.ASCII_STRING_MARSHALLER)
    }
}
