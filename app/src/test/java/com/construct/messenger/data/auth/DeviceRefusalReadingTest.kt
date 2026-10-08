package com.construct.messenger.data.auth

import io.grpc.Metadata
import io.grpc.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.services.v1.AuthServiceOuterClass.DeviceRefusal

/**
 * What erases a device, and what does not. **Canon:** iOS `LocalDataWipeTests`, "Reading the
 * refusal"; each test names the mutation that must redden it.
 */
class DeviceRefusalReadingTest {

    private fun refused(code: Status.Code, value: String?, message: String = "Device is inactive") =
        Status.fromCode(code).withDescription(message).asRuntimeException(
            Metadata().apply {
                if (value != null) put(Metadata.Key.of(DeviceRefusalReading.METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER), value)
            },
        )

    @Test
    fun `the number names the refusal`() {
        assertEquals(DeviceRefusal.DEVICE_REFUSAL_REMOVED, DeviceRefusalReading.refusalOf(refused(Status.Code.UNAUTHENTICATED, "1")))
        assertEquals(DeviceRefusal.DEVICE_REFUSAL_NOT_FOUND, DeviceRefusalReading.refusalOf(refused(Status.Code.UNAUTHENTICATED, "2")))
    }

    /** Mutation: branch on the status text — "Device is inactive" without the key reads as removed. */
    @Test
    fun `no key, a word, or an unknown number is unspecified`() {
        listOf(null, "removed", "REMOVED", "", "99", "-1").forEach { value ->
            assertEquals(value, DeviceRefusal.DEVICE_REFUSAL_UNSPECIFIED, DeviceRefusalReading.refusalOf(refused(Status.Code.UNAUTHENTICATED, value)))
        }
    }

    /** Mutation: drop the code check — a PERMISSION_DENIED with the key reads as removed. */
    @Test
    fun `the key counts only on UNAUTHENTICATED`() {
        listOf(Status.Code.PERMISSION_DENIED, Status.Code.NOT_FOUND, Status.Code.UNAVAILABLE).forEach { code ->
            assertEquals(code.name, DeviceRefusal.DEVICE_REFUSAL_UNSPECIFIED, DeviceRefusalReading.refusalOf(refused(code, "1")))
        }
        assertEquals(DeviceRefusal.DEVICE_REFUSAL_UNSPECIFIED, DeviceRefusalReading.refusalOf(IllegalStateException("1")))
    }

    /**
     * Mutation: drop `overDirectTLS` — a VEIL relay's forged answer erases the device; or erase on
     * NOT_FOUND — an unapproved join request erases it.
     */
    @Test
    fun `only REMOVED over direct TLS erases`() {
        assertTrue(DeviceRefusalReading.erasesDevice(DeviceRefusal.DEVICE_REFUSAL_REMOVED, overDirectTLS = true))
        assertFalse(DeviceRefusalReading.erasesDevice(DeviceRefusal.DEVICE_REFUSAL_REMOVED, overDirectTLS = false))
        assertFalse(DeviceRefusalReading.erasesDevice(DeviceRefusal.DEVICE_REFUSAL_NOT_FOUND, overDirectTLS = true))
        assertFalse(DeviceRefusalReading.erasesDevice(DeviceRefusal.DEVICE_REFUSAL_UNSPECIFIED, overDirectTLS = true))
    }
}
