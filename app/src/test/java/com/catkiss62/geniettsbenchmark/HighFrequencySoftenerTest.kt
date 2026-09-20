package com.catkiss62.geniettsbenchmark

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighFrequencySoftenerTest {
    private val sampleRate = 32_000

    @Test
    fun attenuatesUpperHarmonicsWithoutLoweringLowRegister() {
        val low = sineWave(500.0)
        val high = sineWave(8_000.0)
        val softenedLow = HighFrequencySoftener(sampleRate, -6.0f).process(low)
        val softenedHigh = HighFrequencySoftener(sampleRate, -6.0f).process(high)

        val lowChangeDb = levelChangeDb(low, softenedLow)
        val highChangeDb = levelChangeDb(high, softenedHigh)

        assertTrue("500 Hz should remain effectively unchanged: $lowChangeDb dB", lowChangeDb > -0.25)
        assertTrue("8 kHz should receive the requested shelf cut: $highChangeDb dB", highChangeDb in -6.5..-5.0)
    }

    @Test
    fun preservesFilterStateAcrossStreamingSegments() {
        val input = FloatArray(24_000) { index ->
            (0.45 * sin(2.0 * PI * 700.0 * index / sampleRate) +
                0.25 * sin(2.0 * PI * 7_500.0 * index / sampleRate)).toFloat()
        }
        val whole = HighFrequencySoftener(sampleRate, -7.3f).process(input)
        val streamingFilter = HighFrequencySoftener(sampleRate, -7.3f)
        val first = streamingFilter.process(input.copyOfRange(0, 7_777))
        val second = streamingFilter.process(input.copyOfRange(7_777, input.size))
        val streamed = first + second

        whole.indices.forEach { index ->
            assertEquals(whole[index].toDouble(), streamed[index].toDouble(), 1e-7)
        }
    }

    private fun sineWave(frequencyHz: Double): FloatArray = FloatArray(32_000) { index ->
        (0.5 * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
    }

    private fun levelChangeDb(before: FloatArray, after: FloatArray): Double {
        val start = 2_048
        fun rms(values: FloatArray): Double = sqrt(
            values.drop(start).sumOf { it.toDouble() * it.toDouble() } / (values.size - start)
        )
        return 20.0 * log10(rms(after) / rms(before))
    }
}
