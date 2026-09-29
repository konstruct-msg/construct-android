package com.construct.messenger.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Canon: iOS `LogCollector` — the same file ring, and the same reasons for each rule. */
class LogCollectorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "logs")
    private fun collector(maxBytes: Long = LogCollector.MAX_FILE_BYTES, enabled: Boolean = true) =
        LogCollector(dir(), enabled, maxFileBytes = maxBytes)

    @Test
    fun `lines reach the file in order`() {
        val c = collector()
        c.append("INFO", "Test", "one")
        c.append("WARN", "Test", "two")
        c.flush()
        val text = File(dir(), "current.log").readText()
        assertTrue(text.indexOf("[INFO] [Test] one") in 0 until text.indexOf("[WARN] [Test] two"))
    }

    /**
     * A relaunch keeps the session before it — the one a crash needs. Mutation: write the header
     * over `current.log` without rotating — the old line is gone.
     */
    @Test
    fun `the previous session is set aside at launch`() {
        collector().apply { append("INFO", "Old", "before the crash"); flush() }
        collector()
        assertTrue(File(dir(), "1.log").readText().contains("before the crash"))
        assertFalse(File(dir(), "current.log").readText().contains("before the crash"))
    }

    @Test
    fun `an empty session is not worth a slot`() {
        assertFalse(LogCollector.shouldPreserveSession(null))
        assertFalse(LogCollector.shouldPreserveSession(0))
        assertTrue(LogCollector.shouldPreserveSession(1))
    }

    /** Mutation: never delete the oldest — a fourth file appears. */
    @Test
    fun `three rotated files at most`() {
        repeat(6) { i -> collector().apply { append("INFO", "S", "session $i"); flush() } }
        val names = dir().list()!!.toSet()
        assertEquals(setOf("current.log", "1.log", "2.log", "3.log"), names)
        assertTrue(File(dir(), "current.log").readText().contains("session 5"))
        assertTrue(File(dir(), "1.log").readText().contains("session 4"))
        assertTrue(File(dir(), "3.log").readText().contains("session 2"))
    }

    @Test
    fun `a full file rotates`() {
        val c = collector(maxBytes = 200)
        repeat(20) { c.append("INFO", "Big", "x".repeat(40)) }
        c.flush()
        assertTrue(File(dir(), "1.log").exists())
    }

    /** Crash reports lead the archive; sessions follow oldest first. */
    @Test
    fun `the archive puts crashes first and sessions oldest first`() {
        collector().apply { append("INFO", "S", "older"); flush() }
        val c = collector()
        c.append("INFO", "S", "newer")
        c.recordCrash("main", IllegalStateException("boom"))
        val text = c.createArchive(File(tmp.root, "out"), "Device: test\n").readText()

        val crash = text.indexOf("=== CRASH REPORTS ===")
        val older = text.indexOf("older")
        // The crash report quotes "newer" too; the session's copy is the last one.
        val newer = text.lastIndexOf("newer")
        assertTrue("crash first", crash in 0 until older)
        assertTrue("oldest session before the newest", older < newer)
        assertTrue(text.contains("IllegalStateException: boom"))
    }

    /** The crash report carries the lines still queued when the process died. */
    @Test
    fun `a crash report carries the recent lines`() {
        val c = collector()
        c.append("INFO", "S", "last words")
        c.recordCrash("worker", RuntimeException("x"))
        assertTrue(File(dir(), "crashes.log").readText().contains("last words"))
    }

    @Test
    fun `clearing leaves a header and no crash report`() {
        val c = collector()
        c.append("INFO", "S", "secret-ish")
        c.recordCrash("main", RuntimeException("x"))
        c.clear()
        assertFalse(File(dir(), "crashes.log").exists())
        val text = File(dir(), "current.log").readText()
        assertFalse(text.contains("secret-ish"))
        assertTrue(text.contains("Log Session"))
    }

    /** A release build keeps nothing. Mutation: drop the `enabled` guard in append. */
    @Test
    fun `disabled writes nothing anywhere`() {
        val c = collector(enabled = false)
        c.append("INFO", "S", "line")
        c.recordCrash("main", RuntimeException("x"))
        c.flush()
        assertFalse(dir().exists())
        assertTrue(c.recentLines(10).isEmpty())
    }
}
