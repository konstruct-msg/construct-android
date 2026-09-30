package com.construct.messenger.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A voice note being recorded. **Canon:** iOS `AudioRecorderService` — AAC in MPEG-4 (`.m4a`),
 * 44.1 kHz mono at 64 kbit/s, at most 300 s; the level sampled every 50 ms and mapped from
 * −50…0 dBFS to 0…1; only the last 400 samples kept, and on stop averaged down to 100 (fewer
 * when the note is shorter), which is the waveform the message carries.
 *
 * Android reports the peak amplitude since the last read, not iOS's average power; the scale
 * is the same, the bars run a little taller.
 *
 * The file is in the app's cache while it exists at all: from the first sample until it is sent
 * (then it is sealed and this copy deleted) or discarded.
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface State {
        data object Idle : State
        data class Recording(val durationMs: Long, val recent: List<Float>) : State
        data class Recorded(val file: File, val durationMs: Long, val waveform: List<Float>) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var meter: Job? = null
    private val samples = ArrayDeque<Float>()

    /** Starts; false when the microphone could not be opened. The caller holds RECORD_AUDIO. */
    fun start(): Boolean {
        if (_state.value !is State.Idle) return false
        val out = File(File(context.cacheDir, "voice").apply { mkdirs() }, "voice_${UUID.randomUUID()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(SAMPLE_RATE)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            file = out
            startedAt = System.currentTimeMillis()
            samples.clear()
            _state.value = State.Recording(0, emptyList())
            meter = scope.launch { meterLoop() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "recording did not start", e)
            rec.release()
            out.delete()
            false
        }
    }

    fun stop() {
        val rec = recorder ?: return
        val out = file ?: return
        meter?.cancel()
        val duration = System.currentTimeMillis() - startedAt
        val ok = runCatching { rec.stop() }.isSuccess
        rec.release()
        recorder = null
        _state.value = if (ok && out.length() > 0) {
            State.Recorded(out, duration, waveform(samples.toList()))
        } else {
            out.delete()
            State.Idle
        }
    }

    /** Throw it away, recording or recorded. */
    fun cancel() {
        meter?.cancel()
        recorder?.let { rec -> runCatching { rec.stop() }; rec.release() }
        recorder = null
        (state.value as? State.Recorded)?.file?.delete()
        file?.delete()
        file = null
        _state.value = State.Idle
    }

    /** The recording was handed to the send; the composer is free. The file is the send's now. */
    fun handedOff() {
        file = null
        _state.value = State.Idle
    }

    private suspend fun meterLoop() {
        while (scope.isActive) {
            delay(METER_MS)
            val rec = recorder ?: return
            val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)
            val db = if (amp > 0) 20 * log10(amp / 32767.0) else -160.0
            samples.addLast(((db + 50) / 50).coerceIn(0.0, 1.0).toFloat())
            while (samples.size > TARGET_SAMPLES * 4) samples.removeFirst()
            val duration = System.currentTimeMillis() - startedAt
            if (duration >= MAX_MS) {
                stop()
                return
            }
            _state.value = State.Recording(duration, samples.toList().takeLast(40))
        }
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 64_000
        const val MAX_MS = 300_000L
        private const val METER_MS = 50L
        const val TARGET_SAMPLES = 100

        /** iOS `normalizedWaveform`: averaged down to 100 buckets; a shorter one as it is. */
        fun waveform(raw: List<Float>): List<Float> {
            if (raw.size <= TARGET_SAMPLES) return raw
            val step = raw.size.toFloat() / TARGET_SAMPLES
            return List(TARGET_SAMPLES) { i ->
                val start = (i * step).toInt()
                val end = minOf(((i + 1) * step).toInt(), raw.size)
                raw.subList(start, end).average().toFloat()
            }
        }
    }
}
