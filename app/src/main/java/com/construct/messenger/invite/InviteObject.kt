package com.construct.messenger.invite

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

data class InviteObject(
    val v: Int,
    val jti: String,
    val uuid: String,
    val deviceId: String,
    val server: String,
    val ephKey: String,
    val ts: Long,
    val sig: String,
    val un: String?,
    val ttl: Int?,
) {
    fun canonicalString(): String {
        val jtiLower = jti.lowercase()
        val uuidLower = uuid.lowercase()
        return when (v) {
            1 -> "$v|$jtiLower|$uuidLower|$server|$ephKey|$ts"
            2 -> "$v|$jtiLower|$uuidLower|$deviceId|$server|$ephKey|$ts"
            3 -> "$v|$jtiLower|$uuidLower|$deviceId|$server|$ephKey|$ts|${un.orEmpty()}"
            4 -> "$v|$jtiLower|$uuidLower|$deviceId|$server|$ts|${un.orEmpty()}"
            5 -> {
                val t = ttl ?: throw InviteException.MissingTtl
                "$v|$jtiLower|$uuidLower|$deviceId|$server|$ts|${un.orEmpty()}|$t"
            }
            else -> throw InviteException.UnsupportedVersion(v)
        }
    }

    fun effectiveTtlSeconds(): Long = InviteConfig.effectiveTtl(ttl)

    fun isExpired(nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean =
        nowEpochSec > ts + effectiveTtlSeconds()

    fun validate(requireSignature: Boolean = true) {
        if (v !in InviteConfig.supportedVersions) throw InviteException.UnsupportedVersion(v)
        if (runCatching { UUID.fromString(jti) }.isFailure) throw InviteException.InvalidJti
        if (runCatching { UUID.fromString(uuid) }.isFailure) throw InviteException.InvalidUserId
        if (v >= 2) {
            if (deviceId.length != InviteConfig.DEVICE_ID_LENGTH ||
                !InviteConfig.DEVICE_ID_REGEX.matches(deviceId)
            ) {
                throw InviteException.InvalidDeviceId
            }
        }
        if (server.isEmpty() || '.' !in server) throw InviteException.InvalidServer
        if (InviteConfig.carriesEphKey(v)) {
            val eph = b64decode(ephKey)
            if (eph.size != InviteConfig.EPH_KEY_LENGTH) throw InviteException.InvalidEphKey
        } else if (ephKey.isNotEmpty()) {
            throw InviteException.InvalidEphKey
        }
        val now = System.currentTimeMillis() / 1000
        if (ts <= 0 || ts > now + InviteConfig.MAX_FUTURE_SKEW_SECONDS) {
            throw InviteException.InvalidTimestamp
        }
        if (requireSignature) {
            val sigBytes = b64decode(sig)
            if (sigBytes.size != InviteConfig.SIGNATURE_LENGTH) throw InviteException.InvalidSignature
        }
        if (InviteConfig.carriesTtl(v)) {
            val t = ttl ?: throw InviteException.MissingTtl
            if (t < InviteConfig.MIN_TTL_SECONDS) throw InviteException.TtlBelowFloor(t)
        } else if (ttl != null) {
            throw InviteException.TtlOnUnsupportedVersion(v)
        }
    }

    fun encodeBinary(): ByteArray {
        validate()
        val jtiBytes = uuidBytes(jti) ?: throw InviteException.InvalidJti
        val uuidBytes = uuidBytes(uuid) ?: throw InviteException.InvalidUserId
        val deviceBytes = hexDecode(deviceId)
        if (deviceBytes.size != 16) throw InviteException.InvalidDeviceId
        val sigBytes = b64decode(sig)
        if (sigBytes.size != InviteConfig.SIGNATURE_LENGTH) throw InviteException.InvalidSignature
        val ephBytes = if (InviteConfig.carriesEphKey(v)) {
            val e = b64decode(ephKey)
            if (e.size != InviteConfig.EPH_KEY_LENGTH) throw InviteException.InvalidEphKey
            e
        } else {
            null
        }
        val serverData = server.toByteArray(Charsets.UTF_8)
        require(serverData.size <= 255)
        val unData = un?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        if (unData != null) require(unData.size <= 255)

        val out = ArrayList<Byte>(128)
        out += BINARY_MAGIC.toList()
        out += if (unData == null) 0 else FLAG_HAS_USERNAME
        out += v.toByte()
        out += jtiBytes.toList()
        out += uuidBytes.toList()
        out += deviceBytes.toList()
        if (ephBytes != null) out += ephBytes.toList()
        out += u64be(ts.toULong()).toList()
        out += sigBytes.toList()
        out += serverData.size.toByte()
        out += serverData.toList()
        if (unData != null) {
            out += unData.size.toByte()
            out += unData.toList()
        }
        if (InviteConfig.carriesTtl(v)) {
            val t = ttl ?: throw InviteException.MissingTtl
            out += u32be(t.toUInt()).toList()
        }
        return out.toByteArray()
    }

    fun toBase64Url(): String = base64UrlEncode(encodeBinary())

    companion object {
        val BINARY_MAGIC = byteArrayOf(0x43, 0x49, 0x76, 0x31) // CIv1
        const val FLAG_HAS_USERNAME: Byte = 0x01

        fun decodeBinary(data: ByteArray): InviteObject {
            val r = Reader(data)
            val magic = r.take(4)
            if (!magic.contentEquals(BINARY_MAGIC)) throw InviteException.BadMagic
            val flags = r.u8()
            val version = r.u8().toInt()
            val jti = uuidString(r.take(16))
            val uuid = uuidString(r.take(16))
            val deviceId = hexEncode(r.take(16))
            val ephKey = if (InviteConfig.carriesEphKey(version)) {
                b64encode(r.take(InviteConfig.EPH_KEY_LENGTH))
            } else {
                ""
            }
            val ts = r.u64be().toLong()
            val sig = b64encode(r.take(InviteConfig.SIGNATURE_LENGTH))
            val serverLen = r.u8().toInt()
            val server = r.take(serverLen).toString(Charsets.UTF_8)
            if (server.isEmpty()) throw InviteException.InvalidServer
            var un: String? = null
            if (flags.toInt() and FLAG_HAS_USERNAME.toInt() != 0) {
                val unLen = r.u8().toInt()
                un = r.take(unLen).toString(Charsets.UTF_8)
            }
            val ttl = if (InviteConfig.carriesTtl(version)) r.u32be().toInt() else null
            if (!r.atEnd) throw InviteException.TrailingBytes
            return InviteObject(
                v = version,
                jti = jti,
                uuid = uuid,
                deviceId = deviceId,
                server = server,
                ephKey = ephKey,
                ts = ts,
                sig = sig,
                un = un,
                ttl = ttl,
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
    data object InvalidEphKey : InviteException("invalid eph key")
    data object InvalidTimestamp : InviteException("invalid timestamp")
    data object InvalidSignature : InviteException("invalid signature")
    data object MissingTtl : InviteException("v5 invite without ttl")
    data class TtlBelowFloor(val ttl: Int) : InviteException("ttl ${ttl}s below floor")
    data class TtlOnUnsupportedVersion(val version: Int) : InviteException("v$version must not carry ttl")
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
