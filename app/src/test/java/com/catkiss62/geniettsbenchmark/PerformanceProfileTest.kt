package com.catkiss62.geniettsbenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProfileTest {
    @Test
    fun verifiedHandoffConfigLocksEveryWinningRuntimeKnob() {
        val config = VerifiedRuntimeConfig.AUTO_AFFINITY
        assertEquals(BackendMode.CPU, config.backend)
        assertEquals(0, config.threads)
        assertEquals(1, config.interOpThreads)
        assertEquals(GraphExecutionMode.SEQUENTIAL, config.executionMode)
        assertTrue(config.allowSpinning)
        assertEquals(null, config.dynamicBlockBase)
        assertFalse(config.reuseDecoderInputMap)
        assertEquals(DenormalTarget.NONE, config.denormalTarget)
        assertTrue(config.vocoderMemoryPatternOptimization)
        assertFalse(config.sustainedPerformance)
    }

    @Test
    fun profileIdsAndConfigsAreDistinct() {
        val profiles = PerformanceProfile.entries
        assertEquals(profiles.size, profiles.map { it.config.profileId }.distinct().size)
        assertEquals(profiles.size, profiles.map { it.config }.distinct().size)
    }

    @Test
    fun diagnosticContainsOnlyNativeAndFinalAffinityModes() {
        assertEquals(
            listOf(PerformanceProfile.NATIVE_TTS, PerformanceProfile.AUTO_AFFINITY),
            PerformanceProfile.entries,
        )
        assertEquals(VerifiedRuntimeConfig.NATIVE_TTS, PerformanceProfile.NATIVE_TTS.config)
        assertEquals(VerifiedRuntimeConfig.AUTO_AFFINITY, PerformanceProfile.AUTO_AFFINITY.config)
    }

    @Test
    fun nativeBaselineDiffersOnlyByIntraOpThreadCountAndIdentity() {
        val native = VerifiedRuntimeConfig.NATIVE_TTS
        val affinity = VerifiedRuntimeConfig.AUTO_AFFINITY
        assertEquals(8, native.threads)
        assertEquals(
            affinity.copy(
                threads = 8,
                profileId = native.profileId,
                profileTitle = native.profileTitle,
            ),
            native,
        )
        assertTrue(PerformanceProfile.entries.none { it.config.sustainedPerformance })
    }
}
