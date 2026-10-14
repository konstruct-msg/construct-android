package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcCallMediaTest {

    @Test
    fun `the plan's servers become ICE servers with their credentials`() {
        val plan = CallIcePlan.make(listOf("turns:ams.konstruct.cc:5349"), "1700000000:user", "secret", veilOn = false)!!
        val servers = WebRtcCallMedia.iceServers(plan)
        assertEquals(1, servers.size)
        assertEquals(listOf("turns:ams.konstruct.cc:5349"), servers[0].urls)
        assertEquals("1700000000:user", servers[0].username)
        assertEquals("secret", servers[0].password)
    }

    @Test
    fun `a candidate is logged by type and protocol, not by address`() {
        assertEquals("typ relay udp", WebRtcCallMedia.summary("candidate:1 1 UDP 41885439 203.0.113.7 50000 typ relay raddr 0.0.0.0 rport 0"))
        assertEquals("typ host tcp", WebRtcCallMedia.summary("candidate:2 1 TCP 2105458943 192.168.1.2 9 typ host tcptype active"))
    }
}
