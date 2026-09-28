package com.construct.messenger.invite

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * A signed, one-time contact invite — v5, the only version. Signed by the issuing device over
 * `v|jti|uuid|deviceId|server|ts|un|ttl|hex(addr)`; [addr] is the issuing account's address
 * (its recovery public key), which the redeemer names them by from then on.
 *
 * **Canon:** iOS `InviteObject`, construct-server `InviteToken`; byte agreement fixed by
 * construct-protos `conformance/knst_invite.json` (`InviteObjectTest`).
 *
 * Compact layout ("CIv1"): magic[4] | flags u8 | v u8 | jti[16] | uuid[16] | deviceId[16] |
 * ts u64 BE | sig[64] | serverLen u8 | server | [unLen u8 | un if flags.hasUn] | ttl u32 BE |
 * addr[32]. Nothing follows `addr`.
 */
data class InviteObject(
    val v: Int,
    val jti: String,
    val uuid: String,
    val deviceId: String,
    val server: String,
    val ts: Long,
    val sig: String,
    val un: String?,
    val ttl: Int,
    val addr: ByteArray,
) {
    fun canonicalString(): String {
        if (v != InviteConfig.VERSION) throw InviteException.UnsupportedVersion(v)
        return listOf(
            v.toString(), jti.lowercase(), uuid.lowercase(), deviceId, server, ts.toString(),
            un.orEmpty(), ttl.toString(), hexEncode(addr),
        ).joinToString("|")
    }

    fun effectiveTtlSeconds(): Long = InviteConfig.effectiveTtl(ttl)

    fun isExpired(nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean =
        nowEpochSec > ts + effectiveTtlSeconds()

    fun validate(requireSignature: Boolean = true) {
        if (v != InviteConfig.VERSION) throw InviteException.UnsupportedVersion(v)
        if (runCatching { UUID.fromString(jti) }.isFailure) throw InviteException.InvalidJti
        if (runCatching { UUID.fromString(uuid) }.isFailure) throw InviteException.InvalidUserId
        if (deviceId.length != InviteConfig.DEVICE_ID_LENGTH ||
            !InviteConfig.DEVICE_ID_REGEX.matches(deviceId)
        ) {
            throw InviteException.InvalidDeviceId
        }
        if (server.isEmpty() || '.' !in server) throw InviteException.InvalidServer
        val now = System.currentTimeMillis() / 1000
        if (ts <= 0 || ts > now + InviteConfig.MAX_FUTURE_SKEW_SECONDS) {
            throw InviteException.InvalidTimestamp
        }
        if (requireSignature) {
            val sigBytes = b64decode(sig)
            if (sigBytes.size != InviteConfig.SIGNATURE_LENGTH) throw InviteException.InvalidSignature
        }
        // An overshoot is not an error — the server clamps it.
        if (ttl < InviteConfig.MIN_TTL_SECONDS) throw InviteException.TtlBelowFloor(ttl)
        if (addr.size != AccountAddress.LENGTH) throw InviteException.InvalidAddress
    }

    fun encodeBinary(): ByteArray {
        validate()
        val jtiBytes = uuidBytes(jti) ?: throw InviteException.InvalidJti
        val uuidBytes = uuidBytes(uuid) ?: throw InviteException.InvalidUserId
        val deviceBytes = hexDecode(deviceId)
        if (deviceBytes.size != 16) throw InviteException.InvalidDeviceId
        val sigBytes = b64decode(sig)
        if (sigBytes.size != InviteConfig.SIGNATURE_LENGTH) throw InviteException.InvalidSignature
        val serverData = server.toByteArray(Charsets.UTF_8)
        require(serverData.size <= 255)
        val unData = un?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        if (unData != null) require(unData.size <= 255)

        val out = ArrayList<Byte>(224)
        out += BINARY_MAGIC.toList()
        out += if (unData == null) 0 else FLAG_HAS_USERNAME
        out += v.toByte()
        out += jtiBytes.toList()
        out += uuidBytes.toList()
        out += deviceBytes.toList()
        out += u64be(ts.toULong()).toList()
        out += sigBytes.toList()
        out += serverData.size.toByte()
        out += serverData.toList()
        if (unData != null) {
            out += unData.size.toByte()
            out += unData.toList()
        }
        out += u32be(ttl.toUInt()).toList()
        out += addr.toList()
        return out.toByteArray()
    }

    fun toBase64Url(): String = base64UrlEncode(encodeBinary())

    override fun equals(other: Any?): Boolean =
        other is InviteObject && v == other.v && jti == other.jti && uuid == other.uuid &&
            deviceId == other.deviceId && server == other.server && ts == other.ts &&
            sig == other.sig && un == other.un && ttl == other.ttl && addr.contentEquals(other.addr)

    override fun hashCode(): Int =
        listOf(v, jti, uuid, deviceId, server, ts, sig, un, ttl).hashCode() * 31 + addr.contentHashCode()

    companion object {
        val BINARY_MAGIC = byteArrayOf(0x43, 0x49, 0x76, 0x31) // CIv1
        const val FLAG_HAS_USERNAME: Byte = 0x01

        fun decodeBinary(data: ByteArray): InviteObject {
            val r = Reader(data)
            val magic = r.take(4)
            if (!magic.contentEquals(BINARY_MAGIC)) throw InviteException.BadMagic
            val flags = r.u8()
            val version = r.u8().toInt()
            // Refused before reading on: an older layout has other fields where v5 has these.
            if (version != InviteConfig.VERSION) throw InviteException.UnsupportedVersion(version)
            val jti = uuidString(r.take(16))
            val uuid = uuidString(r.take(16))
            val deviceId = hexEncode(r.take(16))
            val ts = r.u64be().toLong()
            val sig = b64encode(r.take(InviteConfig.SIGNATURE_LENGTH))
            val serverLen = r.u8().toInt() and 0xFF
            val server = r.take(serverLen).toString(Charsets.UTF_8)
            if (server.isEmpty()) throw InviteException.InvalidServer
            var un: String? = null
            if (flags.toInt() and FLAG_HAS_USERNAME.toInt() != 0) {
                val unLen = r.u8().toInt() and 0xFF
                un = r.take(unLen).toString(Charsets.UTF_8)
            }
            val ttl = r.u32be().toInt()
            val addr = r.take(AccountAddress.LENGTH)
            if (!r.atEnd) throw InviteException.TrailingBytes
            return InviteObject(
                v = version,
                jti = jti,
                uuid = uuid,
                deviceId = deviceId,
                server = server,
                ts = ts,
                sig = sig,
                un = un,
                ttl = ttl,
                addr = addr,
            ).also { it.validate() }
        }

        fun fromBase64(encoded: String): InviteObject {
            val data = base64UrlOrStdDecode(encoded) ?: throw InviteException.InvalidEncoding
            return if (data.size >= 4 && data.copyOfRange(0, 4).contentEquals(BINARY_MAGIC)) {
                decodeBinary(data)
            } else {
                throw InviteException.UnrecognizedPayload
            }
        }

        fun fromRaw(raw: String): InviteObject {
            val trimmed = raw.trim()
            extractInviteParam(trimmed)?.let { return fromBase64(it) }
            return fromBase64(trimmed)
        }

        fun extractInviteParam(raw: String): String? {
            val q = raw.indexOf("invite=")
            if (q >= 0) {
                val start = q + "invite=".length
                val end = raw.indexOf('&', start).let { if (it < 0) raw.length else it }
                return raw.substring(start, end)
            }
            return null
        }
    }
}

sealed class InviteException(message: String) : Exception(message) {
    data class UnsupportedVersion(val version: Int) : InviteException("unsupported invite version $version")
    data object InvalidJti : InviteException("invalid jti")
    data object InvalidUserId : InviteException("invalid user uuid")
    data object InvalidDeviceId : InviteException("invalid device id")
    data object InvalidServer : InviteException("invalid server")
    data object InvalidTimestamp : InviteException("invalid timestamp")
    data object InvalidSignature : InviteException("invalid signature")
    data class TtlBelowFloor(val ttl: Int) : InviteException("ttl ${ttl}s below floor")
    data object InvalidAddress : InviteException("invite address must be a 32-byte key")
    data object BadMagic : InviteException("bad CIv1 magic")
    data object TrailingBytes : InviteException("trailing bytes")
    data object InvalidEncoding : InviteException("invalid base64")
    data object UnrecognizedPayload : InviteException("unrecognized invite payload")
    data object Expired : InviteException("invite expired")
    data object AlreadyUsed : InviteException("invite already used")
    data object DeviceIdMismatch : InviteException("device id does not match identity key")
    data object PublicKeyFetchFailed : InviteException("could not fetch inviter keys")
    data object SigningFailed : InviteException("invite self-verify failed")
    data object MissingIdentityKey : InviteException("signing key not available")
    /** This device does not know the account's address — the recovery phrase is needed. */
    data object NoAccountAddress : InviteException("recovery phrase not set up on this device")
}

internal fun uuidBytes(s: String): ByteArray? = runCatching {
    val u = UUID.fromString(s)
    ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
}.getOrNull()

internal fun uuidString(bytes: ByteArray): String {
    val buf = ByteBuffer.wrap(bytes)
    return UUID(buf.long, buf.long).toString().lowercase()
}

internal fun hexEncode(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

internal fun hexDecode(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

internal fun b64encode(bytes: ByteArray): String =
    Base64.getEncoder().encodeToString(bytes)

internal fun b64decode(s: String): ByteArray =
    Base64.getDecoder().decode(s)

internal fun base64UrlEncode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun base64UrlOrStdDecode(s: String): ByteArray? {
    val cleaned = s.trim()
    return runCatching { Base64.getUrlDecoder().decode(cleaned) }.getOrNull()
        ?: runCatching { Base64.getDecoder().decode(cleaned) }.getOrNull()
}

private fun u64be(v: ULong): ByteArray = ByteBuffer.allocate(8).putLong(v.toLong()).array()
private fun u32be(v: UInt): ByteArray = ByteBuffer.allocate(4).putInt(v.toInt()).array()

private class Reader(private val data: ByteArray) {
    private var i = 0
    val atEnd: Boolean get() = i == data.size
    fun take(n: Int): ByteArray {
        if (i + n > data.size) throw InviteException.UnrecognizedPayload
        val slice = data.copyOfRange(i, i + n)
        i += n
        return slice
    }
    fun u8(): Byte = take(1)[0]
    fun u32be(): UInt = ByteBuffer.wrap(take(4)).int.toUInt()
    fun u64be(): ULong = ByteBuffer.wrap(take(8)).long.toULong()
}
