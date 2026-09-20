package com.catkiss62.geniettsbenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceProfileTest {
    @Test
    fun profileIdsAndConfigsAreDistinct() {
        val profiles = PerformanceProfile.entries
        assertEquals(profiles.size, profiles.map { it.config.profileId }.distinct().size)
        assertEquals(profiles.size, profiles.map { it.config }.distinct().size)
    }

    @Test
    fun originalAutoAffinityPreservesTheV081Winner() {
        val config = PerformanceProfile.AUTO_AFFINITY_ORIGINAL.config
        assertEquals(BackendMode.CPU, config.backend)
        assertEquals(0, config.threads)
        assertEquals(1, config.interOpThreads)
        assertEquals(GraphExecutionMode.SEQUENTIAL, config.executionMode)
        assertTrue(config.allowSpinning)
        assertEquals(null, config.dynamicBlockBase)
        assertFalse(config.reuseDecoderInputMap)
        assertFalse(config.sustainedPerformance)
    }

    @Test
    fun optimizedProfilesKeepAutoAffinityAndOnlyChangeDeclaredKnobs() {
        val optimized = PerformanceProfile.entries.drop(1)
        assertTrue(optimized.all { it.config.threads == 0 })
        assertTrue(optimized.all { it.config.executionMode == GraphExecutionMode.SEQUENTIAL })
        assertTrue(optimized.all { it.config.allowSpinning })
        assertTrue(optimized.all { it.config.reuseDecoderInputMap })
        assertEquals(listOf(null, 2, 4, 8), optimized.map { it.config.dynamicBlockBase })
        assertTrue(PerformanceProfile.entries.none { it.config.sustainedPerformance })
    }
}
