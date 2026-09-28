package com.construct.messenger.invite

import com.construct.messenger.data.repository.toProto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v5 against construct-protos `conformance/knst_invite.json` — the file iOS and construct-server
 * are held to as well. Three implementations build the canonical string and the CIv1 bytes
 * independently, and a disagreement surfaces only at redeem, as "invalid signature". The values
 * are copied from the vector file; if it changes, copy them again.
 */
class InviteObjectTest {

    private fun vector(un: String?) = InviteObject(
        v = 5,
        jti = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        uuid = "14f28d31-5b2a-4c1e-9a3d-6f0e2b7c8d90",
        deviceId = "6f5e37ac1b2c3d4e5f60718293a4b5c6",
        server = "konstruct.cc",
        ts = 1790000000L,
        sig = b64encode(hexDecode(if (un == null) SIG_WITHOUT_UN else SIG_WITH_UN)),
        un = un,
        ttl = 300,
        addr = hexDecode("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
    )

    @Test
    fun `canonical string matches the vector`() {
        assertEquals(CANONICAL_WITH_UN, vector("alice").canonicalString())
        assertEquals(CANONICAL_WITHOUT_UN, vector(null).canonicalString())
    }

    @Test
    fun `binary matches the vector and decodes back`() {
        assertEquals(BINARY_WITH_UN, hexEncode(vector("alice").encodeBinary()))
        assertEquals(BINARY_WITHOUT_UN, hexEncode(vector(null).encodeBinary()))
        assertEquals(vector("alice"), InviteObject.decodeBinary(hexDecode(BINARY_WITH_UN)))
    }

    @Test
    fun `refused blobs do not decode`() {
        for ((name, blob) in REFUSED) {
            try {
                InviteObject.decodeBinary(hexDecode(blob))
                fail("$name decoded")
            } catch (_: InviteException) {
            }
        }
    }

    /** Mutation: drop `addr` from `canonicalString` — this reddens, as does the vector. */
    @Test
    fun `the address is signed`() {
        val a = vector("alice")
        val b = InviteObject(a.v, a.jti, a.uuid, a.deviceId, a.server, a.ts, a.sig, a.un, a.ttl, ByteArray(32) { 1 })
        assertNotEquals(a.canonicalString(), b.canonicalString())
    }

    @Test
    fun `an address that is not a key is refused`() {
        for (size in listOf(0, 31, 33)) {
            val a = vector("alice")
            val bad = InviteObject(a.v, a.jti, a.uuid, a.deviceId, a.server, a.ts, a.sig, a.un, a.ttl, ByteArray(size))
            try {
                bad.validate()
                fail("an address of $size bytes was accepted")
            } catch (_: InviteException.InvalidAddress) {
            }
        }
    }

    @Test
    fun `only v5 is accepted`() {
        for (v in listOf(1, 2, 3, 4, 6)) {
            val a = vector("alice")
            val other = InviteObject(v, a.jti, a.uuid, a.deviceId, a.server, a.ts, a.sig, a.un, a.ttl, a.addr)
            try {
                other.canonicalString()
                fail("v$v produced a canonical string")
            } catch (_: InviteException.UnsupportedVersion) {
            }
        }
    }

    @Test
    fun `effective ttl clamps to server max`() {
        assertEquals(InviteConfig.TTL_SECONDS, InviteConfig.effectiveTtl(100_000))
        assertEquals(300L, InviteConfig.effectiveTtl(300))
    }

    @Test
    fun `deep link extract then decode`() {
        val link = "${InviteConfig.DEEP_LINK_SCHEME}?invite=${vector("alice").toBase64Url()}"
        assertEquals(vector("alice"), InviteObject.fromRaw(link))
    }

    /**
     * Every field the server rebuilds its canonical string from survives the AcceptInvite
     * mapping; `addr` is also the one it checks against the account's recovery key.
     *
     * Mutation: drop `.setAddr(...)` from `toProto` — this reddens.
     */
    @Test
    fun `every canonical field survives the AcceptInvite mapping`() {
        val source = vector("alice")
        val token = source.toProto()
        assertEquals(5, token.v)
        assertEquals(source.jti, token.jti)
        assertEquals(source.uuid, token.uuid)
        assertEquals(source.deviceId, token.deviceId)
        assertEquals(source.server, token.server)
        assertEquals(source.ts, token.ts)
        assertEquals(source.sig, token.sig)
        assertEquals(source.un, token.un)
        assertEquals(source.ttl, token.ttl)
        assertArrayEquals(source.addr, token.addr.toByteArray())
        assertTrue("the server refuses a v5 that carries one", token.ephPub.isEmpty())
    }

    private companion object {
        const val CANONICAL_WITH_UN = "5|7c9e6679-7425-40de-944b-e07fc1f90ae7|14f28d31-5b2a-4c1e-9a3d-6f0e2b7c8d90|6f5e37ac1b2c3d4e5f60718293a4b5c6|konstruct.cc|1790000000|alice|300|3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        const val CANONICAL_WITHOUT_UN = "5|7c9e6679-7425-40de-944b-e07fc1f90ae7|14f28d31-5b2a-4c1e-9a3d-6f0e2b7c8d90|6f5e37ac1b2c3d4e5f60718293a4b5c6|konstruct.cc|1790000000||300|3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        const val SIG_WITH_UN = "d243215959c9c7bdc3f4f089e678972299f8f1e3f95194217d3c3c197ff39eb967a60043e18f523103717b16cccfeb0ec2ddeea0d91fdee2f81412a781bb9f04"
        const val SIG_WITHOUT_UN = "750bf8ba43ff6a284509efaa6df1b0420da3bfe007b193f8a87ed357f92c96eb168d19ff33eba7b6bef6d2405c7cfe05d002ed854961045c967ad17563a0ac0a"
        const val BINARY_WITH_UN =
            "4349763101057c9e6679742540de944be07fc1f90ae714f28d315b2a4c1e9a3d6f0e2b7c8d906f5e37ac1b2c3d4e5f60718293a4b5c6000000006ab13b80d243215959c9c7bdc3f4f089e678972299f8f1e3f95194217d3c3c197ff39eb967a60043e18f523103717b16cccfeb0ec2ddeea0d91fdee2f81412a781bb9f040c6b6f6e7374727563742e636305616c6963650000012c3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        const val BINARY_WITHOUT_UN =
            "4349763100057c9e6679742540de944be07fc1f90ae714f28d315b2a4c1e9a3d6f0e2b7c8d906f5e37ac1b2c3d4e5f60718293a4b5c6000000006ab13b80750bf8ba43ff6a284509efaa6df1b0420da3bfe007b193f8a87ed357f92c96eb168d19ff33eba7b6bef6d2405c7cfe05d002ed854961045c967ad17563a0ac0a0c6b6f6e7374727563742e63630000012c3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        val REFUSED = listOf(
            "version_4" to "4349763101047c9e6679742540de944be07fc1f90ae714f28d315b2a4c1e9a3d6f0e2b7c8d906f5e37ac1b2c3d4e5f60718293a4b5c6000000006ab13b80d243215959c9c7bdc3f4f089e678972299f8f1e3f95194217d3c3c197ff39eb967a60043e18f523103717b16cccfeb0ec2ddeea0d91fdee2f81412a781bb9f040c6b6f6e7374727563742e636305616c6963650000012c3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "addr_truncated" to "4349763101057c9e6679742540de944be07fc1f90ae714f28d315b2a4c1e9a3d6f0e2b7c8d906f5e37ac1b2c3d4e5f60718293a4b5c6000000006ab13b80d243215959c9c7bdc3f4f089e678972299f8f1e3f95194217d3c3c197ff39eb967a60043e18f523103717b16cccfeb0ec2ddeea0d91fdee2f81412a781bb9f040c6b6f6e7374727563742e636305616c6963650000012c3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af466",
            "trailing_byte" to "4349763101057c9e6679742540de944be07fc1f90ae714f28d315b2a4c1e9a3d6f0e2b7c8d906f5e37ac1b2c3d4e5f60718293a4b5c6000000006ab13b80d243215959c9c7bdc3f4f089e678972299f8f1e3f95194217d3c3c197ff39eb967a60043e18f523103717b16cccfeb0ec2ddeea0d91fdee2f81412a781bb9f040c6b6f6e7374727563742e636305616c6963650000012c3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c00",
        )
    }
}
