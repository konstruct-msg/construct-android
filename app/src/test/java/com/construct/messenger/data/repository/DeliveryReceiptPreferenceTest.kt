package com.construct.messenger.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.stealth.StealthPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings → Security → Delivery receipts. **Canon:** iOS `DeliveryReceiptPreferenceTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeliveryReceiptPreferenceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val stealth: StealthPolicy = mock()

    @Before
    fun setUp() {
        context.getSharedPreferences("security_prefs", 0).edit().clear().commit()
    }

    /** Mutation: default false — every device would stop telling senders. */
    @Test
    fun `receipts are sent unless the person turned them off`() {
        assertTrue(SecuritySettingsRepository(context, stealth).sendsDeliveryReceipts.value)
    }

    /** Mutation: skip the write — the choice is gone after a restart. */
    @Test
    fun `off stays off across a restart, and on again`() {
        SecuritySettingsRepository(context, stealth).setSendsDeliveryReceipts(false)
        val after = SecuritySettingsRepository(context, stealth)
        assertFalse(after.sendsDeliveryReceipts.value)
        after.setSendsDeliveryReceipts(true)
        assertTrue(SecuritySettingsRepository(context, stealth).sendsDeliveryReceipts.value)
    }
}
