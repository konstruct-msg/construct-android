package com.construct.messenger.veil

import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * The C ABI construct-veil exports from `libconstruct_core.so` (`src/ffi.rs`): the coordinator
 * entry points only. The choice of method, the race and the fallback are Rust's
 * (`docs/IMPLEMENTATION_PLAN.md` §5) — this file passes strings in and a port out.
 * **Canon:** iOS `NativeVeilRuntime`.
 *
 * JNA rather than JNI: the library already loads through it for the UniFFI bindings, and the
 * functions are plain C with `#[repr(C)]` structs.
 */
internal interface VeilLib : Library {
    fun veil_start(req: VeilStartRequest.ByValue, out: VeilStartResult): Int
    fun veil_stop(): Int
    fun veil_is_alive(): Int
    fun veil_port(): Short
    fun veil_last_error(buf: ByteArray, cap: SizeT): SizeT

    companion object {
        val INSTANCE: VeilLib by lazy { Native.load("construct_core", VeilLib::class.java) }
    }
}

/** `usize`: 8 bytes on arm64, 4 on armeabi-v7a. */
class SizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toByte(): Byte = toLong().toByte()
    override fun toChar(): Char = toLong().toInt().toChar()
    override fun toShort(): Short = toLong().toShort()
}

/** `VeilStartRequest` in `construct-veil/src/ffi.rs`, field for field and in the same order. */
@Structure.FieldOrder(
    "relay_addr", "bundle", "tls_sni", "spki_hex", "host_header", "wt_base_path",
    "network_fingerprint", "network_fingerprint_len", "allowed_methods", "scores_path",
    "veil_front_ticket_b64", "veil_capability_v2_b64", "veil_sk_hex",
)
open class VeilStartRequest : Structure() {
    @JvmField var relay_addr: String? = null
    @JvmField var bundle: String? = null
    @JvmField var tls_sni: String? = null
    @JvmField var spki_hex: String? = null
    @JvmField var host_header: String? = null
    @JvmField var wt_base_path: String? = null
    @JvmField var network_fingerprint: Pointer? = null
    @JvmField var network_fingerprint_len: SizeT = SizeT(0)
    @JvmField var allowed_methods: Int = 0
    @JvmField var scores_path: String? = null
    @JvmField var veil_front_ticket_b64: String? = null
    @JvmField var veil_capability_v2_b64: String? = null
    @JvmField var veil_sk_hex: String? = null

    class ByValue : VeilStartRequest(), Structure.ByValue
}

/** `VeilStartResult`: the local port, the method that won, and its latency. */
@Structure.FieldOrder("port", "method", "latency_ms")
open class VeilStartResult : Structure() {
    @JvmField var port: Short = 0
    @JvmField var method: Byte = 0
    @JvmField var latency_ms: Int = 0
}

/**
 * `MethodId` order in construct-veil. Bit N of `allowed_methods` is method N, and a set bit
 * **disables** it (`MethodSet::from_bitmask`: "1 means disabled", 0 = all) — the field's name
 * says the opposite of what it does.
 */
enum class VeilMethod(val id: Int) {
    OBFS4(0), WEBTUNNEL(1), MASQUE(2), VEIL_FRONT(3);

    companion object {
        fun of(id: Int): VeilMethod? = entries.firstOrNull { it.id == id }

        /**
         * Everything but veil-front disabled: obfs4 and WebTunnel are cut by DPI in the target
         * network. Canon: iOS `NativeVeilRuntime.veilFrontOnlyDisabledMethodsBitmask`.
         */
        val DISABLED_EXCEPT_VEIL_FRONT: Int = (1 shl OBFS4.id) or (1 shl WEBTUNNEL.id) or (1 shl MASQUE.id)
    }
}
