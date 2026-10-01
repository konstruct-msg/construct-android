package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Test
import shared.proto.signaling.v1.Webrtc.TurnCredentials

class WebRtcCallMediaTest {

    @Test
    fun `with TURN credentials only the TURN servers are used`() {
        val turn = TurnCredentials.newBuilder()
            .addUrls("turn:ams.konstruct.cc:3478?transport=udp")
            .addUrls("turns:ams.konstruct.cc:5349")
            .setUsername("1700000000:user").setCredential("secret").build()
        val servers = WebRtcCallMedia.iceServers(turn)
        assertEquals(1, servers.size)
        assertEquals(turn.urlsList, servers[0].urls)
        assertEquals("1700000000:user", servers[0].username)
        assertEquals("secret", servers[0].password)
    }

    @Test
    fun `without them, our own STUN and never a public one`() {
        for (turn in listOf(null, TurnCredentials.getDefaultInstance())) {
            val urls = WebRtcCallMedia.iceServers(turn).flatMap { it.urls }
            assertEquals(listOf("stun:ams.konstruct.cc:3478"), urls)
        }
    }

    @Test
    fun `a candidate is logged by type and protocol, not by address`() {
        assertEquals("typ relay udp", WebRtcCallMedia.summary("candidate:1 1 UDP 41885439 203.0.113.7 50000 typ relay raddr 0.0.0.0 rport 0"))
        assertEquals("typ host tcp", WebRtcCallMedia.summary("candidate:2 1 TCP 2105458943 192.168.1.2 9 typ host tcptype active"))
    }
}
