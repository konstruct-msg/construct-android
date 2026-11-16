package com.construct.messenger.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS `CallOfferOrderingTests` and `CallSetupHolesTests`, for the rules Android has a carrier for.
 * The offer, stream-closed and hangup-route rules are the core's: [CallRuleWireTest].
 */
class CallRulesTest {

    @Test
    fun `an empty offer is not an offer`() {
        assertFalse(CallRules.offerSdpIsUsable(null))
        assertFalse(CallRules.offerSdpIsUsable(""))
        assertTrue(CallRules.offerSdpIsUsable("v=0"))
    }

}
