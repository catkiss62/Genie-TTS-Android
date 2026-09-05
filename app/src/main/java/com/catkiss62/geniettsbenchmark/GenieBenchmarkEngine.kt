package com.catkiss62.geniettsbenchmark

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Debug
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class GenieBenchmarkEngine(private val context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private var manifest: BenchmarkManifest? = null
    private var encoder: OrtSession? = null
    private var firstDecoder: OrtSession? = null
    private var stageDecoder: OrtSession? = null
    private var vocoder: OrtSession? = null
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
        info.assetFiles.forEachIndexed { index, relative ->
            val output = File(root, relative)
            if (!output.exists() || output.length() == 0L) {
                progress("正在释放模型 ${index + 1}/${info.assetFiles.size}：${output.name}")
                output.parentFile?.mkdirs()
                context.assets.open("benchmark/$relative").use { input ->
                    output.outputStream().buffered().use { target -> input.copyTo(target, 1024 * 1024) }
                }
            }
        }
        return root
    }

    fun loadModels(root: File, threads: Int = min(4, Runtime.getRuntime().availableProcessors())): Long {
        if (encoder != null) return lastModelLoadMs
        val info = readManifest()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(1, threads))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val start = System.nanoTime()
        try {
            encoder = env.createSession(File(root, info.models.getValue("encoder")).absolutePath, options)
            firstDecoder = env.createSession(File(root, info.models.getValue("first_decoder")).absolutePath, options)
            stageDecoder = env.createSession(File(root, info.models.getValue("stage_decoder")).absolutePath, options)
            vocoder = env.createSession(File(root, info.models.getValue("vocoder")).absolutePath, options)
        } finally {
            options.close()
        }
        lastModelLoadMs = elapsedMs(start)
        return lastModelLoadMs
    }

    fun run(root: File, case: BenchmarkCase): BenchmarkResult {
        val info = readManifest()
        checkNotNull(encoder) { "请先加载模型" }
        val specs = (info.sharedTensors + case.tensors).associateBy { it.name }
        val fixtureStart = System.nanoTime()
        val loadedInputs = specs.mapValues { readTensor(root, it.value) }
        val fixtureLoadMs = elapsedMs(fixtureStart)
        fun tensor(name: String): OnnxTensor = loadedInputs.getValue(name)

        val encoderInputs = linkedMapOf<String, OnnxTensor>()
        info.encoderInputNames.forEach { encoderInputs[it] = tensor(it) }
        val encoderStart = System.nanoTime()
        val encoderResult = encoder!!.run(encoderInputs)
        val encoderMs = elapsedMs(encoderStart)
        val firstInputs = linkedMapOf<String, OnnxTensor>()
        info.firstStageInputNames.forEachIndexed { index, name -> firstInputs[name] = encoderResult.get(index) as OnnxTensor }
        val firstStart = System.nanoTime()
        var decoderResult = firstDecoder!!.run(firstInputs)
        val firstMs = elapsedMs(firstStart)
        encoderResult.close()

        val autoregressiveStart = System.nanoTime()
        var loopIndex = 0
        var iterations = 0
        while (loopIndex < 500) {
            val stageInputs = linkedMapOf<String, OnnxTensor>()
            info.stageInputNames.forEachIndexed { index, name -> stageInputs[name] = decoderResult.get(index) as OnnxTensor }
            val next = stageDecoder!!.run(stageInputs)
            decoderResult.close()
            decoderResult = next
            iterations += 1
            if (tensorIsTrue(decoderResult.get(2))) break
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
        val semantic = yValues.copyOfRange(yValues.size - semanticCount, yValues.size)
        val semanticTensor = OnnxTensor.createTensor(
            env, java.nio.LongBuffer.wrap(semantic), longArrayOf(1, 1, semanticCount.toLong())
        )
        decoderResult.close()

        val vocoderInputs = linkedMapOf<String, OnnxTensor>()
        info.vocoderInputNames.forEach { name ->
            vocoderInputs[name] = if (name == "pred_semantic") semanticTensor else tensor(name)
        }
        val vocoderStart = System.nanoTime()
        val audioResult = vocoder!!.run(vocoderInputs)
        val vocoderMs = elapsedMs(vocoderStart)
        loadedInputs.values.forEach { it.close() }
        semanticTensor.close()

        val audioTensor = audioResult.get(0) as OnnxTensor
        val audioInfo = audioTensor.info as TensorInfo
        val audio = FloatArray(elementCount(audioInfo.shape))
        audioTensor.floatBuffer.get(audio)
        audioResult.close()

        val total = encoderMs + firstMs + autoregressiveMs + vocoderMs
        val seconds = audio.size.toDouble() / info.sampleRate
        return BenchmarkResult(
            case.title, case.text, lastModelLoadMs, fixtureLoadMs, encoderMs, firstMs,
            autoregressiveMs, vocoderMs, total, iterations, seconds,
            if (seconds > 0.0) total / (seconds * 1000.0) else Double.POSITIVE_INFINITY,
            (Debug.getPss() / 1024L).toInt(), audio
        )
    }

    fun play(audio: FloatArray, sampleRate: Int) {
        audioTrack?.runCatching { stop() }
        audioTrack?.release()
        val pcm = ShortArray(audio.size) { (audio[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
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

    override fun close() {
        audioTrack?.release()
        encoder?.close(); firstDecoder?.close(); stageDecoder?.close(); vocoder?.close()
        encoder = null; firstDecoder = null; stageDecoder = null; vocoder = null
    }
}
