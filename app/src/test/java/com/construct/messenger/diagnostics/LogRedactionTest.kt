package com.construct.messenger.diagnostics

import com.construct.messenger.veil.VeilRelay
import android.app.Application
import java.io.File
import java.net.ConnectException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * No release build writes a VEIL front's name (TODO 135): what reaches logcat is a label keyed per
 * device. Robolectric, so the line checked is the one `android.util.Log` received.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LogRedactionTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var keyFile: File

    @Before
    fun setUp() {
        keyFile = File(dir.root, "log_label.key")
        LogRedaction.install(keyFile, plain = false)
        ShadowLog.clear()
    }

    @After
    fun tearDown() = LogRedaction.install(File(dir.root, "other.key"), plain = false)

    private fun logcat(): String = ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable}" }

    /** Mutation: write `msg` instead of the redacted line in `Log.write` — the name is in logcat. */
    @Test
    fun `a front's address, SNI and resolved IP never reach logcat`() {
        val relay = VeilRelay("relay-a.example.net:443", "cdn-b.example.org", "00".repeat(32))
        Log.i("VEIL", "VEIL up: ${relay.address} via VEIL_FRONT in 212ms, local :41000, sni ${relay.sni}")
        Log.w("VEIL", "front failed", ConnectException("failed to connect to relay-a.example.net/203.0.113.9 (port 443)"))

        val written = logcat()
        assertFalse(written, written.contains("relay-a.example.net"))
        assertFalse(written, written.contains("cdn-b.example.org"))
        assertFalse(written, written.contains("203.0.113.9"))
        assertTrue(written, written.contains(LogRedaction.label(relay.address)))
        // What does not name the front stays: method, latency, the local port, the error's type.
        assertTrue(written, written.contains("via VEIL_FRONT in 212ms, local :41000"))
        assertTrue(written, written.contains("ConnectException"))
    }

    @Test
    fun `a name inside an error's cause is redacted too`() {
        LogRedaction.register("relay-c.example.net:8443")
        val error = RuntimeException("outer", IllegalStateException("veil_start: relay-c.example.net:8443 refused"))
        Log.e("VEIL", "start failed", error)
        assertFalse(logcat().contains("relay-c.example.net"))
    }

    @Test
    fun `one device labels a front the same way every time, another device differently`() {
        val first = LogRedaction.label("relay-d.example.net:443")
        assertEquals(first, LogRedaction.label("relay-d.example.net:8443"))
        LogRedaction.install(keyFile, plain = false) // the same device after a restart
        assertEquals(first, LogRedaction.label("relay-d.example.net:443"))
        LogRedaction.install(File(dir.root, "another-device.key"), plain = false)
        assertNotEquals(first, LogRedaction.label("relay-d.example.net:443"))
    }

    @Test
    fun `loopback and lines with no front stay as they are`() {
        val line = "gRPC through 127.0.0.1:41000, stream connected=true"
        assertEquals(line, LogRedaction.redact(line))
    }

    @Test
    fun `a debug build writes names as they are`() {
        LogRedaction.install(keyFile, plain = true)
        LogRedaction.register("relay-e.example.net:443")
        assertEquals("VEIL up: relay-e.example.net:443", LogRedaction.redact("VEIL up: relay-e.example.net:443"))
    }
}
