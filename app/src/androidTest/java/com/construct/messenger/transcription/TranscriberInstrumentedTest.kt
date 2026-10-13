package com.construct.messenger.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path on a device (TODO 116): the tiny model from Hugging Face, checked against its
 * pinned SHA-256, the platform decoder, whisper.cpp through JNI. `jfk.wav` is whisper.cpp's own
 * sample (samples/jfk.wav): "…ask not what your country can do for you…". Needs the network once.
 */
@RunWith(AndroidJUnit4::class)
class TranscriberInstrumentedTest {
    @Test
    fun theSampleReadsAsWhatWasSaid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        val models = WhisperModels(context)
        assertTrue("the tiny model downloads and matches its digest", models.download(WhisperModel.TINY))
        val outcome = Transcriber(models).transcribe(audio, language = "en", model = WhisperModel.TINY)
        val done = outcome as? Transcriber.Outcome.Done ?: error("not transcribed: $outcome")
        android.util.Log.i("TranscriberTest", "heard: ${done.transcript.text}")
        assertTrue(done.transcript.text, done.transcript.text.contains("country", ignoreCase = true))
    }
}
