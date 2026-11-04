package com.construct.messenger.diagnostics

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * What a release build carries. Unit tests run the debug variant, so what differs in release is
 * held here two ways: the behaviour with the switch off, and the sources that set the switch.
 * Canon: iOS `scripts/check_build_conditions.py`, `decisions/tester-builds-are-release-plus-logs`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReleaseBuildSurfaceTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val sources by lazy {
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private fun filesContaining(text: String): Set<String> =
        sources.filter { it.readText().contains(text) }.map { it.name }.toSet()

    @Before
    fun setUp() = ShadowLog.clear()

    @After
    fun tearDown() {
        Log.toLogcat = true
        Diagnostics.collector = null
    }

    @Test
    fun `with logcat off a line reaches the file and not logcat`() {
        Log.toLogcat = false
        Diagnostics.collector = LogCollector(dir.root, enabled = true)

        Log.i("TAG", "a line for the log")

        assertEquals(emptyList<Any>(), ShadowLog.getLogs())
        assertTrue(Diagnostics.collector!!.recentLines(1).single().contains("a line for the log"))
    }

    @Test
    fun `logcat is on only in a debug build`() {
        val log = sources.single { it.name == "Log.kt" && it.parentFile.name == "diagnostics" }.readText()
        assertTrue(log.contains("internal var toLogcat: Boolean = BuildConfig.DEBUG\n"))
    }

    /** Past `diagnostics.Log` a line skips the switch above, and the redaction of a front's name. */
    @Test
    fun `only the log itself calls android's log`() {
        assertEquals(setOf("Log.kt"), filesContaining("android.util.Log"))
    }

    /** A new tester surface is the owner's decision, recorded here. */
    @Test
    fun `a tester's build differs from the public one only at the listed places`() {
        assertEquals(
            setOf(
                "Diagnostics.kt", // the log file and the screen that exports it
                "SettingsScreen.kt", // the BETA label
                "DeviceLinkOfferPolicy.kt", // linking, until the owner moves it to release
            ),
            filesContaining("BuildConfig.INTERNAL_TOOLS"),
        )
    }
}
