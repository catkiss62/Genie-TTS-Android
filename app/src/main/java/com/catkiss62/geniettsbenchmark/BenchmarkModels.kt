package com.catkiss62.geniettsbenchmark

import org.json.JSONObject

data class TensorSpec(val name: String, val file: String, val dtype: String, val shape: LongArray)
data class FeatureMode(
    val id: String,
    val title: String,
    val description: String,
    val tensors: List<TensorSpec>,
    val bertNonZero: Long,
    val bertElements: Long,
    val toneDiagnostic: String,
)
data class TextPreset(
    val id: String,
    val title: String,
    val text: String,
    val tensors: List<TensorSpec>,
    val bertNonZero: Long,
    val bertElements: Long,
)
data class FrontendSpec(
    val roberta: String,
    val vocab: String,
    val charPhones: String,
    val phrasePhones: String,
    val punctuationIds: String,
    val maxPhraseChars: Int,
    val bertDim: Int,
    val quantization: String,
    val robertaBytes: Long,
    val robertaSha256: String,
    val robertaExternal: Boolean,
)
data class PreparedText(
    val text: String,
    val normalizedText: String,
    val sequence: LongArray,
    val bert: FloatArray,
    val bertDim: Int,
    val frontendMs: Long,
    val diagnostic: String,
)
data class BenchmarkCase(
    val id: String,
    val title: String,
    val text: String,
    val tensors: List<TensorSpec>,
    val referenceText: String? = null,
    val referenceAudio: String? = null,
    val playbackGainDb: Double = 0.0,
    val featureTensors: Map<String, List<TensorSpec>> = emptyMap(),
) {
    val displayTitle: String
        get() = VoiceProfileCatalog.displayName(id, title)
}

enum class BackendMode(val displayName: String) {
    CPU("CPU"),
    XNNPACK("XNNPACK"),
    NNAPI_VITS("NNAPI-FP16（仅 VITS）"),
}

enum class GraphExecutionMode(val displayName: String) {
    SEQUENTIAL("顺序图"),
    PARALLEL("图并行"),
}

data class EngineConfig(
    val backend: BackendMode,
    val threads: Int,
    val interOpThreads: Int = 1,
    val executionMode: GraphExecutionMode = GraphExecutionMode.SEQUENTIAL,
    val allowSpinning: Boolean = true,
    val dynamicBlockBase: Int? = null,
    val reuseDecoderInputMap: Boolean = false,
    val sustainedPerformance: Boolean = false,
    val profileId: String = "legacy",
    val profileTitle: String = "自定义",
) {
    init {
        require(dynamicBlockBase == null || dynamicBlockBase > 0) { "动态分块系数必须为正整数" }
    }

    val label: String
        get() {
            val intra = if (threads == 0) "自动物理核/亲和" else "${threads}线程"
            val backendLabel = when (backend) {
                BackendMode.NNAPI_VITS -> "${backend.displayName} + CPU $intra"
                else -> "${backend.displayName} $intra"
            }
            val graph = when (executionMode) {
                GraphExecutionMode.SEQUENTIAL -> executionMode.displayName
                GraphExecutionMode.PARALLEL -> "${executionMode.displayName} / inter-op $interOpThreads"
            }
            val decoderLoop = if (reuseDecoderInputMap) " · Decoder映射复用" else " · Decoder原循环"
            val dynamicBlock = dynamicBlockBase?.let { " · 动态分块 $it" } ?: ""
            val sustained = if (sustainedPerformance) " · Android长时稳态请求" else ""
            return "$profileTitle · $backendLabel · $graph · 线程忙等${if (allowSpinning) "开" else "关"}" +
                "$decoderLoop$dynamicBlock$sustained"
        }
}

enum class PerformanceProfile(
    val title: String,
    val description: String,
    val config: EngineConfig,
) {
    AUTO_AFFINITY_ORIGINAL(
        "原始自动核亲和",
        "完整保留 v0.8.1 胜出路径：ORT 自动物理核/亲和、顺序图、忙等开启和原 Decoder 循环。",
        EngineConfig(
            BackendMode.CPU,
            0,
            profileId = "auto_affinity_original",
            profileTitle = "原始自动核亲和",
        ),
    ),
    DECODER_MAP_REUSE(
        "Decoder映射复用",
        "保持原始自动核亲和，只复用近千次自回归调用的输入映射并预计算输出索引。",
        EngineConfig(
            BackendMode.CPU,
            0,
            reuseDecoderInputMap = true,
            profileId = "decoder_map_reuse",
            profileTitle = "Decoder映射复用",
        ),
    ),
    DYNAMIC_BLOCK_2(
        "动态分块2",
        "在 Decoder 映射复用上启用 ORT 动态任务分块系数 2，测试异构核心负载尾部。",
        EngineConfig(
            BackendMode.CPU,
            0,
            dynamicBlockBase = 2,
            reuseDecoderInputMap = true,
            profileId = "dynamic_block_2",
            profileTitle = "动态分块2",
        ),
    ),
    DYNAMIC_BLOCK_4(
        "动态分块4",
        "在 Decoder 映射复用上启用 ORT 动态任务分块系数 4；官方以 4 作为典型试验值。",
        EngineConfig(
            BackendMode.CPU,
            0,
            dynamicBlockBase = 4,
            reuseDecoderInputMap = true,
            profileId = "dynamic_block_4",
            profileTitle = "动态分块4",
        ),
    ),
    DYNAMIC_BLOCK_8(
        "动态分块8",
        "在 Decoder 映射复用上启用更细的 ORT 动态任务分块系数 8，检验收益是否继续扩大。",
        EngineConfig(
            BackendMode.CPU,
            0,
            dynamicBlockBase = 8,
            reuseDecoderInputMap = true,
            profileId = "dynamic_block_8",
            profileTitle = "动态分块8",
        ),
    ),
}

data class ModelLoadInfo(val loadedThisRun: Boolean, val elapsedMs: Long)
data class AssetIntegrity(val bytes: Long, val sha256: String)

data class BenchmarkManifest(
    val version: String,
    val character: String,
    val sampleRate: Int,
    val models: Map<String, String>,
    val sharedTensors: List<TensorSpec>,
    val featureModes: List<FeatureMode>,
    val presetFeatureTitle: String,
    val presetFeatureDescription: String,
    val presets: List<TextPreset>,
    val frontend: FrontendSpec,
    val cases: List<BenchmarkCase>,
    val encoderInputNames: List<String>,
    val firstStageInputNames: List<String>,
    val stageInputNames: List<String>,
    val vocoderInputNames: List<String>,
    val assetFiles: List<String>,
    val assetIntegrity: Map<String, AssetIntegrity>,
) {
    companion object {
        fun parse(text: String): BenchmarkManifest {
            val root = JSONObject(text)
            fun strings(key: String) = root.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            fun tensor(obj: JSONObject): TensorSpec {
                val shape = obj.getJSONArray("shape")
                return TensorSpec(obj.getString("name"), obj.getString("file"), obj.getString("dtype"), LongArray(shape.length()) { shape.getLong(it) })
            }
            fun tensors(obj: JSONObject, key: String) = obj.getJSONArray(key).let { a -> List(a.length()) { tensor(a.getJSONObject(it)) } }
            val modelObject = root.getJSONObject("models")
            val models = modelObject.keys().asSequence().associateWith { modelObject.getString(it) }
            val assetIntegrity = root.optJSONObject("asset_integrity")?.let { integrityObject ->
                integrityObject.keys().asSequence().associateWith { path ->
                    val item = integrityObject.getJSONObject(path)
                    AssetIntegrity(item.getLong("bytes"), item.getString("sha256"))
                }
            } ?: emptyMap()
            val featureModes = root.getJSONArray("feature_modes").let { array ->
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    FeatureMode(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        description = item.getString("description"),
                        tensors = tensors(item, "tensors"),
                        bertNonZero = item.getLong("bert_nonzero"),
                        bertElements = item.getLong("bert_elements"),
                        toneDiagnostic = item.getString("tone_diagnostic"),
                    )
                }
            }
            val presets = root.getJSONArray("presets").let { array ->
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    TextPreset(
                        item.getString("id"), item.getString("title"), item.getString("text"),
                        tensors(item, "tensors"), item.getLong("bert_nonzero"), item.getLong("bert_elements")
                    )
                }
            }
            val frontendObject = root.getJSONObject("frontend")
            val frontend = FrontendSpec(
                frontendObject.getString("roberta"), frontendObject.getString("vocab"),
                frontendObject.getString("char_phones"), frontendObject.getString("phrase_phones"),
                frontendObject.getString("punctuation_ids"), frontendObject.getInt("max_phrase_chars"),
                frontendObject.getInt("bert_dim"), frontendObject.getString("quantization"),
                frontendObject.getLong("roberta_bytes"), frontendObject.getString("roberta_sha256"),
                frontendObject.getBoolean("roberta_external")
            )
            val caseArray = root.getJSONArray("cases")
            val cases = List(caseArray.length()) { index ->
                val item = caseArray.getJSONObject(index)
                BenchmarkCase(
                    item.getString("id"), item.getString("title"), item.getString("text"), tensors(item, "tensors"),
                    item.optString("reference_text").takeIf { it.isNotBlank() },
                    item.optString("reference_audio").takeIf { it.isNotBlank() },
                    item.optDouble("playback_gain_db", 0.0),
                )
            }
            return BenchmarkManifest(
                root.getString("version"), root.getString("character"), root.getInt("sample_rate"), models,
                tensors(root, "shared_tensors"), featureModes,
                root.optString("preset_feature_title", "完整 Chinese RoBERTa"),
                root.optString("preset_feature_description", "预计算 FP32；非零中文特征"),
                presets, frontend, cases,
                strings("encoder_input_names"), strings("first_stage_input_names"),
                strings("stage_input_names"), strings("vocoder_input_names"), strings("asset_files"),
                assetIntegrity,
            )
        }
    }
}

data class BenchmarkResult(
    val config: EngineConfig, val featureModeTitle: String, val featureDescription: String,
    val caseTitle: String, val targetTitle: String, val text: String, val normalizedText: String,
    val frontendMs: Long, val frontendDiagnostic: String, val modelLoadedThisRun: Boolean,
    val modelLoadMs: Long, val fixtureLoadMs: Long,
    val encoderMs: Long, val firstDecoderMs: Long, val autoregressiveMs: Long, val vocoderMs: Long,
    val totalInferenceMs: Long, val decoderIterations: Int, val audioSeconds: Double, val coreRtf: Double,
    val endToEndMs: Long, val semanticTokens: Int, val semanticHash: String,
    val audioPeak: Double, val audioRms: Double, val clippedPercent: Double,
    val playbackGainDb: Double, val pssMb: Int, val audio: FloatArray,
) {
    fun report(deviceLine: String, runNumber: Int? = null): String = buildString {
        appendLine("Genie-TTS Android 小酒狐三语测试 v0.8.2")
        appendLine(deviceLine)
        appendLine("配置：${config.label}${runNumber?.let { " · 第 ${it} 轮" } ?: ""}")
        appendLine("语言前端：$featureModeTitle")
        appendLine("特征说明：$featureDescription")
        appendLine("测试：$caseTitle")
        appendLine("台词类型：$targetTitle")
        appendLine("文本：$text")
        if (normalizedText != text) appendLine("规范化文本：$normalizedText")
        appendLine("文本前处理：${frontendMs} ms · $frontendDiagnostic")
        if (modelLoadedThisRun) {
            appendLine("本轮模型状态：冷加载 ${modelLoadMs} ms（不计入核心推理）")
        } else {
            appendLine("本轮模型状态：复用已加载模型")
        }
        appendLine("测试张量读取：${fixtureLoadMs} ms")
        appendLine("T2S Encoder：${encoderMs} ms")
        appendLine("首步 Decoder：${firstDecoderMs} ms")
        appendLine("自回归 Decoder：${autoregressiveMs} ms / $decoderIterations 次")
        appendLine("VITS：${vocoderMs} ms")
        appendLine("核心推理：${totalInferenceMs} ms")
        appendLine("端到端等待：${endToEndMs} ms")
        appendLine("音频时长：${"%.3f".format(audioSeconds)} s")
        appendLine("RTF：${"%.3f".format(coreRtf)}（小于 1 才快于实时）")
        appendLine("语义序列：$semanticTokens tokens · $semanticHash")
        appendLine("波形：峰值 ${"%.4f".format(audioPeak)} · RMS ${"%.4f".format(audioRms)} · 近削波 ${"%.4f".format(clippedPercent)}%")
        if (playbackGainDb != 0.0) appendLine("播放增益：${"%+.1f".format(playbackGainDb)} dB（仅播放，不改变推理）")
        appendLine("PSS：约 $pssMb MB")
    }
}
