package com.construct.messenger.veil

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android half of the exporter probe. The question it answers is the same one
 * `crates/construct-veil-relay/examples/exporter_probe.{rs,swift}` answered for iOS, now for the
 * stack a future Android veil-front native-TLS dialer would use: **does Conscrypt's TLS exporter
 * agree with `rustls::export_keying_material` byte for byte?** veil-front binds its AUTH record to
 * that exporter, so if Conscrypt and the relay's rustls disagree, the whole Conscrypt path is a
 * non-starter — this is the gating de-risk, proven before any production dialer exists.
 *
 * It mirrors `exporter_probe.swift`: connect to the throwaway rustls server
 * (`cargo run --example exporter_probe`), accept its self-signed cert (the question is the
 * exporter, not trust), derive the exporter over the label / length with an empty-but-present
 * context, and print it. The exporter is unique per handshake, so this prints `conscrypt: <hex>`
 * and the rust server prints `rust: <hex>` for the *same* connection — compare the two by eye
 * (as the iOS probe did). Equal → gate closed.
 *
 * This is a throwaway probe, not a shipped path: Conscrypt is an `androidTestImplementation`
 * dependency only, and nothing here touches the app's VEIL code. See the run runbook in
 * construct-docs (VEIL_CONSCRYPT_EXPORTER_PARITY_RUNBOOK).
 *
 * Run (see runbook for the full flow incl. `adb reverse`):
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.construct.messenger.veil.VeilExporterParityProbe \
 *   -Pandroid.testInstrumentationRunnerArguments.port=<rust server port> \
 *   -Pandroid.testInstrumentationRunnerArguments.host=10.0.2.2
 * ```
 */
@RunWith(AndroidJUnit4::class)
class VeilExporterParityProbe {

    /** EXPORTER_LABEL — construct_veil_protocol::lib.rs. Must match the relay exactly. */
    private val label = "construct veil-front auth v1"

    /** EXPORTER_LEN — construct_veil_protocol::lib.rs. */
    private val exporterLen = 32

    @Test
    fun conscryptExporterMatchesRustls() {
        val args = InstrumentationRegistry.getArguments()
        val portArg = args.getString("port")
        // No port → the rust server was not started / wired. Skip rather than fail: this probe is
        // meaningless without its server half, and we don't want it red in an unrelated test run.
        assumeTrue(
            "pass -Pandroid.testInstrumentationRunnerArguments.port=<rust exporter_probe port> " +
                "(start it with `cargo run --example exporter_probe` in construct-veil)",
            portArg != null,
        )
        val port = portArg!!.toInt()
        // 10.0.2.2 is the emulator's alias for the host loopback. With a physical device use
        // `adb reverse tcp:<port> tcp:<port>` and pass host=127.0.0.1.
        val host = args.getString("host") ?: "10.0.2.2"
        // protocols=default lets Conscrypt offer its full default hello (TLS 1.2 + 1.3) — what a
        // production dialer should send to blend (the Safari/1.2+1.3 lesson from iOS). Anything else
        // (the default) pins TLS 1.3-only, which is what gate (a) exporter parity measured.
        val defaultProtocols = args.getString("protocols") == "default"
        // capture=true: gate (b) fingerprint. Point at a dumb TCP catcher (no ServerHello) that only
        // records the raw ClientHello; tolerate the handshake not completing, skip the exporter.
        val captureMode = args.getString("capture") == "true"

        // provider=platform (capture only) uses the device's default JSSE provider = the PLATFORM
        // Conscrypt the Android-app crowd sends — the reference to compare the bundled hello against.
        // Otherwise the bundled Conscrypt explicitly (what a production dialer + gate (a) use).
        val usePlatform = captureMode && args.getString("provider") == "platform"
        val sslContext =
            if (usePlatform) SSLContext.getInstance("TLS")
            else SSLContext.getInstance("TLS", Conscrypt.newProvider())
        sslContext.init(null, arrayOf(acceptAllTrust), SecureRandom())

        val socket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        try {
            socket.soTimeout = 15_000

            if (captureMode) {
                // Gate (b): only need the ClientHello on the wire. The catcher sends no ServerHello,
                // so the handshake never completes — expected; the hello is already captured.
                // protocols=default leaves the provider's own set (TLS 1.2 + 1.3).
                if (!defaultProtocols) socket.enabledProtocols = arrayOf("TLSv1.3")
                // ALPN h2 via the standard SSLParameters API so it works on both providers.
                val params = socket.sslParameters
                params.applicationProtocols = arrayOf("h2")
                socket.sslParameters = params
                Log.i(TAG, "capture  : provider=${if (usePlatform) "platform" else "bundled"} protocols=${socket.enabledProtocols.joinToString(",")}")
                runCatching { socket.startHandshake() }
                Log.i(TAG, "capture  : done — ClientHello sent")
                return
            }

            // Gate (a): bundled Conscrypt, TLS 1.3, exporter parity.
            assertTrue("socket is not a Conscrypt socket", Conscrypt.isConscrypt(socket))
            if (!defaultProtocols) socket.enabledProtocols = arrayOf("TLSv1.3")
            Conscrypt.setApplicationProtocols(socket, arrayOf("h2"))
            socket.startHandshake()

            val protocol = socket.session.protocol
            val alpn = runCatching { Conscrypt.getApplicationProtocol(socket) }.getOrNull()
            Log.i(TAG, "protocol : $protocol")
            Log.i(TAG, "alpn     : $alpn")
            assertEquals("the relay negotiates TLS 1.3", "TLSv1.3", protocol)

            // `ByteArray(0)` == rustls `Some(&[])`: an empty-but-present context.
            val exporter = Conscrypt.exportKeyingMaterial(socket, label, ByteArray(0), exporterLen)
            assertEquals("exporter length", exporterLen, exporter.size)

            val hex = exporter.joinToString("") { "%02x".format(it) }
            Log.i(TAG, "label    : \"$label\"")
            Log.i(TAG, "conscrypt: $hex")
            // Compare this against the rust server's `rust: <hex>` line for the SAME connection.
        } finally {
            runCatching { socket.close() }
        }
    }

    private val acceptAllTrust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val TAG = "VEIL-PROBE"
    }
}
