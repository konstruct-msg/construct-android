package com.construct.messenger.transcription

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** The parts of on-device transcription that run without the native engine (TODO 116). */
class TranscriptionTest {
    @get:Rule val dir = TemporaryFolder()

    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun store(bytes: ByteArray, pinned: String) =
        WhisperModels(dir.root.resolve("models"), { bytes.inputStream() }, { pinned })

    @Test
    fun aModelWithThePinnedDigestIsKept() = runTest {
        val models = store(payload, payloadSha)
        assertTrue(models.download(WhisperModel.TINY))
        assertArrayEquals(payload, models.file(WhisperModel.TINY).readBytes())
        assertEquals(WhisperModels.State.Installed, models.states.value[WhisperModel.TINY])
        assertEquals(listOf(WhisperModel.TINY), models.installed())
    }

    /** A file that is not the pinned one is never kept, so never loaded. Mutation: skip the check — reddens. */
    @Test
    fun aModelWithAnotherDigestIsDeleted() = runTest {
        val models = store(payload, "0".repeat(64))
        assertFalse(models.download(WhisperModel.TINY))
        assertFalse(models.file(WhisperModel.TINY).exists())
        assertFalse(dir.root.resolve("models/ggml-tiny.bin.part").exists())
        assertEquals(WhisperModels.State.Failed, models.states.value[WhisperModel.TINY])
        assertTrue(models.installed().isEmpty())
    }

    @Test
    fun aDeletedModelIsGone() = runTest {
        val models = store(payload, payloadSha)
        models.download(WhisperModel.BASE)
        models.delete(WhisperModel.BASE)
        assertFalse(models.file(WhisperModel.BASE).exists())
        assertEquals(WhisperModels.State.Absent, models.states.value[WhisperModel.BASE])
    }

    @Test
    fun theUrlsArePinnedToARevision() {
        WhisperModel.entries.forEach {
            assertTrue(it.url, it.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/${WhisperModel.REVISION}/"))
            assertEquals(64, it.sha256.length)
        }
    }

    @Test
    fun stereoIsAveragedToMono() {
        val mono = PcmDecoder.toMono(shortArrayOf(16384, -16384, 32767, 32767), channels = 2)
        assertEquals(2, mono.size)
        assertEquals(0f, mono[0], 1e-4f)
        assertEquals(1f, mono[1], 1e-3f)
    }

    @Test
    fun resamplingKeepsTheLengthInTime() {
        val second = FloatArray(44_100) { it / 44_100f }
        val out = PcmDecoder.resample(second, 44_100, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(0.5f, out[8_000], 1e-3f)
        assertTrue(PcmDecoder.resample(second, 16_000, 16_000) === second)
    }

    @Test
    fun theBridgesAnswerIsReadAsLanguageAndText() {
        assertEquals(Transcriber.Transcript("привет мир", "ru"), Transcriber.parse("ru\n привет мир"))
        assertNull(Transcriber.parse("\n hello").language)
        assertEquals("hello", Transcriber.parse("\n hello").text)
    }
}
