package com.construct.messenger.ui.screens.calls

import com.construct.messenger.data.model.CallUi

/**
 * What goes where on the video call screen — what fills it, what sits in the small window, which
 * corner that window goes to, and when the controls hide. Apart from the screen, so a test can
 * reach it. **Canon:** iOS `VideoCallStage.swift`; `client/specs/VIDEO_CALLS_DESIGN.md`,
 * "Принцип интерфейса".
 */
data class VideoCallStage(val big: Pane, val small: Pane?) {

    enum class Pane {
        REMOTE_VIDEO,
        /** The peer's camera is off: their avatar, as in an audio call. */
        REMOTE_AVATAR,
        LOCAL_VIDEO,
        /** Our camera is off while theirs is on — the window stays, so it is clear why they cannot see us. */
        LOCAL_CAMERA_OFF,
    }

    /** Only two faces can trade places. */
    val canSwap: Boolean get() = small != null && big != small && listOf(big, small).all { it == Pane.REMOTE_VIDEO || it == Pane.LOCAL_VIDEO }

    /**
     * The header and the controls go away after a few seconds without a touch — but only over a
     * face worth seeing whole, never while the call is still being set up, and never under
     * TalkBack, where a control that disappears cannot be found again by touch.
     */
    fun controlsAutoHide(isConnecting: Boolean, talkBack: Boolean): Boolean =
        big == Pane.REMOTE_VIDEO && !isConnecting && !talkBack

    companion object {
        const val CONTROLS_HIDE_AFTER_MS = 3_000L

        /** Null: the audio screen. */
        fun make(video: CallUi.Video, isConnecting: Boolean, isEnded: Boolean, swapped: Boolean): VideoCallStage? {
            if (isEnded) return null
            return when {
                !video.peerCameraOn && !video.sending -> null
                // Before the answer there is nobody to show yet, so our own camera fills the
                // screen, as FaceTime does while it rings.
                !video.peerCameraOn -> if (isConnecting) VideoCallStage(Pane.LOCAL_VIDEO, null) else VideoCallStage(Pane.REMOTE_AVATAR, Pane.LOCAL_VIDEO)
                !video.sending -> VideoCallStage(Pane.REMOTE_VIDEO, Pane.LOCAL_CAMERA_OFF)
                swapped -> VideoCallStage(Pane.LOCAL_VIDEO, Pane.REMOTE_VIDEO)
                else -> VideoCallStage(Pane.REMOTE_VIDEO, Pane.LOCAL_VIDEO)
            }
        }
    }
}

/** Where the small window sits. It is dragged anywhere and lands in the nearest corner. */
enum class PreviewCorner {
    TOP_START, TOP_END, BOTTOM_START, BOTTOM_END;

    val isTop: Boolean get() = this == TOP_START || this == TOP_END
    val isStart: Boolean get() = this == TOP_START || this == BOTTOM_START

    companion object {
        /** The corner whose quarter of the screen the point is in. */
        fun nearest(x: Float, y: Float, width: Float, height: Float): PreviewCorner {
            val start = x < width / 2
            val top = y < height / 2
            return when {
                top && start -> TOP_START
                top -> TOP_END
                start -> BOTTOM_START
                else -> BOTTOM_END
            }
        }
    }
}
