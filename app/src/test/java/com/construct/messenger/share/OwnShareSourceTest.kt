package com.construct.messenger.share

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The own-file entry of the share draft reads only the log export, nothing else this app keeps. */
class OwnShareSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun exportDir() = tmp.newFolder("logs-export")

    @Test
    fun aFileDirectlyInTheExportDirectoryIsAccepted() {
        val dir = exportDir()
        val archive = File(dir, "construct-logs-1.txt").apply { writeText("log") }
        assertTrue(OwnShareSource.isExport(archive, dir))
    }

    @Test
    fun aPrivateFileOutsideTheExportDirectoryIsRefused() {
        val dir = exportDir()
        val secret = tmp.newFile("session.blob").apply { writeText("key") }
        assertFalse(OwnShareSource.isExport(secret, dir))
        assertFalse(OwnShareSource.isExport(File(dir, "../session.blob"), dir))
    }

    @Test
    fun aFileInASubdirectoryIsRefused() {
        val dir = exportDir()
        val nested = File(File(dir, "inner").apply { mkdirs() }, "x.txt").apply { writeText("x") }
        assertFalse(OwnShareSource.isExport(nested, dir))
    }

    @Test
    fun aLinkInTheExportDirectoryToAPrivateFileIsRefused() {
        val dir = exportDir()
        val secret = tmp.newFile("identity.key").apply { writeText("key") }
        val link = File(dir, "construct-logs-2.txt")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertFalse(OwnShareSource.isExport(link, dir))
    }

    @Test
    fun aMissingFileOrTheDirectoryItselfIsRefused() {
        val dir = exportDir()
        assertFalse(OwnShareSource.isExport(File(dir, "gone.txt"), dir))
        assertFalse(OwnShareSource.isExport(File(File(dir, "sub").apply { mkdirs() }.path), dir))
    }
}
