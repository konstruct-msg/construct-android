package com.construct.messenger.stealth

import com.google.protobuf.ByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass

/**
 * What Android owes the core for TODO 108: every delegation the server serves, and a certificate
 * with its hybrid fields. The verdict itself is the core's (`certificate_verdict`); a field
 * dropped here would leave the hybrid signature unchecked forever, and nothing would say so.
 */
class ServerTrustWireTest {

    @Test
    fun `served delegations are read, and a document without them keeps the cache`() {
        val json = JSONObject("""{"bundle_verification_key":"x","server_trust":{"delegations":["AQID","","!!","BAU="]}}""")
        val decoded = ServerDelegations.decode(ServerDelegations.served(json)!!)
        assertEquals(2, decoded.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded[0])
        assertArrayEquals(byteArrayOf(4, 5), decoded[1])
        assertNull(ServerDelegations.served(JSONObject("""{"bundle_verification_key":"x"}""")))
    }

    /** Mutation: drop `serverKid` or `serverSignatureHybrid` in `toCore` — this reddens. */
    @Test
    fun `the certificate reaches the core with its hybrid signature`() {
        val proto = EnvelopeOuterClass.SenderCertificate.newBuilder()
            .setSenderUserId("u")
            .setSenderDeviceId("d")
            .setServerSignature(ByteString.copyFrom(byteArrayOf(9)))
            .setServerKid(ByteString.copyFrom(byteArrayOf(1, 2)))
            .setServerSignatureHybrid(ByteString.copyFrom(byteArrayOf(3, 4, 5)))
            .build()
        val core = proto.toCore()
        assertArrayEquals(byteArrayOf(1, 2), core.serverKid)
        assertArrayEquals(byteArrayOf(3, 4, 5), core.serverSignatureHybrid)
        assertArrayEquals(byteArrayOf(9), core.signature)
    }
}
