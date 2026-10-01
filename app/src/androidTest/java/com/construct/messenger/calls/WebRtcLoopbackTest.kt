package com.construct.messenger.calls

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two [WebRtcCallMedia] in one process, wired to each other by hand: offer, answer, candidates.
 * What no unit test can show — that this WebRTC build negotiates our configuration, finishes the
 * DTLS handshake with the PQC trial on, and reports the peer connection connected. No server.
 *
 * Run it on an emulator only: `connectedAndroidTest` uninstalls the app afterwards, account and all.
 */
@RunWith(AndroidJUnit4::class)
class WebRtcLoopbackTest {

    private class Side(val name: String) : CallMedia.Listener {
        lateinit var other: () -> CallMedia
        val connected = CountDownLatch(1)
        val candidates = mutableListOf<CallIce>()
        override fun onLocalCandidate(candidate: CallIce) {
            synchronized(candidates) { candidates += candidate }
        }
        override fun onConnected() = connected.countDown()
        override fun onFailed() = Unit
        override fun onQuality(quality: CallQuality) = Unit
    }

    @Test
    fun twoPeersConnect() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val factory = WebRtcCallMedia.Factory(context, CallAudio(context))

        val callerSide = Side("caller")
        val calleeSide = Side("callee")
        val caller = factory.create(CallMedia.Role.CALLER, null, callerSide)
        val callee = factory.create(CallMedia.Role.CALLEE, null, calleeSide)
        try {
            callee.setRemoteOffer(caller.createOffer())
            caller.setRemoteAnswer(callee.createAnswer())
            // Trickle until both say connected: whatever each gathered goes to the other.
            val deadline = System.currentTimeMillis() + 20_000
            var sentCaller = 0
            var sentCallee = 0
            while (System.currentTimeMillis() < deadline &&
                (callerSide.connected.count > 0 || calleeSide.connected.count > 0)
            ) {
                val fromCaller = synchronized(callerSide.candidates) { callerSide.candidates.drop(sentCaller) }
                fromCaller.forEach { callee.addRemoteCandidate(it) }
                sentCaller += fromCaller.size
                val fromCallee = synchronized(calleeSide.candidates) { calleeSide.candidates.drop(sentCallee) }
                fromCallee.forEach { caller.addRemoteCandidate(it) }
                sentCallee += fromCallee.size
                Thread.sleep(100)
            }
            assertTrue("caller connected", callerSide.connected.await(1, TimeUnit.SECONDS))
            assertTrue("callee connected", calleeSide.connected.await(1, TimeUnit.SECONDS))
        } finally {
            caller.close()
            callee.close()
        }
    }
}
