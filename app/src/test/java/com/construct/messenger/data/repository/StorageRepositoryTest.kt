package com.construct.messenger.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The media store's bounds on real files: what goes, and what is never touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageRepositoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val dir = File(context.filesDir, StorageRepository.MEDIA_DIR)
    // Real time: setLimit and setKeepDays sweep against the clock.
    private val now = System.currentTimeMillis()
    private lateinit var repo: StorageRepository

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        context.getSharedPreferences("storage_prefs", 0).edit().clear().commit()
        repo = StorageRepository(context)
    }

    private fun file(name: String, bytes: Int, daysAgo: Long) = File(dir, name).apply {
        writeBytes(ByteArray(bytes))
        setLastModified(now - daysAgo * DAY)
    }

    @Test
    fun clearRemovesDownloadsButNotOurUnsentMediaOrPartials() = runTest {
        file("aaaa", 100, 1)
        file("bbbb", 100, 20)
        file("local-1234", 100, 1)
        file("cccc.part", 100, 1)
        assertEquals(200L, repo.cachedBytes())
        assertEquals(200L, repo.clear())
        assertEquals(setOf("local-1234", "cccc.part"), dir.list()!!.toSet())
    }

    @Test
    fun theLimitDeletesOnlyWhatTheStoreMayStillHave() = runTest {
        file("fresh", 400, 1)
        file("week-old", 400, 3)
        file("only-copy", 400, 20)
        repo.setLimit(500)
        repo.evictToQuota(now)
        // Both re-downloadable ones go; the only copy stays though the store is still over.
        assertEquals(setOf("only-copy"), dir.list()!!.toSet())
    }

    @Test
    fun keepForRemovesOlderMediaWhenSet() = runTest {
        file("new", 10, 2)
        file("old", 10, 40)
        repo.evictOld(now)
        assertEquals(2, dir.list()!!.size)
        repo.setKeepDays(30)
        repo.evictOld(now)
        assertEquals(setOf("new"), dir.list()!!.toSet())
    }

    @Test
    fun settingsPersistAndDefaultToIos() {
        assertEquals(StorageRepository.DEFAULT_LIMIT_BYTES, repo.settings.value.limitBytes)
        assertEquals(0, repo.settings.value.keepDays)
        runTest { repo.setLimit(0); repo.setKeepDays(7) }
        val again = StorageRepository(context)
        assertEquals(0L, again.settings.value.limitBytes)
        assertEquals(7, again.settings.value.keepDays)
        assertFalse(again.settings.value.limitBytes > 0)
        assertTrue(dir.exists())
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
