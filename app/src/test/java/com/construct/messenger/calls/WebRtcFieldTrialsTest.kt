package com.construct.messenger.calls

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-quantum DTLS trial is a string the linked WebRTC reads at start-up. A WebRTC update
 * that renamed or dropped it would fail nowhere: calls would quietly key their media with X25519
 * alone. **Canon:** iOS `WebRTCFieldTrialsTests`, which reads its xcframework the same way.
 *
 * The AAR is handed in by the build (`webrtc.aar`, app/build.gradle).
 */
class WebRtcFieldTrialsTest {

    private fun library(abi: String): ByteArray {
        val aar = System.getProperty("webrtc.aar") ?: error("webrtc.aar not set — run through Gradle")
        ZipFile(File(aar)).use { zip ->
            val entry = zip.getEntry("jni/$abi/libjingle_peerconnection_so.so") ?: error("no WebRTC for $abi")
            return zip.getInputStream(entry).readBytes()
        }
    }

    private fun ByteArray.contains(text: String): Boolean =
        String(this, Charsets.ISO_8859_1).contains(text)

    @Test fun `every shipped WebRTC knows the post-quantum DTLS trial and its group`() {
        val shipped = System.getProperty("shipped.abis")?.split(",") ?: error("shipped.abis not set — run through Gradle")
        for (abi in shipped) {
            val lib = library(abi)
            assertTrue("$abi: WebRTC no longer knows ${WebRtcRuntime.DTLS_PQC_KEY}", lib.contains(WebRtcRuntime.DTLS_PQC_KEY))
            assertTrue("$abi: no X25519MLKEM768 in its BoringSSL", lib.contains("X25519MLKEM768"))
        }
    }

    @Test fun `the trial is switched on, in WebRTC's own syntax`() =
        assertEquals("WebRTC-EnableDtlsPqc/Enabled/", WebRtcRuntime.FIELD_TRIALS)
}
