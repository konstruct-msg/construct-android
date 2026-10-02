package com.construct.messenger.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.core.v1.Identity
import shared.proto.services.v1.AuthServiceOuterClass.DeviceMetadata
import shared.proto.services.v1.AuthServiceOuterClass.SealedDeviceMetadata

class DeviceMetadataSealTest {

    /** A stand-in for the core's box: the key's first byte, then the plaintext. */
    private val sealTo = { plaintext: ByteArray, key: ByteArray ->
        if (key.size != 32) throw IllegalArgumentException("bad key")
        byteArrayOf(key[0]) + plaintext
    }

    @Test
    fun `an unknown device set never publishes, a moved one does`() {
        assertFalse(DeviceMetadataSeal.needsPublish(emptySet(), setOf("a")))
        assertFalse(DeviceMetadataSeal.needsPublish(setOf("a", "b"), setOf("b", "a")))
        assertTrue(DeviceMetadataSeal.needsPublish(setOf("a", "b"), setOf("a")))
        assertTrue("a removed device must stop reading", DeviceMetadataSeal.needsPublish(setOf("a"), setOf("a", "b")))
        assertTrue(DeviceMetadataSeal.needsPublish(setOf("a"), emptySet()))
    }

    @Test
    fun `one copy per device, the platform and no model string`() {
        val blob = DeviceMetadataSeal.seal(
            DeviceMetadataSeal.selfDescription(),
            listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            sealTo,
        )!!
        val copies = SealedDeviceMetadata.parseFrom(blob).copiesList.map { it.toByteArray() }
        assertEquals(listOf<Byte>(1, 2), copies.map { it[0] })
        val described = DeviceMetadata.parseFrom(copies[0].copyOfRange(1, copies[0].size))
        assertEquals(Identity.DevicePlatform.DEVICE_PLATFORM_ANDROID, described.platform)
        assertEquals("Android", described.deviceName)
        assertArrayEquals(copies[0].copyOfRange(1, copies[0].size), copies[1].copyOfRange(1, copies[1].size))
    }

    @Test
    fun `a bad key costs only its own copy, and no copy at all is no blob`() {
        val blob = DeviceMetadataSeal.seal(
            DeviceMetadataSeal.selfDescription(),
            listOf(ByteArray(7), ByteArray(32) { 3 }),
            sealTo,
        )!!
        assertEquals(1, SealedDeviceMetadata.parseFrom(blob).copiesCount)
        assertNull(DeviceMetadataSeal.seal(DeviceMetadataSeal.selfDescription(), listOf(ByteArray(7)), sealTo))
        assertNull(DeviceMetadataSeal.seal(DeviceMetadataSeal.selfDescription(), emptyList(), sealTo))
    }

    @Test
    fun `a blob over the server's limit is not published`() {
        val many = List(80) { ByteArray(32) { 4 } }
        val big = { plaintext: ByteArray, _: ByteArray -> ByteArray(60) + plaintext }
        assertNull(DeviceMetadataSeal.seal(DeviceMetadataSeal.selfDescription(), many, big))
    }
}
