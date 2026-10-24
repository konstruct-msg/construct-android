package com.construct.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** `decisions/new-device-alarm-waits-for-cross-signing.md` */
class SecurityNoticeTest {

    /** Rows stored while the new-device alarm existed carry 2. Mutation: give 2 a case again — this reddens. */
    @Test
    fun `a stored new-device notice reads as none`() {
        assertEquals(SecurityNotice.NONE, SecurityNotice.of(2))
    }

    @Test
    fun `the address change still reads as itself`() {
        assertEquals(SecurityNotice.ADDRESS_CHANGED, SecurityNotice.of(1))
    }

    /** iOS stores the same 3 (`SecurityNotice.unvouchedDevice`); a contact row means the same on both. */
    @Test
    fun `the unvouched device is 3, as on iOS`() {
        assertEquals(SecurityNotice.UNVOUCHED_DEVICE, SecurityNotice.of(3))
        assertEquals(ContactTrustAlert.UNVOUCHED_DEVICE, ContactTrustAlert.of(SecurityNotice.UNVOUCHED_DEVICE, KtStatus.FAILED))
    }
}
