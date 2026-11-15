package com.construct.messenger.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shared.proto.signaling.v1.Webrtc.HangupReason
import uniffi.construct_core.CallAnswerDisposition as AnswerDisposition
import uniffi.construct_core.CallHangupDisposition as HangupDisposition

/**
 * Who hears a call signal, and whose signals count. The rule is the core's since todo 295; these
 * tests run it through this app's bridge, so a wrong wire number in [CallRuleWire] turns them red.
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

    @Test
    fun theFirstAnswerWinsAndReleasesTheRest() {
        assertEquals(AnswerDisposition.Accept("mac", listOf("phone", "ipad")), CallDeviceRouting.onAnswer("mac", null, offered))
        assertEquals(AnswerDisposition.ReleaseLate("ipad"), CallDeviceRouting.onAnswer("ipad", "mac", offered))
        // An ICE-restart answer from the chosen device is just an answer.
        assertEquals(AnswerDisposition.Accept("mac", listOf("phone", "ipad")), CallDeviceRouting.onAnswer("mac", "mac", offered))
    }

    @Test
    fun aDeclineAnywhereEndsTheCallAndReleasesTheOthers() {
        assertEquals(
            HangupDisposition.End(listOf("phone", "ipad")),
            CallDeviceRouting.onHangup("mac", HangupReason.HANGUP_REASON_DECLINED, null, offered),
        )
    }

    @Test
    fun busyTakesOutOneDeviceUntilAllAreBusy() {
        assertEquals(
            HangupDisposition.DropDevice(listOf("phone", "ipad")),
            CallDeviceRouting.onHangup("mac", HangupReason.HANGUP_REASON_BUSY, null, offered),
        )
        assertEquals(
            HangupDisposition.End(emptyList()),
            CallDeviceRouting.onHangup("ipad", HangupReason.HANGUP_REASON_BUSY, null, listOf("ipad")),
        )
    }

    @Test
    fun afterAnAnswerAHangupFromAnotherDeviceIsIgnored() {
        assertEquals(HangupDisposition.Ignore, CallDeviceRouting.onHangup("ipad", HangupReason.HANGUP_REASON_NORMAL, "mac", offered))
        assertEquals(HangupDisposition.End(emptyList()), CallDeviceRouting.onHangup("mac", HangupReason.HANGUP_REASON_NORMAL, "mac", offered))
    }

    /** Mutation: route `ACCEPTED_ELSEWHERE` as another reason — the ringing would not stop. */
    @Test
    fun takenOnAnotherOfOurDevicesStopsTheRingingAndNothingElse() {
        assertEquals(
            HangupDisposition.EndTakenElsewhere,
            CallDeviceRouting.onHangup("mac", HangupReason.HANGUP_REASON_ACCEPTED_ELSEWHERE, "mac", emptyList()),
        )
        assertEquals(
            HangupDisposition.Ignore,
            CallDeviceRouting.onHangup("ipad", HangupReason.HANGUP_REASON_ACCEPTED_ELSEWHERE, "mac", emptyList()),
        )
    }
}
