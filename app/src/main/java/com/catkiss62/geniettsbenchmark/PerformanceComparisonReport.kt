package com.catkiss62.geniettsbenchmark

import java.util.Locale

data class SingleRunPerformanceEntry(
    val profile: PerformanceProfile,
    val sustainedPerformanceApplied: Boolean,
    val thermalBefore: String,
    val thermalAfter: String,
    val modelLoadMs: Long,
    val fixtureLoadMs: Long,
    val encoderMs: Long,
    val firstDecoderMs: Long,
    val autoregressiveMs: Long,
    val vocoderMs: Long,
    val totalInferenceMs: Long,
    val endToEndMs: Long,
    val decoderIterations: Int,
    val audioSeconds: Double,
    val coreRtf: Double,
    val semanticTokens: Int,
    val semanticHash: String,
    val audioPeak: Double,
    val audioRms: Double,
    val clippedPercent: Double,
    val pssMb: Int,
) {
    companion object {
        fun from(
            profile: PerformanceProfile,
            sustainedPerformanceApplied: Boolean,
            thermalBefore: String,
            thermalAfter: String,
            result: BenchmarkResult,
        ) = SingleRunPerformanceEntry(
            profile = profile,
            sustainedPerformanceApplied = sustainedPerformanceApplied,
            thermalBefore = thermalBefore,
            thermalAfter = thermalAfter,
            modelLoadMs = result.modelLoadMs,
            fixtureLoadMs = result.fixtureLoadMs,
            encoderMs = result.encoderMs,
            firstDecoderMs = result.firstDecoderMs,
            autoregressiveMs = result.autoregressiveMs,
            vocoderMs = result.vocoderMs,
            totalInferenceMs = result.totalInferenceMs,
            endToEndMs = result.endToEndMs,
            decoderIterations = result.decoderIterations,
            audioSeconds = result.audioSeconds,
            coreRtf = result.coreRtf,
            semanticTokens = result.semanticTokens,
            semanticHash = result.semanticHash,
            audioPeak = result.audioPeak,
            audioRms = result.audioRms,
            clippedPercent = result.clippedPercent,
            pssMb = result.pssMb,
        )
    }
}

data class SingleRunPerformanceFailure(
    val profile: PerformanceProfile,
    val sustainedPerformanceApplied: Boolean,
    val thermalBefore: String,
    val thermalAfter: String,
    val error: String,
)

data class PerformanceComparisonReport(
    val version: String,
    val timestamp: String,
    val deviceLine: String,
    val voicePackageTitle: String,
    val caseTitle: String,
    val targetTitle: String,
    val text: String,
    val sustainedPerformanceSupported: Boolean,
    val entries: List<SingleRunPerformanceEntry>,
    val failures: List<SingleRunPerformanceFailure> = emptyList(),
) {
    init {
        val profiles = entries.map { it.profile } + failures.map { it.profile }
        require(profiles.isNotEmpty()) { "性能对比至少需要一项结果" }
        require(profiles.distinct().size == profiles.size) { "性能档结果重复" }
    }

    fun render(): String = buildString {
        appendLine("===== Genie-TTS v$version 小酒狐五档单次性能对比 · $timestamp =====")
        appendLine(deviceLine)
        appendLine("模式：每档一次冷加载 + 一次固定预设推理；只生成 PCM 数据，不创建 AudioTrack、不播放。")
        appendLine("公平性：同一语音包、同一参考音色、同一预计算文本特征；语速、音调、高频柔化和播放增益均不参与。")
        val entryByProfile = entries.associateBy { it.profile }
        val failureByProfile = failures.associateBy { it.profile }
        val attemptedProfiles = PerformanceProfile.entries.filter { it in entryByProfile || it in failureByProfile }
        appendLine("测试顺序：${attemptedProfiles.joinToString(" → ") { it.title }}")
        appendLine("语音包：$voicePackageTitle · 音色：$caseTitle")
        appendLine("台词类型：$targetTitle")
        appendLine("文本：$text")
        appendLine("Android 持续性能模式支持：${if (sustainedPerformanceSupported) "是" else "否"}")

        attemptedProfiles.forEachIndexed { index, profile ->
            val entry = entryByProfile[profile]
            val failure = failureByProfile[profile]
            appendLine("\n--- ${index + 1}/${attemptedProfiles.size} · ${profile.title} ---")
            if (failure != null) {
                appendLine("配置：${profile.config.label}")
                appendLine("持续性能模式实际请求：${if (failure.sustainedPerformanceApplied) "是" else "否"}")
                appendLine("温控：${failure.thermalBefore} → ${failure.thermalAfter}")
                appendLine("结果：失败，但已继续测试后续档位")
                appendLine("错误：${failure.error}")
                return@forEachIndexed
            }
            checkNotNull(entry)
            appendLine("配置：${entry.profile.config.label}")
            appendLine("持续性能模式实际请求：${if (entry.sustainedPerformanceApplied) "是" else "否"}")
            appendLine("温控：${entry.thermalBefore} → ${entry.thermalAfter}")
            appendLine("模型冷加载：${entry.modelLoadMs} ms · 测试张量读取：${entry.fixtureLoadMs} ms")
            appendLine("T2S Encoder：${entry.encoderMs} ms")
            appendLine("首步 Decoder：${entry.firstDecoderMs} ms")
            appendLine("自回归 Decoder：${entry.autoregressiveMs} ms / ${entry.decoderIterations} 次")
            appendLine("VITS：${entry.vocoderMs} ms")
            appendLine("核心推理：${entry.totalInferenceMs} ms · 端到端等待：${entry.endToEndMs} ms")
            appendLine("音频时长：${decimal(entry.audioSeconds, 3)} s · RTF：${decimal(entry.coreRtf, 3)}")
            appendLine("语义序列：${entry.semanticTokens} tokens · ${entry.semanticHash}")
            appendLine(
                "波形：峰值 ${decimal(entry.audioPeak, 4)} · RMS ${decimal(entry.audioRms, 4)} · " +
                    "近削波 ${decimal(entry.clippedPercent, 4)}%"
            )
            appendLine("PSS：约 ${entry.pssMb} MB")
        }

        val baseline = entries.firstOrNull { it.profile == PerformanceProfile.BASELINE_8 }
        val ranked = entries.sortedBy { it.coreRtf }
        appendLine("\n===== 横向汇总（成功档位按核心 RTF 从快到慢） =====")
        ranked.forEachIndexed { index, entry ->
            val relative = baseline?.let { baselineEntry ->
                if (entry.profile == PerformanceProfile.BASELINE_8 || baselineEntry.coreRtf <= 0.0) {
                    "基准"
                } else {
                    val faster = (baselineEntry.coreRtf - entry.coreRtf) / baselineEntry.coreRtf * 100.0
                    if (faster >= 0.0) "比基准快 ${decimal(faster, 1)}%" else "比基准慢 ${decimal(-faster, 1)}%"
                }
            } ?: "无基准"
            appendLine(
                "${index + 1}. ${entry.profile.title}：RTF ${decimal(entry.coreRtf, 3)} · " +
                    "核心 ${entry.totalInferenceMs} ms · 端到端 ${entry.endToEndMs} ms · $relative"
            )
        }

        if (ranked.isEmpty()) {
            appendLine("没有成功完成的档位，请根据上方错误排查。")
        } else {
            val semanticConsistent = entries.map { it.semanticTokens to it.semanticHash }.distinct().size == 1
            val minAudio = entries.minOf { it.audioSeconds }
            val maxAudio = entries.maxOf { it.audioSeconds }
            appendLine("最快单次结果：${ranked.first().profile.title}")
            appendLine("语义序列一致：${if (semanticConsistent) "是" else "否（需要排查输出差异）"}")
            appendLine("成功档位音频时长范围：${decimal(minAudio, 3)}～${decimal(maxAudio, 3)} s")
            appendLine("峰值 PSS：约 ${entries.maxOf { it.pssMb }} MB")
        }
        appendLine("成功/失败：${entries.size}/${failures.size}")
        appendLine("说明：单次结果会受温控、系统后台和测试顺序影响；用于先筛出候选档，最终应让最快两档重复测试。")
    }

    private fun decimal(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value)
}
