package com.catkiss62.geniettsbenchmark

import org.json.JSONObject

data class TensorSpec(val name: String, val file: String, val dtype: String, val shape: LongArray)
data class BenchmarkCase(val id: String, val title: String, val text: String, val tensors: List<TensorSpec>)

data class BenchmarkManifest(
    val version: String,
    val character: String,
    val sampleRate: Int,
    val models: Map<String, String>,
    val sharedTensors: List<TensorSpec>,
    val cases: List<BenchmarkCase>,
    val encoderInputNames: List<String>,
    val firstStageInputNames: List<String>,
    val stageInputNames: List<String>,
    val vocoderInputNames: List<String>,
    val assetFiles: List<String>,
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
            val caseArray = root.getJSONArray("cases")
            val cases = List(caseArray.length()) { index ->
                val item = caseArray.getJSONObject(index)
                BenchmarkCase(item.getString("id"), item.getString("title"), item.getString("text"), tensors(item, "tensors"))
            }
            return BenchmarkManifest(
                root.getString("version"), root.getString("character"), root.getInt("sample_rate"), models,
                tensors(root, "shared_tensors"), cases, strings("encoder_input_names"), strings("first_stage_input_names"),
                strings("stage_input_names"), strings("vocoder_input_names"), strings("asset_files")
            )
        }
    }
}

data class BenchmarkResult(
    val caseTitle: String, val text: String, val modelLoadMs: Long, val fixtureLoadMs: Long,
    val encoderMs: Long, val firstDecoderMs: Long, val autoregressiveMs: Long, val vocoderMs: Long,
    val totalInferenceMs: Long, val decoderIterations: Int, val audioSeconds: Double, val coreRtf: Double,
    val pssMb: Int, val audio: FloatArray,
) {
    fun report(deviceLine: String): String = buildString {
        appendLine("Genie-TTS Android Benchmark v0.1.0")
        appendLine(deviceLine)
        appendLine("测试：$caseTitle")
        appendLine("文本：$text")
        appendLine("模型加载：${modelLoadMs} ms（仅首次）")
        appendLine("测试张量读取：${fixtureLoadMs} ms")
        appendLine("T2S Encoder：${encoderMs} ms")
        appendLine("首步 Decoder：${firstDecoderMs} ms")
        appendLine("自回归 Decoder：${autoregressiveMs} ms / $decoderIterations 次")
        appendLine("VITS：${vocoderMs} ms")
        appendLine("核心推理：${totalInferenceMs} ms")
        appendLine("音频时长：${"%.3f".format(audioSeconds)} s")
        appendLine("RTF：${"%.3f".format(coreRtf)}（小于 1 才快于实时）")
        appendLine("PSS：约 $pssMb MB")
    }
}

