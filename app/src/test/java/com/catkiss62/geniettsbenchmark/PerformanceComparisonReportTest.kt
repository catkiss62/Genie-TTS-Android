package com.catkiss62.geniettsbenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceComparisonReportTest {
    @Test
    fun reportContainsEveryProfileAndRanksByAggregateRtf() {
        val entries = PerformanceProfile.entries.mapIndexed { index, profile ->
            entry(profile, rtf = listOf(1.0, 0.7, 0.9, 1.2, 0.8)[index])
        }
        val report = report(entries).render()

        PerformanceProfile.entries.forEach { assertTrue(report.contains(it.title)) }
        assertTrue(report.contains("1. Decoder映射复用：聚合 RTF 0.700"))
        assertTrue(report.contains("比原始自动快 30.0%"))
        assertTrue(report.contains("只生成 PCM 数据，不创建 AudioTrack、不播放"))
        assertTrue(report.contains("逐段语义序列一致：是"))
        assertTrue(report.contains("纯推理对比，不是 AudioTrack underrun 实测"))
    }

    @Test
    fun simulatedContinuityUsesSegmentReadyTimesWithoutPlaying() {
        val entry = entry(PerformanceProfile.AUTO_AFFINITY_ORIGINAL, rtf = 1.0)

        assertEquals(listOf(500L), entry.simulatedBufferMarginsMs)
        assertEquals(500L, entry.minSimulatedBufferMarginMs)
        assertEquals(0, entry.simulatedLateSegments)

        val late = entry.copy(
            segments = listOf(
                segment(1, readyMs = 1_000L, audioSeconds = 2.0, rtf = 1.0),
                segment(2, readyMs = 3_500L, audioSeconds = 2.0, rtf = 1.0),
            ),
        )
        assertEquals(listOf(-500L), late.simulatedBufferMarginsMs)
        assertEquals(1, late.simulatedLateSegments)
    }

    @Test
    fun reportFlagsPerSegmentSemanticMismatch() {
        val baseline = entry(PerformanceProfile.AUTO_AFFINITY_ORIGINAL, rtf = 1.0)
        val changed = entry(PerformanceProfile.DECODER_MAP_REUSE, rtf = 0.8).let { entry ->
            entry.copy(
                segments = entry.segments.mapIndexed { index, segment ->
                    if (index == 1) segment.copy(semanticHash = "different") else segment
                },
            )
        }
        assertTrue(
            report(listOf(baseline, changed)).render()
                .contains("逐段语义序列一致：否（需要排查输出差异）"),
        )
    }

    @Test
    fun failedProfileIsRecordedWithoutDroppingSuccessfulResults() {
        val failure = SingleRunPerformanceFailure(
            PerformanceProfile.DYNAMIC_BLOCK_4,
            sustainedPerformanceApplied = false,
            thermalBefore = "正常",
            thermalAfter = "正常",
            error = "测试异常",
        )
        val rendered = report(
            listOf(entry(PerformanceProfile.AUTO_AFFINITY_ORIGINAL, rtf = 1.0)),
            failures = listOf(failure),
        ).render()
        assertTrue(rendered.contains("结果：失败，但已继续测试后续档位"))
        assertTrue(rendered.contains("成功/失败：1/1"))
    }

    private fun report(
        entries: List<SingleRunPerformanceEntry>,
        failures: List<SingleRunPerformanceFailure> = emptyList(),
    ) = PerformanceComparisonReport(
        version = "0.8.2",
        timestamp = "2026-09-21 03:00:00",
        deviceLine = "测试设备",
        voicePackageTitle = "小酒狐",
        caseTitle = "日常",
        text = "第一句。第二句。",
        plannedSegments = listOf("第一句。", "第二句。"),
        sustainedPerformanceSupported = true,
        entries = entries,
        failures = failures,
    )

    private fun entry(profile: PerformanceProfile, rtf: Double) = SingleRunPerformanceEntry(
        profile = profile,
        sustainedPerformanceApplied = profile.config.sustainedPerformance,
        thermalBefore = "正常",
        thermalAfter = "正常",
        generationWallMs = 2_500L,
        segments = listOf(
            segment(1, readyMs = 1_000L, audioSeconds = 2.0, rtf = rtf),
            segment(2, readyMs = 2_500L, audioSeconds = 2.0, rtf = rtf),
        ),
    )

    private fun segment(
        index: Int,
        readyMs: Long,
        audioSeconds: Double,
        rtf: Double,
    ) = PerformanceSegmentEntry(
        index = index,
        text = "第${index}句。",
        readyAfterStartMs = readyMs,
        frontendMs = 20,
        modelLoadMs = if (index == 1) 100 else 0,
        fixtureLoadMs = 2,
        encoderMs = 20,
        firstDecoderMs = 10,
        autoregressiveMs = 50,
        vocoderMs = 20,
        totalInferenceMs = (rtf * audioSeconds * 1000.0).toLong(),
        endToEndMs = 205,
        decoderIterations = 12,
        audioSeconds = audioSeconds,
        coreRtf = rtf,
        semanticTokens = 12,
        semanticHash = "same-$index",
        audioPeak = 0.5,
        audioRms = 0.1,
        clippedPercent = 0.0,
        pssMb = 1024,
    )
}
