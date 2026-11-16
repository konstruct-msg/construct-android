package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who hears a call signal, and whose signals count. The rule is the core's since todo 295; these
 * tests run it through this app's bridge. Answers and hangups are the call machine's
 * (`CallManagerTest` drives them).
 */
class CallDeviceRoutingTest {
    private val offered = listOf("phone", "mac", "ipad")

    @Test
    fun targetsAreTheChosenDeviceThenTheOfferedThenThePinned() {
        assertEquals(listOf("mac"), CallDeviceRouting.targets("mac", offered, "phone"))
        assertEquals(offered, CallDeviceRouting.targets(null, offered, "phone"))
        assertEquals(listOf("phone"), CallDeviceRouting.targets(null, emptyList(), "phone"))
        assertEquals(emptyList<String>(), CallDeviceRouting.targets(null, emptyList(), null))
    }

    @Test
    fun anOfferGoesToPlannedDevicesWithASessionInPlanOrder() {
        val sessions = setOf("mac", "phone")
        assertEquals(listOf("phone", "mac"), CallDeviceRouting.offerDevices(listOf("phone", "ipad", "mac", "phone"), sessions::contains, "phone"))
        assertEquals(listOf("pinned"), CallDeviceRouting.offerDevices(listOf("ipad"), sessions::contains, "pinned"))
    }

    @Test
    fun afterAnAnswerOnlyTheChosenDeviceSpeaks() {
        assertTrue(CallDeviceRouting.admits("mac", null))
        assertTrue(CallDeviceRouting.admits("mac", "mac"))
        assertFalse(CallDeviceRouting.admits("ipad", "mac"))
    }
}
