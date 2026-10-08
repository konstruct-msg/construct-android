package com.construct.messenger.data.auth

import io.grpc.Metadata
import io.grpc.Status
import shared.proto.services.v1.AuthServiceOuterClass.DeviceRefusal

/**
 * Why the server refused this device, from the trailing metadata key `construct-device-refusal` of
 * an UNAUTHENTICATED status (construct-protos `DeviceRefusal`).
 *
 * **Canon:** iOS `DeviceRefusalReading`. Read as a number because a client erases itself on
 * [DeviceRefusal.DEVICE_REFUSAL_REMOVED], and the status text ("Device is inactive") is for people
 * — `decisions/signals-are-numbers-not-text.md`. Anything else — another code, no key, not a
 * number, a value this build does not know — is unspecified, and nothing erases on it.
 */
object DeviceRefusalReading {
    const val METADATA_KEY = "construct-device-refusal"
    private val key: Metadata.Key<String> = Metadata.Key.of(METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

    fun refusalOf(error: Throwable): DeviceRefusal {
        if (Status.fromThrowable(error).code != Status.Code.UNAUTHENTICATED) return UNSPECIFIED
        val number = Status.trailersFromThrowable(error)?.get(key)?.trim()?.toIntOrNull() ?: return UNSPECIFIED
        return DeviceRefusal.forNumber(number)?.takeIf { it != DeviceRefusal.UNRECOGNIZED } ?: UNSPECIFIED
    }

    /**
     * The one answer that erases this device: its row was deactivated — removed from another device
     * of the account, or signed out from elsewhere — and the server never reactivates a row.
     * [DeviceRefusal.DEVICE_REFUSAL_NOT_FOUND] is not it: an unapproved join request answers that
     * too, and so would a server that lost its rows. **Canon:** iOS `AuthViewModel.isRemovedDevice`.
     *
     * Only over a direct TLS connection to our server ([overDirectTLS]). Through VEIL the client
     * speaks plaintext gRPC to the relay, and a relay able to forge this answer would be able to
     * erase the device; a removed device reached only through VEIL keeps its data until it connects
     * directly.
     */
    fun erasesDevice(refusal: DeviceRefusal, overDirectTLS: Boolean): Boolean =
        overDirectTLS && refusal == DeviceRefusal.DEVICE_REFUSAL_REMOVED

    private val UNSPECIFIED = DeviceRefusal.DEVICE_REFUSAL_UNSPECIFIED
}

/**
 * `AuthenticateDevice` was refused. [overDirectTLS]: the call went out and came back on a TLS
 * connection to our server, not through a VEIL relay — checked both before and after, so a switch
 * to VEIL mid-call counts as VEIL.
 */
class DeviceAuthRefused(
    val refusal: DeviceRefusal,
    val overDirectTLS: Boolean,
    cause: Throwable,
) : Exception("device auth refused: $refusal, direct=$overDirectTLS", cause) {
    val erasesDevice: Boolean get() = DeviceRefusalReading.erasesDevice(refusal, overDirectTLS)
}
