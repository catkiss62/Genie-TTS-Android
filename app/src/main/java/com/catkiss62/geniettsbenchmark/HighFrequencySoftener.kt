package com.catkiss62.geniettsbenchmark

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stateful high-shelf attenuation for synthesized PCM.
 *
 * Keeping the filter state between streamed segments avoids a discontinuity at segment boundaries.
 * The fixed 4 kHz transition reduces Jiuhu's bright upper harmonics without moving the fundamental
 * pitch or compressing the lower register.
 */
internal class HighFrequencySoftener(
    sampleRate: Int,
    attenuationDb: Float,
    cutoffHz: Double = DEFAULT_CUTOFF_HZ,
) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var delay1 = 0.0
    private var delay2 = 0.0

    init {
        require(sampleRate > 0)
        require(attenuationDb in MIN_ATTENUATION_DB..0.0f)

        val centerHz = min(cutoffHz, sampleRate * 0.45)
        val amplitude = 10.0.pow(attenuationDb / 40.0)
        val omega = 2.0 * PI * centerHz / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / 2.0 * sqrt(
            (amplitude + 1.0 / amplitude) * (1.0 / SHELF_SLOPE - 1.0) + 2.0
        )
        val beta = 2.0 * sqrt(amplitude) * alpha

        val rawB0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta)
        val rawB1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
        val rawB2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta)
        val rawA0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta
        val rawA1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
        val rawA2 = (amplitude + 1.0) - (amplitude - 1.0) * cosine - beta

        b0 = rawB0 / rawA0
        b1 = rawB1 / rawA0
        b2 = rawB2 / rawA0
        a1 = rawA1 / rawA0
        a2 = rawA2 / rawA0
    }

    fun process(input: FloatArray): FloatArray = FloatArray(input.size) { index ->
        val sample = input[index].toDouble()
        val output = b0 * sample + delay1
        delay1 = b1 * sample - a1 * output + delay2
        delay2 = b2 * sample - a2 * output
        output.toFloat()
    }

    companion object {
        const val MIN_ATTENUATION_DB = -10.0f
        const val DEFAULT_CUTOFF_HZ = 4_000.0
        private const val SHELF_SLOPE = 1.0
    }
}
