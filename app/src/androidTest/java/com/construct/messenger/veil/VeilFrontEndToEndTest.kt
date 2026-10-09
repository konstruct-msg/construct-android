package com.construct.messenger.veil

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end drive of [VeilFrontExternalDialer] against a real veil-front relay — the whole new
 * native-TLS tract at once: Conscrypt dial + SPKI pin + TLS exporter + socketpair ferry +
 * `veil_front_ferry_fd` + the relay's exporter-bound AUTH. Bypasses the app's account/router (not
 * the thing under test).
 *
 * `start()` returning a port already proves reachability + pin + handshake + exporter against the
 * relay. Then it opens the local gRPC port and writes an HTTP/2 preface, which makes the dialer
 * accept → dial a relay session → call the ferry → Rust sends the AUTH record. **The verdict is in
 * the RELAY log:** `capability (v2) valid, routing to tunnel` = AUTH passed (exporter matched end
 * to end); `… invalid … routing to site` = it did not.
 *
 * Run (relay on the host, reachable from the emulator at 10.0.2.2):
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.construct.messenger.veil.VeilFrontEndToEndTest \
 *   -Pandroid.testInstrumentationRunnerArguments.relay=10.0.2.2:8443 \
 *   -Pandroid.testInstrumentationRunnerArguments.sni=test.local \
 *   -Pandroid.testInstrumentationRunnerArguments.spki=<relay spki sha256 hex> \
 *   -Pandroid.testInstrumentationRunnerArguments.capability=<bearer capability b64>
 * ```
 */
@RunWith(AndroidJUnit4::class)
class VeilFrontEndToEndTest {

    @Test
    fun dialerBringsUpTunnelAndRelayAcceptsAuth() {
        val args = InstrumentationRegistry.getArguments()
        val relayAddr = args.getString("relay")
        val spki = args.getString("spki")
        val capability = args.getString("capability")
        assumeTrue(
            "pass relay=, spki=, capability= (and sni=) — see the class doc / runbook",
            relayAddr != null && spki != null && capability != null,
        )
        val sni = args.getString("sni") ?: relayAddr!!.substringBeforeLast(':')

        val dialer = VeilFrontExternalDialer()
        val port = dialer.start(VeilRelay(relayAddr!!, sni, spki!!), capability!!, "", "")
        Log.i(TAG, "dialer.start → local port $port")
        // Non-null ⇒ validation dial succeeded: Conscrypt reached the relay, the SPKI pin matched,
        // the handshake completed, and the exporter was derived. (Null ⇒ pin/reachability failure.)
        assertNotNull("dialer.start returned null — relay unreachable or SPKI pin mismatch", port)

        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port!!), 5000)
                // An HTTP/2 client preface — realistic first bytes of a gRPC stream; it makes the
                // dialer accept and the ferry fire (the AUTH record goes out regardless).
                val out = s.getOutputStream()
                // Gate (d): flood the local side so MANY bucket-padded DATA frames queue in the
                // socketpair at once — the worst case for the bulk pump. If our pump coalesces, the
                // UP TLS records come out NOT bucket-aligned (sums of buckets / odd sizes); if it
                // preserves boundaries, every UP record is exactly one LENGTH_BUCKET + overhead.
                runCatching {
                    out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    repeat(6) {
                        out.write(ByteArray(16000) { 'x'.code.toByte() })
                        out.flush()
                    }
                }
                s.soTimeout = 2000
                val got = runCatching { s.getInputStream().read() }.getOrDefault(-1)
                Log.i(TAG, "local tunnel first byte read = $got")
            }
            // Give the AUTH record time to reach the relay and be logged.
            Thread.sleep(2000)
        } finally {
            dialer.stop()
        }
    }

    private companion object {
        const val TAG = "VEIL-E2E"
    }
}
