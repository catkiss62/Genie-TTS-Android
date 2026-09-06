package com.catkiss62.geniettsbenchmark

import android.content.Context
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Debug
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min

class GenieBenchmarkEngine(private val context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private var manifest: BenchmarkManifest? = null
    private var encoder: OrtSession? = null
    private var firstDecoder: OrtSession? = null
    private var stageDecoder: OrtSession? = null
    private var vocoder: OrtSession? = null
    private var currentConfig: EngineConfig? = null
    private var lastModelLoadMs = 0L
    private var audioTrack: AudioTrack? = null

    fun readManifest(): BenchmarkManifest {
        manifest?.let { return it }
        val text = context.assets.open("benchmark/manifest.json").bufferedReader().use { it.readText() }
        return BenchmarkManifest.parse(text).also { manifest = it }
    }

    fun prepareAssets(progress: (String) -> Unit): File {
        val info = readManifest()
        val root = File(context.filesDir, "genie-benchmark/${info.version}")
        copyAssets(root, info.assetFiles.filterNot { it.startsWith("frontend/") }, progress)
        return root
    }

    fun prepareFrontendAssets(root: File, progress: (String) -> Unit) {
        val frontend = readManifest().frontend
        copyAssets(root, listOf(
            frontend.vocab, frontend.charPhones,
            frontend.phrasePhones, frontend.punctuationIds
        ), progress)
    }

    fun hasFrontendModel(root: File): Boolean {
        val frontend = readManifest().frontend
        val model = File(root, frontend.roberta)
        val marker = File(root, frontend.roberta + ".sha256")
        return model.isFile && model.length() == frontend.robertaBytes && marker.isFile &&
            marker.readText().trim().equals(frontend.robertaSha256, ignoreCase = true)
    }

    fun importFrontendModel(root: File, uri: Uri, progress: (String) -> Unit) {
        val frontend = readManifest().frontend
        val target = File(root, frontend.roberta)
        val incoming = File(root, frontend.roberta + ".incoming")
        target.parentFile?.mkdirs()
        incoming.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选模型文件")
            input.buffered().use { source ->
                incoming.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        if (copied % (32L * 1024L * 1024L) < count) {
                            progress("正在导入 RoBERTa：${copied / 1024L / 1024L} MB")
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(copied == frontend.robertaBytes) { "模型大小不匹配：$copied / ${frontend.robertaBytes}" }
            check(sha.equals(frontend.robertaSha256, ignoreCase = true)) { "模型 SHA-256 不匹配，请选择配套的 v0.3.2 文件" }
            target.delete()
            check(incoming.renameTo(target)) { "无法保存 RoBERTa 模型" }
            File(root, frontend.roberta + ".sha256").writeText(sha)
        } catch (error: Throwable) {
            incoming.delete()
            throw error
        }
    }

    private fun copyAssets(root: File, files: List<String>, progress: (String) -> Unit) {
        files.forEachIndexed { index, relative ->
            val output = File(root, relative)
            if (!output.exists() || output.length() == 0L) {
                progress("正在释放资源 ${index + 1}/${files.size}：${output.name}")
                output.parentFile?.mkdirs()
                context.assets.open("benchmark/$relative").use { input ->
                    output.outputStream().buffered().use { target -> input.copyTo(target, 1024 * 1024) }
                }
            }
        }
    }

    fun loadModels(root: File, config: EngineConfig): Long {
        if (encoder != null && currentConfig == config) return lastModelLoadMs
        unloadModels()
        System.gc()
        val info = readManifest()
        val start = System.nanoTime()
        try {
            encoder = createSession(File(root, info.models.getValue("encoder")), config, false)
            firstDecoder = createSession(File(root, info.models.getValue("first_decoder")), config, false)
            stageDecoder = createSession(File(root, info.models.getValue("stage_decoder")), config, false)
            vocoder = createSession(File(root, info.models.getValue("vocoder")), config, true)
            currentConfig = config
        } catch (error: Throwable) {
            unloadModels()
            throw error
        }
        lastModelLoadMs = elapsedMs(start)
        return lastModelLoadMs
    }

    fun releaseModelsForFrontend() {
        unloadModels()
        System.gc()
    }

    private fun createSession(model: File, config: EngineConfig, isVocoder: Boolean): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (config.backend) {
                BackendMode.CPU -> setIntraOpNumThreads(max(1, config.threads))
                BackendMode.XNNPACK -> {
                    // XNNPACK owns its worker pool. Keeping ORT's own pool at one thread avoids
                    // two thread pools competing for the same mobile CPU cores.
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(mapOf("intra_op_num_threads" to max(1, config.threads).toString()))
                }
                BackendMode.NNAPI_VITS -> {
                    setIntraOpNumThreads(max(1, config.threads))
                    if (isVocoder) {
                        addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                    }
                }
            }
        }
        return try {
            env.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
    }

    fun runPreset(
        root: File,
        case: BenchmarkCase,
        preset: TextPreset,
        shouldCancel: () -> Boolean = { false },
    ): BenchmarkResult = run(
        root, case, preset.title, preset.text, preset.text, 0L,
        "预计算 FP32 Chinese RoBERTa · ${preset.bertNonZero}/${preset.bertElements} 非零",
        preset.tensors, null, shouldCancel
    )

    fun runPrepared(
        root: File,
        case: BenchmarkCase,
        prepared: PreparedText,
        shouldCancel: () -> Boolean = { false },
    ): BenchmarkResult = run(
        root, case, "自由输入", prepared.text, prepared.normalizedText, prepared.frontendMs,
        prepared.diagnostic, emptyList(), prepared, shouldCancel
    )

    private fun run(
        root: File,
        case: BenchmarkCase,
        targetTitle: String,
        targetText: String,
        normalizedText: String,
        frontendMs: Long,
        frontendDiagnostic: String,
        targetTensors: List<TensorSpec>,
        prepared: PreparedText?,
        shouldCancel: () -> Boolean,
    ): BenchmarkResult {
        val info = readManifest()
        checkNotNull(encoder) { "请先加载模型" }
        val config = checkNotNull(currentConfig) { "缺少当前推理配置" }
        checkCancelled(shouldCancel)
        val specs = (info.sharedTensors + case.tensors + targetTensors).associateBy { it.name }
        val fixtureStart = System.nanoTime()
        val loadedInputs = specs.mapValuesTo(linkedMapOf()) { readTensor(root, it.value) }
        if (prepared != null) {
            loadedInputs["text_seq"] = OnnxTensor.createTensor(
                env, LongBuffer.wrap(prepared.sequence), longArrayOf(1, prepared.sequence.size.toLong())
            )
            loadedInputs["text_bert"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(prepared.bert),
                longArrayOf(prepared.sequence.size.toLong(), prepared.bertDim.toLong())
            )
        }
        val fixtureLoadMs = elapsedMs(fixtureStart)
        fun tensor(name: String): OnnxTensor = loadedInputs.getValue(name)
        try {
            checkCancelled(shouldCancel)
            val encoderInputs = linkedMapOf<String, OnnxTensor>()
            info.encoderInputNames.forEach { encoderInputs[it] = tensor(it) }
            val encoderStart = System.nanoTime()
            val encoderResult = encoder!!.run(encoderInputs)
            val encoderMs = elapsedMs(encoderStart)
            val firstInputs = linkedMapOf<String, OnnxTensor>()
            info.firstStageInputNames.forEachIndexed { index, name -> firstInputs[name] = encoderResult.get(index) as OnnxTensor }
            checkCancelled(shouldCancel)
            val firstStart = System.nanoTime()
            var decoderResult = firstDecoder!!.run(firstInputs)
            val firstMs = elapsedMs(firstStart)
            encoderResult.close()

            val autoregressiveStart = System.nanoTime()
            var loopIndex = 0
            var iterations = 0
            var stageOutputsIncludeStopCondition = false
            while (loopIndex < 500) {
                checkCancelled(shouldCancel)
                val stageInputs = linkedMapOf<String, OnnxTensor>()
                info.stageInputNames.forEachIndexed { index, name ->
                    // The first decoder returns [y, y_emb, *present_key_values], while every
                    // stage decoder call returns [y, y_emb, stop_condition, *present_key_values].
                    // stop_condition is bool and must not be fed back into the float cache input.
                    val outputIndex = if (stageOutputsIncludeStopCondition && index >= 2) index + 1 else index
                    stageInputs[name] = decoderResult.get(outputIndex) as OnnxTensor
                }
                val next = stageDecoder!!.run(stageInputs)
                decoderResult.close()
                decoderResult = next
                iterations += 1
                if (tensorIsTrue(decoderResult.get(2))) break
                stageOutputsIncludeStopCondition = true
                loopIndex += 1
            }
            val autoregressiveMs = elapsedMs(autoregressiveStart)

            val yTensor = decoderResult.get(0) as OnnxTensor
            val yInfo = yTensor.info as TensorInfo
            val yValues = LongArray(elementCount(yInfo.shape))
            yTensor.longBuffer.get(yValues)
            if (yValues.isNotEmpty()) yValues[yValues.lastIndex] = 0L
            val requested = if (loopIndex == 0) yValues.size else loopIndex
            val semanticCount = min(max(1, requested), yValues.size)
            val selectedSemantic = yValues.copyOfRange(yValues.size - semanticCount, yValues.size)
            // Match Genie's Python inference: remove the first invalid/EOS token and anything
            // after it before the VITS call. Usually the forced final zero means this is a no-op,
            // but keeping the guard prevents an early invalid token from reaching the vocoder.
            val firstInvalid = selectedSemantic.indexOfFirst { it >= 1024L }
            val semantic = when {
                firstInvalid > 0 -> selectedSemantic.copyOf(firstInvalid)
                firstInvalid == 0 -> longArrayOf(0L)
                else -> selectedSemantic
            }
            val semanticTensor = OnnxTensor.createTensor(
                env, java.nio.LongBuffer.wrap(semantic), longArrayOf(1, 1, semantic.size.toLong())
            )
            decoderResult.close()

            checkCancelled(shouldCancel)
            val vocoderInputs = linkedMapOf<String, OnnxTensor>()
            info.vocoderInputNames.forEach { name ->
                vocoderInputs[name] = if (name == "pred_semantic") semanticTensor else tensor(name)
            }
            val vocoderStart = System.nanoTime()
            val audioResult = try {
                vocoder!!.run(vocoderInputs)
            } finally {
                semanticTensor.close()
            }
            val vocoderMs = elapsedMs(vocoderStart)

            val audioTensor = audioResult.get(0) as OnnxTensor
            val audioInfo = audioTensor.info as TensorInfo
            val audio = FloatArray(elementCount(audioInfo.shape))
            audioTensor.floatBuffer.get(audio)
            audioResult.close()
            check(audio.isNotEmpty() && audio.all { it.isFinite() }) { "VITS 输出了无效音频" }

            val total = encoderMs + firstMs + autoregressiveMs + vocoderMs
            val seconds = audio.size.toDouble() / info.sampleRate
            return BenchmarkResult(
                config, "完整 Chinese RoBERTa", "预设 FP32 / 自由输入 INT8；均为非零中文特征",
                case.title, targetTitle, targetText, normalizedText, frontendMs, frontendDiagnostic,
                lastModelLoadMs, fixtureLoadMs, encoderMs, firstMs,
                autoregressiveMs, vocoderMs, total, iterations, seconds,
                if (seconds > 0.0) total / (seconds * 1000.0) else Double.POSITIVE_INFINITY,
                (Debug.getPss() / 1024L).toInt(), audio
            )
        } finally {
            loadedInputs.values.forEach { runCatching { it.close() } }
        }
    }

    fun play(audio: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(audio.size) { (audio[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        playPcm(pcm, sampleRate)
    }

    fun playReferenceAsset(relative: String) {
        val bytes = context.assets.open("benchmark/$relative").use { it.readBytes() }
        check(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "参考音频不是标准 WAV" }
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = offset + 8
            if (id == "fmt " && size >= 16 && body + 16 <= bytes.size) {
                val fmt = ByteBuffer.wrap(bytes, body, 16).order(ByteOrder.LITTLE_ENDIAN)
                format = fmt.short.toInt() and 0xffff
                channels = fmt.short.toInt() and 0xffff
                sampleRate = fmt.int
                fmt.int
                fmt.short
                bits = fmt.short.toInt() and 0xffff
            } else if (id == "data") {
                dataOffset = body
                dataSize = min(size, bytes.size - body)
                break
            }
            offset = body + size + (size and 1)
        }
        check(format == 1 && channels == 1 && bits == 16 && dataOffset >= 0) {
            "只支持单声道 PCM16 WAV（当前 format=$format, channels=$channels, bits=$bits）"
        }
        val shorts = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(shorts.remaining())
        shorts.get(pcm)
        playPcm(pcm, sampleRate)
    }

    private fun playPcm(pcm: ShortArray, sampleRate: Int) {
        audioTrack?.runCatching { stop() }
        audioTrack?.release()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuffer, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        audioTrack!!.write(pcm, 0, pcm.size)
        audioTrack!!.play()
    }

    private fun readTensor(root: File, spec: TensorSpec): OnnxTensor {
        val bytes = File(root, spec.file).readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(bytes).flip()
        return when (spec.dtype) {
            "float32" -> OnnxTensor.createTensor(env, buffer.asFloatBuffer(), spec.shape)
            "int64" -> OnnxTensor.createTensor(env, buffer.asLongBuffer(), spec.shape)
            else -> error("不支持的张量类型：${spec.dtype}")
        }
    }

    private fun tensorIsTrue(value: ai.onnxruntime.OnnxValue): Boolean {
        fun anyTrue(item: Any?): Boolean = when (item) {
            is Boolean -> item
            is BooleanArray -> item.any { it }
            is Array<*> -> item.any { anyTrue(it) }
            else -> false
        }
        return anyTrue(value.value)
    }

    private fun elementCount(shape: LongArray) = shape.fold(1L) { a, b -> a * b }.toInt()
    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000L
    private fun checkCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) throw CancellationException("用户停止了测试")
    }

    fun unloadModels() {
        encoder?.close(); firstDecoder?.close(); stageDecoder?.close(); vocoder?.close()
        encoder = null; firstDecoder = null; stageDecoder = null; vocoder = null
        currentConfig = null
        lastModelLoadMs = 0L
    }

    override fun close() {
        audioTrack?.release()
        unloadModels()
    }
}
