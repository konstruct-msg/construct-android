package com.construct.messenger.calls

import shared.proto.signaling.v1.Webrtc.CallType
import shared.proto.signaling.v1.Webrtc.MediaType
import shared.proto.signaling.v1.Webrtc.MediaUpdate
import shared.proto.signaling.v1.Webrtc.MediaUpdateType

/*
 * The decisions of a video call that need neither a camera nor a peer connection, so a test can
 * reach them. **Canon:** iOS `CallVideo.swift`; `client/specs/VIDEO_CALLS_DESIGN.md` in the vault.
 *
 * Every call carries a video transceiver, so turning the camera on is a track swap on a sender that
 * already exists — no renegotiation, no crossing offers. What the swap does not tell the peer is
 * *why* frames stopped: a removed track and a stalled network look the same to the receiver, which
 * keeps showing the last frame. `MediaUpdate` says it, inside the ratchet, so the peer shows the
 * avatar instead of a frozen face.
 */

enum class CallVideoSide { LOCAL, REMOTE }

enum class CameraFacing {
    FRONT, BACK;

    val flipped: CameraFacing get() = if (this == FRONT) BACK else FRONT
}

/** What each side of the call shows, as far as the camera goes. Fresh for every call. */
data class CallVideoState(
    /** This side has a video sender — false when the offer came from an audio-only client. */
    val canSend: Boolean = false,
    /** What the person asked for with the camera button. */
    val localCameraOn: Boolean = false,
    /**
     * What the peer last said about its camera. Starts off: a peer that never says anything (an
     * older client) is shown as its avatar, which is what it is sending.
     */
    val remoteCameraOn: Boolean = false,
    val facing: CameraFacing = CameraFacing.FRONT,
    /** Android takes the camera from an app nobody sees, so frames stop although the person did not turn it off. */
    val inBackground: Boolean = false,
) {
    /**
     * What the peer is told. The camera the person turned on but the system took away counts as
     * off — otherwise the peer watches a frozen frame for as long as we are in the background.
     */
    val announcedCameraOn: Boolean get() = canSend && localCameraOn && !inBackground

    /** What the camera should be doing right now. */
    val capturing: Boolean get() = canSend && localCameraOn

    /**
     * Apply a change to our side, with [canSend] as the media now has it (null: no media yet), and
     * say what the peer must be told — the new announced state — or null when that did not change.
     *
     * "Before" is read here, before the change, and nowhere else. On iOS until 2026-10-05 the
     * button changed the state first and a helper read "before" afterwards, so the two always
     * matched: a callee who turned the camera on was never announced.
     */
    fun apply(canSend: Boolean?, change: (CallVideoState) -> CallVideoState): Change {
        val before = announcedCameraOn
        val after = change(this).let { if (canSend != null) it.copy(canSend = canSend) else it }
        return Change(after, after.announcedCameraOn.takeIf { it != before })
    }

    data class Change(val state: CallVideoState, val announce: Boolean?)
}

/**
 * The camera half of `MediaUpdate`. The proto has room for audio and screen too; neither is sent by
 * this app, and the reader ignores them rather than guessing what they would mean.
 */
object CallVideoSignal {
    fun mediaUpdate(cameraOn: Boolean, atMs: Long): MediaUpdate = MediaUpdate.newBuilder()
        .setUpdateType(MediaUpdateType.MEDIA_UPDATE_TYPE_MUTE)
        .setMediaType(MediaType.MEDIA_TYPE_VIDEO)
        .setEnabled(cameraOn)
        .setUpdatedAt(atMs)
        .build()

    /** A video call is answered with video, as in FaceTime; "answer without video" is another button. */
    fun answersWithCamera(offerCallType: CallType): Boolean = offerCallType == CallType.CALL_TYPE_VIDEO

    /** Whether the peer's camera is on after this update, or null when it is not about the camera. */
    fun remoteCameraOn(update: MediaUpdate): Boolean? {
        if (update.mediaType != MediaType.MEDIA_TYPE_VIDEO) return null
        return when (update.updateType) {
            MediaUpdateType.MEDIA_UPDATE_TYPE_MUTE -> update.enabled
            MediaUpdateType.MEDIA_UPDATE_TYPE_ADD -> true
            MediaUpdateType.MEDIA_UPDATE_TYPE_REMOVE -> false
            else -> null
        }
    }

    /** What the offer says the call is: video when it starts with, or already has, our camera. */
    fun offerCallType(video: CallVideoState, startsWithCamera: Boolean): CallType =
        if (video.announcedCameraOn || startsWithCamera) CallType.CALL_TYPE_VIDEO else CallType.CALL_TYPE_AUDIO
}

/** Which capture format to ask the camera for. */
object CallVideoCapture {
    /**
     * 720p: enough for a phone screen, and what the design budgets the uplink for. WebRTC scales
     * down from here on a poor network, never up.
     */
    const val MAX_WIDTH = 1280
    const val MAX_HEIGHT = 720
    const val MAX_FPS = 30

    /**
     * One format as Camera2 reports it: sensor dimensions (landscape) and frame-rate range in fps.
     * Camera2Enumerator gives the range in thousandths; the caller divides.
     */
    data class Candidate(val width: Int, val height: Int, val minFps: Int, val maxFps: Int) {
        val area: Int get() = width * height
        /** The rate we ask of this format: 30, or its own maximum if lower. */
        val fps: Int get() = maxOf(1, minOf(MAX_FPS, maxFps))
        /**
         * Whether it can run at that rate. A high-speed format's range can start above 30; iOS
         * asked one for 30 and crashed inside the capture stack (build 716, 2026-10-05).
         */
        val canRun: Boolean get() = fps in minFps..maxFps
    }

    data class Choice(val index: Int, val fps: Int)

    /**
     * The largest format that fits 1280×720 and can run at our rate; the smallest usable one if
     * nothing fits. Of equals, the lowest maximum rate — the ordinary format over a fast twin.
     */
    fun choose(candidates: List<Candidate>): Choice? {
        val usable = candidates.indices.filter { candidates[it].canRun }
        val fitting = usable.filter { candidates[it].width <= MAX_WIDTH && candidates[it].height <= MAX_HEIGHT }
        val pick = fitting.minWithOrNull(compareByDescending<Int> { candidates[it].area }.thenBy { candidates[it].maxFps })
            ?: usable.minByOrNull { candidates[it].area }
        return pick?.let { Choice(it, candidates[it].fps) }
    }
}

/**
 * Turning the camera on takes the sound off the earpiece: the phone is now held at arm's length,
 * and the earpiece cannot be heard from there. A headset or a speaker chosen by hand is left alone.
 */
object CallVideoAudio {
    fun movesToSpeaker(wasSending: Boolean, isSending: Boolean, outputIsEarpiece: Boolean): Boolean =
        !wasSending && isSending && outputIsEarpiece
}
