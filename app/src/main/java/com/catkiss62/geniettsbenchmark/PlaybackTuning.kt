package com.catkiss62.geniettsbenchmark

import android.media.AudioTrack
import android.media.PlaybackParams
import kotlin.math.pow

data class PlaybackTuning(
    val speed: Float = 1.0f,
    val pitchSemitones: Int = 0,
    val highFrequencySofteningDb: Float = 0.0f,
) {
    init {
        require(speed in 0.70f..1.30f)
        require(pitchSemitones in -6..6)
        require(highFrequencySofteningDb in HighFrequencySoftener.MIN_ATTENUATION_DB..0.0f)
    }

    val pitchRatio: Float
        get() = 2.0.pow(pitchSemitones / 12.0).toFloat()

    val isNeutral: Boolean
        get() = speed == 1.0f && pitchSemitones == 0 && highFrequencySofteningDb == 0.0f

    val reportLabel: String
        get() = "语速 ${"%.2f".format(speed)}×（保持音调） · " +
            "音调 ${"%+d".format(pitchSemitones)} 半音 · " +
            "高频柔化 ${"%.1f".format(highFrequencySofteningDb)} dB"

    internal fun createHighFrequencySoftener(sampleRate: Int): HighFrequencySoftener? =
        if (highFrequencySofteningDb < 0.0f) {
            HighFrequencySoftener(sampleRate, highFrequencySofteningDb)
        } else {
            null
        }

    fun applyTo(track: AudioTrack) {
        if (speed == 1.0f && pitchSemitones == 0) return
        track.playbackParams = PlaybackParams()
            .setSpeed(speed)
            .setPitch(pitchRatio)
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
    }

    companion object {
        val NEUTRAL = PlaybackTuning()
    }
}
