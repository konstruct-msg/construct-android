package com.construct.messenger.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How voice notes play, kept on this device (construct-docs TODO 149). **Canon:** iOS
 * `AudioPlayerService.rate` (`voice.playbackRate`) and `ChatViewModel.continuousVoicePlaybackKey`.
 */
@Singleton
class VoicePlaybackSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("media_prefs", Context.MODE_PRIVATE)

    private val _rate = MutableStateFlow(prefs.getFloat(KEY_RATE, 1f).takeIf { it in RATES } ?: 1f)

    /** The speed of every voice note, one of [RATES]. */
    val rate: StateFlow<Float> = _rate.asStateFlow()

    private val _continuous = MutableStateFlow(prefs.getBoolean(KEY_CONTINUOUS, false))

    /** A voice note that ends by itself starts the next one in the chat. Off by default, as on iOS. */
    val continuous: StateFlow<Boolean> = _continuous.asStateFlow()

    fun setRate(rate: Float) {
        val r = rate.takeIf { it in RATES } ?: 1f
        prefs.edit().putFloat(KEY_RATE, r).apply()
        _rate.value = r
    }

    fun setContinuous(on: Boolean) {
        prefs.edit().putBoolean(KEY_CONTINUOUS, on).apply()
        _continuous.value = on
    }

    companion object {
        /** iOS `AudioPlayerService.rates`. */
        val RATES = listOf(1f, 1.25f, 1.5f, 2f)

        /** The speed after [rate] in the cycle; an unknown one starts it again. */
        fun nextRate(rate: Float): Float {
            val i = RATES.indexOf(rate)
            return if (i < 0) RATES[0] else RATES[(i + 1) % RATES.size]
        }

        /** "1×", "1.25×", "1.5×", "2×" — iOS `VoiceScrub.rateLabel`. */
        fun rateLabel(rate: Float): String =
            rate.toBigDecimal().stripTrailingZeros().toPlainString() + "×"

        private const val KEY_RATE = "voice_rate"
        private const val KEY_CONTINUOUS = "voice_continuous"
    }
}
