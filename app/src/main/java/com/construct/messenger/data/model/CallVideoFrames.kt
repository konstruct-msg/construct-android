package com.construct.messenger.data.model

import org.webrtc.EglBase
import org.webrtc.VideoSink

/** How a video pane reaches the frames: the GL context they are decoded with, and each side's sinks. */
class CallVideoFrames(
    val context: EglBase.Context,
    val add: (local: Boolean, sink: VideoSink) -> Unit,
    val remove: (local: Boolean, sink: VideoSink) -> Unit,
)
