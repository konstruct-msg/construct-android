package com.construct.messenger.media

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.construct.messenger.diagnostics.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records a video note: camera and microphone into one file, with pause and a camera switch in
 * the middle. **Canon:** iOS `VideoNoteRecorder` — front camera first, 60 s at most, one encode
 * at send (the centre 3:4 at 720×960, [VideoPreparer.prepareNote]), which also strips metadata.
 *
 * As on iOS, a pause ends a segment and resuming starts the next: an open MP4 cannot be played
 * back, and the review on pause plays what was recorded. The send joins them in its one encode.
 * A camera switch does not split a segment — a persistent recording survives the rebind. The viewfinder fills a 3:4 view
 * from the same 4:3 frames, so what is sent is what was seen.
 *
 * The file lives in the app's cache, never in shared storage, and is deleted once sent or dropped.
 */
class VideoNoteRecorder(private val context: Context) {
    /** [STOPPING]: a segment is being closed — between a pause or send and its file landing. */
    enum class Phase { IDLE, RECORDING, STOPPING, PAUSED, LIMIT, FAILED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Recorded time, pauses excluded. */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** Closed segments, in order: what the review plays and the send joins. */
    private val _segments = MutableStateFlow<List<VideoNoteTake.Segment>>(emptyList())
    val segments: StateFlow<List<VideoNoteTake.Segment>> = _segments.asStateFlow()

    private val _front = MutableStateFlow(true)
    val front: StateFlow<Boolean> = _front.asStateFlow()

    private val ratio = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()
    private val preview = Preview.Builder().setResolutionSelector(ratio).build()
    private val recorder = Recorder.Builder()
        // 720p is all a note sends (720×960 after the crop); more would only be encoded away.
        .setQualitySelector(QualitySelector.from(Quality.HD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .setAspectRatio(AspectRatio.RATIO_4_3)
        .build()
    // What the front camera showed is what goes out: mirrored, as the viewfinder was.
    private val capture = VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var recording: Recording? = null
    private var file: File? = null
    private var finalized: CompletableDeferred<Boolean>? = null

    /** Opens the camera into [surface] and starts recording at once (iOS: as soon as it is up). */
    fun start(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        this.owner = owner
        // One note at a time: any other note file was left by a process that died mid-way.
        dir().listFiles { f -> f.name.startsWith(NOTE_PREFIX) }?.forEach { it.delete() }
        preview.setSurfaceProvider(surface)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                bind()
                record()
            } catch (e: Exception) {
                Log.w(TAG, "camera did not open", e)
                _phase.value = Phase.FAILED
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind() {
        val p = provider ?: return
        val o = owner ?: return
        val selector = if (_front.value) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        p.unbindAll()
        p.bindToLifecycle(o, selector, preview, capture)
    }

    @SuppressLint("MissingPermission") // The screen holds CAMERA and RECORD_AUDIO before it opens this.
    @OptIn(ExperimentalPersistentRecording::class)
    private fun record() {
        val left = MAX_DURATION_MS - recordedMs()
        if (left <= 0) {
            _phase.value = Phase.LIMIT
            return
        }
        val out = File(dir(), "$NOTE_PREFIX${UUID.randomUUID()}.mp4")
        file = out
        val done = CompletableDeferred<Boolean>()
        finalized = done
        val options = FileOutputOptions.Builder(out).setDurationLimitMillis(left).build()
        recording = capture.output.prepareRecording(context, options)
            .withAudioEnabled()
            // Survives the rebind a camera switch takes.
            .asPersistentRecording()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> _phase.value = Phase.RECORDING
                    is VideoRecordEvent.Status ->
                        _elapsedMs.value = recordedMs() + event.recordingStats.recordedDurationNanos / 1_000_000
                    is VideoRecordEvent.Finalize -> {
                        val ms = event.recordingStats.recordedDurationNanos / 1_000_000
                        val limit = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                        val ok = !event.hasError() || limit
                        if (ok && out.exists() && out.length() > 0 && ms > 0) {
                            _segments.value = _segments.value + VideoNoteTake.Segment(out, ms)
                        } else {
                            if (!ok) Log.w(TAG, "segment ended with error ${event.error}")
                            out.delete()
                        }
                        _elapsedMs.value = recordedMs()
                        _phase.value = when {
                            _phase.value == Phase.FAILED -> Phase.FAILED
                            limit || recordedMs() >= MAX_DURATION_MS -> Phase.LIMIT
                            else -> Phase.PAUSED
                        }
                        recording = null
                        file = null
                        done.complete(ok)
                    }
                }
            }
    }

    /**
     * Ends the current segment: the file is closed and can be played back — the review shows it
     * (iOS: pausing ends a segment). [Phase.PAUSED] once it has landed.
     */
    fun pause() {
        if (_phase.value != Phase.RECORDING) return
        _phase.value = Phase.STOPPING
        recording?.stop()
    }

    /** A new segment; the trim is the screen's and is dropped there. */
    fun resume() {
        if (_phase.value == Phase.PAUSED) record()
    }

    /** The other camera, mid-segment: a persistent recording survives the rebind. */
    fun switchCamera() {
        _front.value = !_front.value
        runCatching { bind() }.onFailure {
            Log.w(TAG, "camera switch failed", it)
            _front.value = !_front.value
            runCatching { bind() }
        }
    }

    /** Stops and hands over the segments, or null when less than a moment was recorded. */
    suspend fun finish(): VideoNoteTake? {
        recording?.stop()
        finalized?.await()
        release()
        val take = VideoNoteTake(_segments.value)
        _segments.value = emptyList()
        if (take.segments.isEmpty() || take.durationMs < MIN_DURATION_MS) {
            take.delete()
            return null
        }
        return take
    }

    /** Drops the recording and every segment. */
    fun cancel() {
        val pending = finalized
        recording?.stop()
        release()
        // A segment still finalising is added when it lands; delete after that too.
        pending?.invokeOnCompletion { _segments.value.forEach { it.file.delete() }; _segments.value = emptyList() }
        _segments.value.forEach { it.file.delete() }
        _segments.value = emptyList()
        file?.delete()
    }

    private fun recordedMs(): Long = _segments.value.sumOf { it.durationMs }

    private fun dir() = File(context.cacheDir, "video").apply { mkdirs() }

    private fun release() {
        runCatching { provider?.unbindAll() }
        owner = null
    }

    companion object {
        private const val TAG = "VideoNoteRecorder"
        private const val NOTE_PREFIX = "note_"

        /** The longest note (iOS `maxDuration`). Longer goes through attachments as a video. */
        const val MAX_DURATION_MS = 60_000L

        /** Shorter is a slip of the finger, not a note. */
        const val MIN_DURATION_MS = 500L
    }
}
