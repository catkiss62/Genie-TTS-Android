package com.catkiss62.geniettsbenchmark

import java.util.Locale
import kotlin.math.sqrt

data class PerformanceSegmentEntry(
    val index: Int,
    val text: String,
    val readyAfterStartMs: Long,
    val frontendMs: Long,
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
    val pcm16Hash: String,
    val pssMb: Int,
) {
    companion object {
        fun from(index: Int, text: String, readyAfterStartMs: Long, result: BenchmarkResult) =
            PerformanceSegmentEntry(
                index = index,
                text = text,
                readyAfterStartMs = readyAfterStartMs,
                frontendMs = result.frontendMs,
                modelLoadMs = if (result.modelLoadedThisRun) result.modelLoadMs else 0L,
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
                pcm16Hash = pcm16Hash(result.audio),
                pssMb = result.pssMb,
            )

        private fun pcm16Hash(audio: FloatArray): String {
            var hash = 1
            audio.forEach { value ->
                val sample = (value.coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE)
                    .toInt().toShort().toInt()
                hash = 31 * hash + sample
            }
            return hash.toUInt().toString(16).padStart(8, '0')
        }
    }
}

data class SingleRunPerformanceEntry(
    val profile: PerformanceProfile,
    val sustainedPerformanceApplied: Boolean,
    val thermalBefore: String,
    val thermalAfter: String,
    val generationWallMs: Long,
    val segments: List<PerformanceSegmentEntry>,
) {
    init {
        require(segments.isNotEmpty()) { "每档性能测试至少需要一个分段" }
        require(segments.map { it.index } == (1..segments.size).toList()) { "性能分段序号不连续" }
    }

    val textChars: Int get() = segments.sumOf { it.text.length }
    val modelLoadMs: Long get() = segments.sumOf { it.modelLoadMs }
    val totalFrontendMs: Long get() = segments.sumOf { it.frontendMs }
    val fixtureLoadMs: Long get() = segments.sumOf { it.fixtureLoadMs }
    val encoderMs: Long get() = segments.sumOf { it.encoderMs }
    val firstDecoderMs: Long get() = segments.sumOf { it.firstDecoderMs }
    val autoregressiveMs: Long get() = segments.sumOf { it.autoregressiveMs }
    val vocoderMs: Long get() = segments.sumOf { it.vocoderMs }
    val totalInferenceMs: Long get() = segments.sumOf { it.totalInferenceMs }
    val decoderIterations: Int get() = segments.sumOf { it.decoderIterations }
    val audioSeconds: Double get() = segments.sumOf { it.audioSeconds }
    val coreRtf: Double
        get() = if (audioSeconds > 0.0) totalInferenceMs / (audioSeconds * 1000.0) else Double.POSITIVE_INFINITY
    val firstSegmentWaitMs: Long get() = segments.first().readyAfterStartMs
    val semanticTokens: Int get() = segments.sumOf { it.semanticTokens }
    val semanticSignature: List<Pair<Int, String>> get() = segments.map { it.semanticTokens to it.semanticHash }
    val pcm16Signature: List<String> get() = segments.map { it.pcm16Hash }
    val audioPeak: Double get() = segments.maxOf { it.audioPeak }
    val audioRms: Double
        get() = if (audioSeconds > 0.0) {
            sqrt(segments.sumOf { it.audioRms * it.audioRms * it.audioSeconds } / audioSeconds)
        } else 0.0
    val clippedPercent: Double
        get() = if (audioSeconds > 0.0) {
            segments.sumOf { it.clippedPercent * it.audioSeconds } / audioSeconds
        } else 0.0
    val pssMb: Int get() = segments.maxOf { it.pssMb }

    /**
     * Estimates whether sequential generation could keep a one-track stream fed. Playback is
     * treated as starting when segment one is ready; later readiness is compared with the audio
     * duration already produced. This intentionally excludes AudioTrack scheduling noise.
     */
    val simulatedBufferMarginsMs: List<Long>
        get() {
            if (segments.size <= 1) return emptyList()
            val playbackStartMs = segments.first().readyAfterStartMs
            var queuedAudioMs = (segments.first().audioSeconds * 1000.0).toLong()
            return segments.drop(1).map { segment ->
                val elapsedPlaybackMs = segment.readyAfterStartMs - playbackStartMs
                val margin = queuedAudioMs - elapsedPlaybackMs
                queuedAudioMs += (segment.audioSeconds * 1000.0).toLong()
                margin
            }
        }

    val minSimulatedBufferMarginMs: Long? get() = simulatedBufferMarginsMs.minOrNull()
    val simulatedLateSegments: Int get() = simulatedBufferMarginsMs.count { it < 0L }
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
    val text: String,
    val plannedSegments: List<String>,
    val sustainedPerformanceSupported: Boolean,
    val sharedFrontendMs: Long,
    val warmupInferenceMs: Long,
    val entries: List<SingleRunPerformanceEntry>,
    val failures: List<SingleRunPerformanceFailure> = emptyList(),
) {
    init {
        val profiles = entries.map { it.profile } + failures.map { it.profile }
        require(profiles.isNotEmpty()) { "性能对比至少需要一项结果" }
        require(profiles.distinct().size == profiles.size) { "性能档结果重复" }
        require(plannedSegments.isNotEmpty()) { "连续性能对比至少需要一个分段" }
    }

    fun render(): String = buildString {
        appendLine("===== Genie-TTS v$version 小酒狐原生/核亲和 TTS 诊断 · $timestamp =====")
        appendLine(deviceLine)
        appendLine("模式：共用一次中文前处理 + 一次不计分热身；原生 TTS 与自动核亲和各自独立冷加载并连续分段推理，只生成 PCM 数据，不创建 AudioTrack、不播放。")
        appendLine("连续性：根据各段完成时刻与已生成音频时长估算缓冲余量；这是纯推理对比，不是 AudioTrack underrun 实测。")
        appendLine("公平性：两档共用同一模型、参考音色、文本、分段、预计算特征与原始 Decoder 路径；唯一调度差异是 intra-op 8 与 0。")
        appendLine("共同准备：中文前处理 $sharedFrontendMs ms（不计入两档） · 自动核亲和热身核心 $warmupInferenceMs ms（不计分）。")
        val entryByProfile = entries.associateBy { it.profile }
        val failureByProfile = failures.associateBy { it.profile }
        val attemptedProfiles = PerformanceProfile.entries.filter { it in entryByProfile || it in failureByProfile }
        appendLine("测试顺序：${attemptedProfiles.joinToString(" → ") { it.title }}")
        appendLine("语音包：$voicePackageTitle · 音色：$caseTitle")
        appendLine("基准文本：${text.length} 字符 · ${plannedSegments.size} 段 · 单段最长 ${plannedSegments.maxOf { it.length }} 字符")
        appendLine("分段策略：与 AI 伴侣沉浸房间一致；首句立即生成，后续只拼合已闭合句子，中文单段不超过 54 字。")
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
            appendLine("分段：${entry.segments.size} 段 / ${entry.textChars} 字符 · 模型冷加载：${entry.modelLoadMs} ms")
            appendLine("中文前处理：共用预计算（本档 ${entry.totalFrontendMs} ms） · 测试张量构造/读取合计：${entry.fixtureLoadMs} ms")
            appendLine("T2S Encoder 合计：${entry.encoderMs} ms")
            appendLine("首步 Decoder 合计：${entry.firstDecoderMs} ms")
            appendLine("自回归 Decoder 合计：${entry.autoregressiveMs} ms / ${entry.decoderIterations} 次")
            appendLine("VITS 合计：${entry.vocoderMs} ms")
            appendLine("核心推理合计：${entry.totalInferenceMs} ms · 连续生成墙钟：${entry.generationWallMs} ms")
            appendLine("首段可用等待：${entry.firstSegmentWaitMs} ms")
            appendLine("音频合计：${decimal(entry.audioSeconds, 3)} s · 聚合 RTF：${decimal(entry.coreRtf, 3)}")
            appendLine(
                "推算最小缓冲余量：${entry.minSimulatedBufferMarginMs?.let { "$it ms" } ?: "无后续段"} · " +
                    "推算迟到段：${entry.simulatedLateSegments}/${entry.simulatedBufferMarginsMs.size}"
            )
            appendLine("语义合计：${entry.semanticTokens} tokens · 波形峰值 ${decimal(entry.audioPeak, 4)} · RMS ${decimal(entry.audioRms, 4)} · 近削波 ${decimal(entry.clippedPercent, 4)}%")
            appendLine("峰值 PSS：约 ${entry.pssMb} MB")
            entry.segments.forEach { segment ->
                val margin = entry.simulatedBufferMarginsMs.getOrNull(segment.index - 2)
                appendLine(
                    "  段${segment.index}/${entry.segments.size}：${segment.text.length}字 · 前处理 ${segment.frontendMs} ms · " +
                        "核心 ${segment.totalInferenceMs} ms · RTF ${decimal(segment.coreRtf, 3)} · " +
                        "音频 ${decimal(segment.audioSeconds, 2)} s · 点击后就绪 ${segment.readyAfterStartMs} ms" +
                        (margin?.let { " · 推算缓冲 ${it} ms" } ?: "")
                )
                appendLine("    语义 ${segment.semanticTokens} tokens · ${segment.semanticHash} · PCM16 ${segment.pcm16Hash} · PSS 约 ${segment.pssMb} MB · 文本：${segment.text}")
            }
        }

        val native = entries.firstOrNull { it.profile == PerformanceProfile.NATIVE_TTS }
        val affinity = entries.firstOrNull { it.profile == PerformanceProfile.AUTO_AFFINITY }
        val ranked = entries.sortedBy { it.coreRtf }
        appendLine("\n===== 横向汇总（成功档位按聚合 RTF 从快到慢） =====")
        ranked.forEachIndexed { index, entry ->
            val relative = if (entry.profile == PerformanceProfile.NATIVE_TTS) {
                "原生基线"
            } else if (native != null) {
                val faster = (native.coreRtf - entry.coreRtf) / native.coreRtf * 100.0
                if (faster >= 0.0) {
                    "比原生 TTS 快 ${decimal(faster, 1)}%"
                } else {
                    "比原生 TTS 慢 ${decimal(-faster, 1)}%"
                }
            } else {
                "缺少原生基线"
            }
            appendLine(
                "${index + 1}. ${entry.profile.title}：聚合 RTF ${decimal(entry.coreRtf, 3)} · " +
                    "核心 ${entry.totalInferenceMs} ms · 连续生成 ${entry.generationWallMs} ms · " +
                    "迟到 ${entry.simulatedLateSegments}/${entry.simulatedBufferMarginsMs.size} · $relative"
            )
        }

        if (ranked.isEmpty()) {
            appendLine("没有成功完成的档位，请根据上方错误排查。")
        } else {
            val semanticConsistent = entries.map { it.semanticSignature }.distinct().size == 1
            val pcm16Consistent = entries.map { it.pcm16Signature }.distinct().size == 1
            val minAudio = entries.minOf { it.audioSeconds }
            val maxAudio = entries.maxOf { it.audioSeconds }
            appendLine("最快单次连续结果：${ranked.first().profile.title}")
            appendLine("逐段语义序列一致：${if (semanticConsistent) "是" else "否（需要排查输出差异）"}")
            appendLine("逐段播放级 PCM16 一致：${if (pcm16Consistent) "是" else "否（需试听并排查波形差异）"}")
            appendLine("成功档位音频总时长范围：${decimal(minAudio, 3)}～${decimal(maxAudio, 3)} s")
            appendLine("峰值 PSS：约 ${entries.maxOf { it.pssMb }} MB")
            val functionsMatch = native != null && affinity != null &&
                native.semanticSignature == affinity.semanticSignature &&
                native.pcm16Signature == affinity.pcm16Signature &&
                native.audioSeconds == affinity.audioSeconds
            appendLine("双模式功能一致性：${if (functionsMatch) "通过" else "未通过或数据不完整"}")
            if (native != null && affinity != null) {
                val faster = (native.coreRtf - affinity.coreRtf) / native.coreRtf * 100.0
                appendLine(
                    "自动核亲和相对原生 TTS：" +
                        if (faster >= 0.0) "快 ${decimal(faster, 1)}%" else "慢 ${decimal(-faster, 1)}%"
                )
            }
        }
        appendLine("成功/失败：${entries.size}/${failures.size}")
        appendLine("说明：本报告用于确认收口后的自动核亲和没有改变语义或播放级 PCM，并与性能实验前的原生 8 线程路径对照。")
    }

    private fun decimal(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value)
}
