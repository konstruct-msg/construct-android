package com.construct.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCopyRouteTest {

    @Test
    fun `parses recipient and own replica suffixes`() {
        assertEquals(
            DeviceCopyRoute.Audience.RECIPIENT,
            DeviceCopyRoute.parse("base-id-fd-0123456789abcdef")?.audience,
        )
        assertEquals(
            DeviceCopyRoute.Audience.OWN_REPLICA,
            DeviceCopyRoute.parse("base-id-ss-01234567-c3")?.audience,
        )
        assertEquals("base-id", DeviceCopyRoute.parse("base-id-ss-01234567-c3")?.baseMessageId)
    }

    @Test
    fun `does not classify ordinary message ids as device copies`() {
        assertNull(DeviceCopyRoute.parse("550e8400-e29b-41d4-a716-446655440000"))
    }
}
