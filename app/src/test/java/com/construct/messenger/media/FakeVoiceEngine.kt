package com.construct.messenger.media

/** Stands for ExoPlayer under [VoicePlayer]: records what it was asked, ends or fails on demand. */
class FakeVoiceEngine : VoicePlayer.Engine {
    class Session(val track: VoicePlayer.Track, var speed: Float, val onEnd: () -> Unit, val onError: () -> Unit) : VoicePlayer.Session {
        override var positionMs = 0L
        override var durationMs = 10_000L
        override var isPlaying = true
        var released = false
        override fun play(rate: Float) { isPlaying = true; speed = rate }
        override fun pause() { isPlaying = false }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun setRate(rate: Float) { speed = rate }
        override fun release() { released = true }
    }

    val sessions = mutableListOf<Session>()
    var fails = false

    override fun open(audio: ByteArray, track: VoicePlayer.Track, rate: Float, onEnd: () -> Unit, onError: () -> Unit): VoicePlayer.Session {
        if (fails) throw IllegalStateException("cannot play")
        return Session(track, rate, onEnd, onError).also { sessions += it }
    }
}
