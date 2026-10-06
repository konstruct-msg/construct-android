package com.construct.messenger.calls

import com.construct.messenger.diagnostics.Log
import java.util.concurrent.CopyOnWriteArraySet
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Whether frames actually flow — the camera's out, the peer's in. **Canon:** iOS
 * `VideoFrameCounter`. Without it iOS spent two days guessing whether a black window was the
 * camera, the network or the screen: the first frame is logged, then the rate every five seconds,
 * and at the end the total, or "none arrived".
 */
class VideoFrameCounter(private val label: String, private val nowNs: () -> Long = System::nanoTime) {
    private var total = 0L
    private var windowFrames = 0L
    private var windowStartNs = 0L
    private var startedNs = 0L

    @Synchronized
    fun count() {
        val now = nowNs()
        if (total == 0L) {
            startedNs = now
            windowStartNs = now
            Log.i(TAG, "VIDEO[$label] first frame")
        }
        total += 1
        windowFrames += 1
        val elapsed = now - windowStartNs
        if (elapsed >= WINDOW_NS) {
            Log.i(TAG, "VIDEO[$label] %.1f fps".format(java.util.Locale.ROOT, windowFrames * 1e9 / elapsed))
            windowFrames = 0
            windowStartNs = now
        }
    }

    @Synchronized
    fun summary(): String =
        if (total == 0L) "VIDEO[$label] 0 frames — none arrived"
        else "VIDEO[$label] $total frames over %.1f s".format(java.util.Locale.ROOT, (nowNs() - startedNs) / 1e9)

    private companion object {
        const val TAG = "CallMedia"
        const val WINDOW_NS = 5_000_000_000L
    }
}

/**
 * One side's frames, handed to whoever draws them. Attached to a track once, it keeps its sinks
 * across the track's arrival and a camera turned off and on — the screen adds a sink whenever it
 * likes, and a track holding the screen's view directly would have to be found and detached by it.
 */
class VideoSinkHub(label: String) : VideoSink {
    val counter = VideoFrameCounter(label)
    private val sinks = CopyOnWriteArraySet<VideoSink>()

    fun add(sink: VideoSink) = sinks.add(sink)

    fun remove(sink: VideoSink) = sinks.remove(sink)

    override fun onFrame(frame: VideoFrame) {
        counter.count()
        sinks.forEach { it.onFrame(frame) }
    }
}

/** The camera's frames on their way to the video source, counted. */
class CountingCapturerObserver(private val target: CapturerObserver, val counter: VideoFrameCounter) : CapturerObserver {
    override fun onCapturerStarted(success: Boolean) = target.onCapturerStarted(success)

    override fun onCapturerStopped() = target.onCapturerStopped()

    override fun onFrameCaptured(frame: VideoFrame) {
        counter.count()
        target.onFrameCaptured(frame)
    }
}

/** Frames handed on to whoever is drawing them now, if anyone. */
class VideoFanOut : VideoSink {
    private val sinks = CopyOnWriteArraySet<VideoSink>()

    fun add(sink: VideoSink) = sinks.add(sink)

    fun remove(sink: VideoSink) = sinks.remove(sink)

    override fun onFrame(frame: VideoFrame) = sinks.forEach { it.onFrame(frame) }
}
