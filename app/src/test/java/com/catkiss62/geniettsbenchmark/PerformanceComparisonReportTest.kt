package com.catkiss62.geniettsbenchmark

import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceComparisonReportTest {
    @Test
    fun reportContainsEveryProfileAndRanksByCoreRtf() {
        val entries = PerformanceProfile.entries.mapIndexed { index, profile ->
            entry(profile, rtf = listOf(1.0, 0.7, 0.9, 1.2, 0.8)[index])
        }
        val report = report(entries).render()

        PerformanceProfile.entries.forEach { assertTrue(report.contains(it.title)) }
        assertTrue(report.contains("1. 自动核亲和：RTF 0.700"))
        assertTrue(report.contains("比基准快 30.0%"))
        assertTrue(report.contains("只生成 PCM 数据，不创建 AudioTrack、不播放"))
        assertTrue(report.contains("语义序列一致：是"))
    }

    @Test
    fun reportFlagsSemanticMismatch() {
        val entries = listOf(
            entry(PerformanceProfile.BASELINE_8, rtf = 1.0),
            entry(PerformanceProfile.AUTO_AFFINITY, rtf = 0.8).copy(semanticHash = "different"),
        )
        assertTrue(report(entries).render().contains("语义序列一致：否（需要排查输出差异）"))
    }

    @Test
    fun failedProfileIsRecordedWithoutDroppingSuccessfulResults() {
        val failure = SingleRunPerformanceFailure(
            PerformanceProfile.GRAPH_PARALLEL_4X2,
            sustainedPerformanceApplied = false,
            thermalBefore = "正常",
            thermalAfter = "正常",
            error = "测试异常",
        )
        val rendered = report(
            listOf(entry(PerformanceProfile.BASELINE_8, rtf = 1.0)),
            failures = listOf(failure),
        ).render()
        assertTrue(rendered.contains("结果：失败，但已继续测试后续档位"))
        assertTrue(rendered.contains("成功/失败：1/1"))
    }

    private fun report(
        entries: List<SingleRunPerformanceEntry>,
        failures: List<SingleRunPerformanceFailure> = emptyList(),
    ) = PerformanceComparisonReport(
        version = "0.8.0",
        timestamp = "2026-09-21 03:00:00",
        deviceLine = "测试设备",
        voicePackageTitle = "小酒狐",
        caseTitle = "日常",
        targetTitle = "普通闲聊",
        text = "你好。",
        sustainedPerformanceSupported = true,
        entries = entries,
        failures = failures,
    )

    private fun entry(profile: PerformanceProfile, rtf: Double) = SingleRunPerformanceEntry(
        profile = profile,
        sustainedPerformanceApplied = profile.config.sustainedPerformance,
        thermalBefore = "正常",
        thermalAfter = "正常",
        modelLoadMs = 100,
        fixtureLoadMs = 2,
        encoderMs = 20,
        firstDecoderMs = 10,
        autoregressiveMs = 50,
        vocoderMs = 20,
        totalInferenceMs = 100,
        endToEndMs = 205,
        decoderIterations = 12,
        audioSeconds = 1.0,
        coreRtf = rtf,
        semanticTokens = 12,
        semanticHash = "same",
        audioPeak = 0.5,
        audioRms = 0.1,
        clippedPercent = 0.0,
        pssMb = 1024,
    )
}
