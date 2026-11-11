package com.construct.messenger.data.auth

import io.grpc.Metadata
import io.grpc.Status
import uniffi.construct_core.DeviceRefusalKind
import uniffi.construct_core.deviceRefusalOf
import uniffi.construct_core.refusalErasesDevice

/**
 * Why the server refused this device, from the trailing metadata key `construct-device-refusal` of
 * an UNAUTHENTICATED status (construct-protos `DeviceRefusal`). Read as a number because a client
 * erases itself on [DeviceRefusalKind.REMOVED], and the status text ("Device is inactive") is for
 * people — `decisions/signals-are-numbers-not-text.md`.
 *
 * The reading and the decision are the core's (construct-rules `device_refusal`), one for every
 * client: construct-client erased on the text until 2026-11-10 (todo 292). This hands the core the
 * status code and the trailer; anything else — no key, not a number, an unknown one — reads as
 * [DeviceRefusalKind.UNSPECIFIED], and nothing erases on it.
 */
object DeviceRefusalReading {
    const val METADATA_KEY = "construct-device-refusal"
    private val key: Metadata.Key<String> = Metadata.Key.of(METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

    fun refusalOf(error: Throwable): DeviceRefusalKind =
        deviceRefusalOf(
            Status.fromThrowable(error).code.value().toUInt(),
            Status.trailersFromThrowable(error)?.get(key),
        )

    /**
     * The one answer that erases this device: `REMOVED`, over a direct TLS connection to our server
     * ([overDirectTLS]). Through VEIL the client speaks plaintext gRPC to the relay, and a relay able
     * to forge this answer would be able to erase the device. The core's rule.
     */
    fun erasesDevice(refusal: DeviceRefusalKind, overDirectTLS: Boolean): Boolean =
        refusalErasesDevice(refusal, overDirectTLS)
}

/**
 * `AuthenticateDevice` was refused. [overDirectTLS]: the call went out and came back on a TLS
 * connection to our server, not through a VEIL relay — checked both before and after, so a switch
 * to VEIL mid-call counts as VEIL.
 */
class DeviceAuthRefused(
    val refusal: DeviceRefusalKind,
    val overDirectTLS: Boolean,
    cause: Throwable,
) : Exception("device auth refused: $refusal, direct=$overDirectTLS", cause) {
    val erasesDevice: Boolean get() = DeviceRefusalReading.erasesDevice(refusal, overDirectTLS)
}
