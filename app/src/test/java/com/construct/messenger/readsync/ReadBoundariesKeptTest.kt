package com.construct.messenger.readsync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The kept state is the core's CFE in a file; the preference strings of before are moved once (todo 300). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadBoundariesKeptTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Mutation: skip the migration, or keep the preferences after it. */
    @Test
    fun `the preference strings are moved once`() {
        context.getSharedPreferences(ReadBoundaries.LEGACY_READ, Context.MODE_PRIVATE).edit().putString("bob", "10:m").commit()
        context.getSharedPreferences(ReadBoundaries.LEGACY_NEWEST, Context.MODE_PRIVATE).edit().putString("bob", "12:n").commit()
        val boundaries = ReadBoundaries(context)
        assertEquals(ReadSync.Boundary(10, "m"), boundaries.boundary("BOB"))
        assertEquals(ReadSync.Boundary(12, "n"), boundaries.newestIncoming("bob"))
        assertTrue(context.getSharedPreferences(ReadBoundaries.LEGACY_READ, Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(context.getSharedPreferences(ReadBoundaries.LEGACY_NEWEST, Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(File(context.noBackupFilesDir, ReadBoundaries.FILE).exists())
    }

    /** A marker read here and not sent yet survives a restart. Mutation: skip the save. */
    @Test
    fun `a pending marker survives a new instance`() {
        assertTrue(ReadBoundaries(context).noteRead("bob", ReadSync.Boundary(5, "x")))
        val reopened = ReadBoundaries(context)
        assertTrue(reopened.hasPending())
        assertEquals(mapOf("bob" to ReadSync.Boundary(5, "x")), reopened.takePending())
        assertFalse(reopened.hasPending())
    }

    /** Rule 11: reading the preference strings is temporary, and this fails on the day it should be gone. */
    @Test
    fun `the preference reading has an end`() {
        assertTrue(
            "2027-01-31 has passed: remove ReadBoundaries.migrate and this test",
            System.currentTimeMillis() < ReadBoundaries.LEGACY_UNTIL_MS,
        )
    }
}
