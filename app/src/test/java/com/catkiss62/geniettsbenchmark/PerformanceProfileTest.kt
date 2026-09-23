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
    fun originalAutoAffinityPreservesTheV081Winner() {
        val config = PerformanceProfile.AUTO_AFFINITY_CONTROL_START.config
        val verified = VerifiedRuntimeConfig.AUTO_AFFINITY
        assertEquals(
            verified.copy(profileId = config.profileId, profileTitle = config.profileTitle),
            config,
        )
    }

    @Test
    fun startAndEndControlsHaveTheSameFunctionalConfiguration() {
        val start = PerformanceProfile.AUTO_AFFINITY_CONTROL_START.config
        val end = PerformanceProfile.AUTO_AFFINITY_CONTROL_END.config
        assertEquals(start.copy(profileId = end.profileId, profileTitle = end.profileTitle), end)
    }

    @Test
    fun optimizedProfilesKeepAutoAffinityAndOnlyChangeDeclaredKnobs() {
        val optimized = PerformanceProfile.entries.drop(1).dropLast(1)
        assertTrue(optimized.all { it.config.threads == 0 })
        assertTrue(optimized.all { it.config.executionMode == GraphExecutionMode.SEQUENTIAL })
        assertTrue(optimized.all { it.config.allowSpinning })
        assertTrue(optimized.none { it.config.reuseDecoderInputMap })
        assertTrue(optimized.all { it.config.dynamicBlockBase == null })
        assertEquals(
            listOf(
                DenormalTarget.DECODERS,
                DenormalTarget.VOCODER,
                DenormalTarget.ALL_TTS,
                DenormalTarget.NONE,
            ),
            optimized.map { it.config.denormalTarget },
        )
        assertEquals(listOf(true, true, true, false), optimized.map {
            it.config.vocoderMemoryPatternOptimization
        })
        assertTrue(PerformanceProfile.entries.none { it.config.sustainedPerformance })
    }

    @Test
    fun denormalTargetsMapToOnlyTheDeclaredSessions() {
        val decoder = PerformanceProfile.DECODER_DENORMAL_ZERO.config
        assertFalse(decoder.flushDenormals(ModelSessionRole.ENCODER))
        assertTrue(decoder.flushDenormals(ModelSessionRole.FIRST_DECODER))
        assertTrue(decoder.flushDenormals(ModelSessionRole.STAGE_DECODER))
        assertFalse(decoder.flushDenormals(ModelSessionRole.VOCODER))

        val vocoder = PerformanceProfile.VITS_DENORMAL_ZERO.config
        assertFalse(vocoder.flushDenormals(ModelSessionRole.STAGE_DECODER))
        assertTrue(vocoder.flushDenormals(ModelSessionRole.VOCODER))

        val all = PerformanceProfile.ALL_TTS_DENORMAL_ZERO.config
        assertTrue(ModelSessionRole.entries.all { all.flushDenormals(it) })
    }
}
