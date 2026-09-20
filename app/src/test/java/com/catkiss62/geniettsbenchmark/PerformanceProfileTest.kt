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
    fun baselinePreservesThePreviousEightThreadSequentialPath() {
        val config = PerformanceProfile.BASELINE_8.config
        assertEquals(BackendMode.CPU, config.backend)
        assertEquals(8, config.threads)
        assertEquals(1, config.interOpThreads)
        assertEquals(GraphExecutionMode.SEQUENTIAL, config.executionMode)
        assertTrue(config.allowSpinning)
        assertFalse(config.sustainedPerformance)
    }

    @Test
    fun automaticAffinityLeavesThreadCountToOrt() {
        val config = PerformanceProfile.AUTO_AFFINITY.config
        assertEquals(0, config.threads)
        assertTrue(config.label.contains("自动物理核/亲和"))
    }

    @Test
    fun graphParallelAndSustainedModesDoNotOverlap() {
        val parallel = PerformanceProfile.GRAPH_PARALLEL_4X2.config
        assertEquals(4, parallel.threads)
        assertEquals(2, parallel.interOpThreads)
        assertEquals(GraphExecutionMode.PARALLEL, parallel.executionMode)
        assertFalse(parallel.sustainedPerformance)

        val sustained = PerformanceProfile.SUSTAINED_8.config
        assertEquals(GraphExecutionMode.SEQUENTIAL, sustained.executionMode)
        assertTrue(sustained.sustainedPerformance)
        assertEquals(1, PerformanceProfile.entries.count { it.config.sustainedPerformance })
    }
}
