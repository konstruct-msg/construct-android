package com.construct.messenger.veil

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.construct.messenger.diagnostics.Log
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.conscrypt.Conscrypt

/**
 * Native-TLS veil-front path for Android, mirroring iOS `VeilFrontExternalDialer`. The TLS to the
 * relay is terminated in **bundled Conscrypt** — a genuine Android ClientHello (gate (b): JA3
 * `2be3ae04`, byte-for-byte the platform Conscrypt the Android-app crowd sends) instead of the
 * rustls-`Chrome131` oddball. Rust runs the post-TLS veil-front framing + AUTH over the decrypted
 * duplex ([VeilLib.veil_front_ferry_fd]), bound to the TLS exporter (gate (a): Conscrypt's exporter
 * == rustls byte-for-byte).
 *
 * The host owns a **stable** local gRPC listener (so the local port outlives reconnects — no
 * startup flap), accepts each connection, dials a fresh Conscrypt `SSLSocket` to the relay, derives
 * the exporter, and hands Rust the accepted local fd + a socketpair fd carrying the decrypted relay
 * bytes; two pump threads move plaintext between the `SSLSocket` and that socketpair while Rust
 * ferries the gRPC side.
 *
 * Off by default behind [VeilProxy]'s flag; [VeilProxy] falls back to rustls (`veil_start`) when
 * [start] returns null. A [start] that binds but whose relay is unreachable/mispinned is caught by
 * the one up-front validation dial, so fallback stays clean.
 */
class VeilFrontExternalDialer(
    /**
     * The listener died while nobody stopped it — its port, its relay. Without this the route goes
     * on pointing gRPC at a port nothing accepts on, and finds out only when the stream has been
     * silent for a minute. Asked of the listener itself, not read off a failed accept: the
     * tester's `getsockopt failed: EBADF` (2026-10-16) was one accepted connection going bad, with
     * the listener fine (TODO 164).
     */
    private val onListenerLost: (port: Int, relay: String) -> Unit = { _, _ -> },
) {

    @Volatile private var listenFd: FileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var listenPort = 0

    /** Whether the local listener still accepts. The rustls coordinator knows nothing of it. */
    val isListening: Boolean get() = running
    private val sessions = Collections.synchronizedSet(mutableSetOf<Session>())

    // AUTH material + relay for the accept loop; set by start().
    @Volatile private var relay: VeilRelay? = null
    private var ticketB64: String = ""
    private var capabilityB64: String = ""
    private var veilSkHex: String = ""

    /**
     * Validate the relay (reachable + SPKI pin + handshake + exporter), then bind a stable local
     * listener and start accepting. Returns the local port, or null to fall back to rustls.
     */
    fun start(relay: VeilRelay, ticketB64: String, capabilityB64: String, veilSkHex: String): Int? {
        // Up-front dial: proves reachability + pin + TLS before committing the path. A failure here
        // is the clean fallback signal; after this, per-connection dials run in the accept loop.
        try {
            dial(relay).close()
        } catch (e: Exception) {
            Log.w(TAG, "native veil-front validation dial failed, falling back: ${e.message}")
            return null
        }

        this.relay = relay
        this.ticketB64 = ticketB64
        this.capabilityB64 = capabilityB64
        this.veilSkHex = veilSkHex

        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        try {
            Os.bind(fd, InetAddress.getByName("127.0.0.1"), 0)
            Os.listen(fd, 16)
        } catch (e: Exception) {
            runCatching { Os.close(fd) }
            Log.e(TAG, "native veil-front listener bind failed: ${e.message}")
            return null
        }
        val port = (Os.getsockname(fd) as InetSocketAddress).port
        listenFd = fd
        listenPort = port
        running = true
        thread(name = "veil-front-accept", isDaemon = true) { acceptLoop(fd, port, relay.address) }
        Log.i(TAG, "native veil-front up (Conscrypt), local :$port")
        return port
    }

    fun stop() {
        running = false
        listenFd?.let {
            // On Linux close() does not wake a thread blocked in accept() (close(2), NOTES), and
            // libcore's Os.close signals nobody: without the shutdown every stop left the old
            // accept thread waiting and the old port still listening.
            runCatching { Os.shutdown(it, OsConstants.SHUT_RDWR) }
            runCatching { Os.close(it) }
        }
        listenFd = null
        synchronized(sessions) { sessions.toList() }.forEach { it.close() }
        sessions.clear()
        relay = null
    }

    private fun acceptLoop(fd: FileDescriptor, port: Int, relayAddress: String) {
        runAcceptLoop(
            isRunning = { running && listenPort == port },
            accept = { Os.accept(fd, null) },
            // Our socket, still bound where it was: a number closed under us and reused by another
            // socket answers with a different port.
            listenerAlive = { runCatching { (Os.getsockname(fd) as InetSocketAddress).port == port }.getOrDefault(false) },
            onDropped = { e -> Log.w(TAG, "accept on :$port: ${e.message} — one connection lost, listener fine") },
            handle = { client ->
                val r = relay
                if (r == null) {
                    runCatching { Os.close(client) }
                    false
                } else {
                    thread(name = "veil-front-conn", isDaemon = true) { handleConnection(client, r) }
                    true
                }
            },
            onLost = { e ->
                Log.w(TAG, "accept ended: ${e.message} — listener :$port lost")
                running = false
                onListenerLost(port, relayAddress)
            },
        )
    }

    /** One accepted local gRPC connection → one fresh Conscrypt relay session → one Rust ferry. */
    private fun handleConnection(clientFd: FileDescriptor, r: VeilRelay) {
        var ssl: SSLSocket? = null
        var hostSide: FileDescriptor? = null // our pump end of the socketpair
        var rustSide: FileDescriptor? = null
        var session: Session? = null
        try {
            ssl = dial(r)
            val exporter = Conscrypt.exportKeyingMaterial(ssl, EXPORTER_LABEL, ByteArray(0), EXPORTER_LEN)

            val rust = FileDescriptor()
            val host = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, rust, host)
            rustSide = rust
            hostSide = host

            session = Session(ssl, host).also { sessions.add(it) }
            startPumps(session)

            // Hand Rust its own dups; it adopts and closes them. We keep `host` + `ssl`. Held as
            // descriptors until the call, so a failed second dup leaves no bare number behind.
            val localPfd = ParcelFileDescriptor.dup(clientFd)
            val rustPfd = try {
                ParcelFileDescriptor.dup(rust)
            } catch (e: Exception) {
                runCatching { localPfd.close() }
                throw e
            }
            runCatching { Os.close(clientFd) }
            runCatching { Os.close(rust) }

            val rc = VeilLib.INSTANCE.veil_front_ferry_fd(
                localPfd.detachFd(), rustPfd.detachFd(), exporter, SizeT(EXPORTER_LEN.toLong()),
                capabilityB64.ifEmpty { null }, veilSkHex.ifEmpty { null }, ticketB64.ifEmpty { null },
            )
            if (rc != 0) {
                // Rust already closed localInt/rustInt. Tear down our side.
                Log.e(TAG, "veil_front_ferry_fd rc=$rc")
                session.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "native veil-front connection failed: ${e.message}")
            // A descriptor already closed above reads -1 now, and closing it again does nothing.
            runCatching { Os.close(clientFd) }
            rustSide?.let { runCatching { Os.close(it) } }
            val live = session
            if (live != null) {
                // The pumps run: they close `host` too, so it goes only through the session's
                // once-only close. Closing it here as well raced them — libcore reads the number and
                // then marks it -1, so two threads could both close it, the second hitting whatever
                // took the number next.
                live.close()
            } else {
                runCatching { ssl?.close() }
                hostSide?.let { runCatching { Os.close(it) } }
            }
        }
    }

    /** Pump plaintext between the SSLSocket and the socketpair host end. Either side's end closes both. */
    private fun startPumps(s: Session) {
        thread(name = "veil-ferry-r2h", isDaemon = true) {
            val buf = ByteArray(BUF)
            try {
                val input = s.ssl.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) Os.write(s.host, buf, 0, n)
                }
            } catch (_: Exception) {
            } finally {
                s.close()
            }
        }
        thread(name = "veil-ferry-h2r", isDaemon = true) {
            val buf = ByteArray(BUF)
            try {
                val output = s.ssl.outputStream
                while (true) {
                    val n = Os.read(s.host, buf, 0, buf.size)
                    if (n <= 0) break // 0 = EOF on the socketpair
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                s.close()
            }
        }
    }

    private fun dial(r: VeilRelay): SSLSocket {
        val host = r.address.substringBeforeLast(':')
        val port = r.address.substringAfterLast(':').toInt()
        val ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider())
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(SpkiPinTrustManager(r.spkiHex)), SecureRandom())
        val ssl = ctx.socketFactory.createSocket() as SSLSocket
        try {
            ssl.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            ssl.soTimeout = 0 // long-lived tunnel; no read timeout
            // SNI = the pinned front name (may differ from the dialed host). Default enabled
            // protocols (TLS 1.2 + 1.3) — the shippable Conscrypt hello, never 1.3-only.
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(r.sni))
            ssl.sslParameters = params
            Conscrypt.setApplicationProtocols(ssl, arrayOf("h2"))
            ssl.startHandshake()
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw e
        }
        return ssl
    }

    /** A live ferry: the SSLSocket and our socketpair end, torn down exactly once. */
    private inner class Session(val ssl: SSLSocket, val host: FileDescriptor) {
        private val closed = AtomicBoolean(false)
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            sessions.remove(this)
            runCatching { ssl.close() } // unblocks the r2h read / h2r write
            runCatching { Os.shutdown(host, OsConstants.SHUT_RDWR) } // unblocks the h2r Os.read
            runCatching { Os.close(host) }
        }
    }

    /** Verifies the leaf by SPKI pin (SHA-256 of DER SubjectPublicKeyInfo), not CA chain — as the relay does. */
    private class SpkiPinTrustManager(spkiHexPin: String) : X509TrustManager {
        private val pin = spkiHexPin.trim().lowercase()

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val spkiDer = leaf.publicKey.encoded // X.509 SubjectPublicKeyInfo DER
            val got = MessageDigest.getInstance("SHA-256").digest(spkiDer)
                .joinToString("") { "%02x".format(it) }
            if (got != pin) throw CertificateException("SPKI pin mismatch (got $got)")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client auth not supported")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val TAG = "VEIL"
        // EXPORTER_LABEL / EXPORTER_LEN — construct-veil-protocol/src/lib.rs. Must match the relay.
        const val EXPORTER_LABEL = "construct veil-front auth v1"
        const val EXPORTER_LEN = 32
        const val CONNECT_TIMEOUT_MS = 10_000
        const val BUF = 16384
    }
}

/**
 * Accept until [handle] says stop, the loop is stopped, or the listener is gone. After `stop()` a
 * throw is the shutdown it did. A throw while running is not by itself the listener dying: libcore
 * checks the *accepted* socket after a successful accept (`BlockGuardOs.accept` → `getsockopt`),
 * so one connection whose number was closed in that window throws too (TODO 164). [listenerAlive]
 * decides: alive, that one connection is dropped ([onDropped]) and accepting goes on after a short
 * pause that grows while throws repeat, so a fault that recurs cannot spin; gone, [onLost], once.
 */
internal fun runAcceptLoop(
    isRunning: () -> Boolean,
    accept: () -> FileDescriptor,
    handle: (FileDescriptor) -> Boolean,
    listenerAlive: () -> Boolean,
    onLost: (Exception) -> Unit,
    onDropped: (Exception) -> Unit = {},
    pause: (Long) -> Unit = Thread::sleep,
) {
    var misses = 0
    while (isRunning()) {
        val client = try {
            accept()
        } catch (e: Exception) {
            if (!isRunning()) return
            if (!listenerAlive()) {
                onLost(e)
                return
            }
            onDropped(e)
            pause(acceptBackoffMs(misses++))
            continue
        }
        misses = 0
        if (!handle(client)) return
    }
}

/** 10 ms doubling to 1 s. */
internal fun acceptBackoffMs(misses: Int): Long = minOf(1_000L, 10L shl misses.coerceAtMost(7))
