package com.construct.messenger.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How a picked video is sent (construct-docs TODO 148). **Canon:** iOS `VideoQuality` — 720p and
 * 1080p are re-encoded to fit their box, [ORIGINAL] goes as it is, only re-wrapped so nothing
 * about where or when it was shot leaves with it.
 */
enum class VideoQuality(val long: Int, val short: Int) {
    P720(1280, 720),
    P1080(1920, 1080),
    ORIGINAL(0, 0),
    ;

    companion object {
        /** iOS `composer.videoQuality` defaults to 1080p too. */
        val DEFAULT = P1080
    }
}

/** The last quality chosen, kept for the next video — iOS keeps it in `composer.videoQuality`. */
@Singleton
class VideoQualityStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("media_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        prefs.getString(KEY, null)?.let { stored -> VideoQuality.entries.firstOrNull { it.name == stored } }
            ?: VideoQuality.DEFAULT,
    )
    val state: StateFlow<VideoQuality> = _state.asStateFlow()

    fun save(quality: VideoQuality) {
        prefs.edit().putString(KEY, quality.name).apply()
        _state.value = quality
    }

    private companion object {
        const val KEY = "video_quality"
    }
}
