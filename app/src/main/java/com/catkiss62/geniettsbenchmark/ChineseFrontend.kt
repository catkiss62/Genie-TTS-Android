package com.catkiss62.geniettsbenchmark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max

class ChineseFrontend(private val engine: GenieBenchmarkEngine) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private var robertaSession: OrtSession? = null
    private var robertaPath: String? = null
    private var vocab: Map<String, Long>? = null
    private var charPhones: Map<String, List<LongArray>>? = null
    private var phrasePhones: Map<String, List<LongArray>>? = null
    private var punctuation: Map<String, List<LongArray>>? = null
    private val cache = object : LinkedHashMap<String, PreparedText>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedText>?) = size > 4
    }

    fun clearPreparedCache() = synchronized(cache) { cache.clear() }

    fun closeModel() {
        robertaSession?.close()
        robertaSession = null
        robertaPath = null
    }

    override fun close() = closeModel()

    fun prepare(root: File, original: String, progress: (String) -> Unit): PreparedText {
        val text = original.trim()
        require(text.isNotEmpty()) { "请输入要生成的台词" }
        synchronized(cache) {
            cache[text]?.let { return it.copy(frontendMs = 0L, diagnostic = it.diagnostic + " · 命中缓存") }
        }
        val started = System.nanoTime()
        val info = engine.readManifest().frontend
        ensureDictionaries(root, info, progress)
        val ignoredKana = text.count(::isJapaneseKana)
        val latin = expandLatin(text)
        val normalized = normalize(latin.text)
        require(normalized.length <= 81) {
            "英文转写后超过 80 个规范化字符，请缩短本段文字"
        }
        progress("正在生成中文音素……")
        val phoneResult = phonesFor(normalized, info.maxPhraseChars)
        val tokenIds = LongArray(normalized.length + 2)
        tokenIds[0] = 101L
        normalized.forEachIndexed { index, char ->
            tokenIds[index + 1] = vocab!!.getOrDefault(char.toString(), 100L)
        }
        tokenIds[tokenIds.lastIndex] = 102L

        progress("正在运行本地 INT8 Chinese RoBERTa……")
        val rawBert = runRoberta(root, info, tokenIds)
        val expectedRows = normalized.length + 2
        check(rawBert.size == expectedRows * info.bertDim) {
            "RoBERTa 输出尺寸异常：${rawBert.size}，预期 ${expectedRows * info.bertDim}"
        }
        val expanded = FloatArray(phoneResult.sequence.size * info.bertDim)
        var targetRow = 0
        phoneResult.word2ph.forEachIndexed { charIndex, repeats ->
            repeat(repeats) {
                System.arraycopy(
                    rawBert, (charIndex + 1) * info.bertDim,
                    expanded, targetRow * info.bertDim, info.bertDim
                )
                targetRow += 1
            }
        }
        check(expanded.any { it != 0f } && expanded.all { it.isFinite() }) { "Chinese RoBERTa 输出无效" }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        val prepared = PreparedText(
            text = text,
            normalizedText = normalized.removePrefix("."),
            sequence = phoneResult.sequence,
            bert = expanded,
            bertDim = info.bertDim,
            frontendMs = elapsed,
            diagnostic = buildString {
                append("本地 INT8 RoBERTa · ${normalized.length - 1}字符/${phoneResult.sequence.size}音素 · ")
                append("词组命中${phoneResult.phraseHits}次")
                if (latin.spelledLetters > 0) append(" · 英文字母逐读${latin.spelledLetters}个")
                if (latin.tokenHits > 0) append(" · token→拖肯 ${latin.tokenHits}次")
                if (ignoredKana > 0) append(" · 已忽略日语假名${ignoredKana}个")
            },
        )
        synchronized(cache) { cache[text] = prepared }
        return prepared
    }

    private fun ensureDictionaries(root: File, info: FrontendSpec, progress: (String) -> Unit) {
        if (vocab != null) return
        progress("首次加载手机端中文词典……")
        vocab = readSimpleMap(File(root, info.vocab)) { it.toLong() }
        charPhones = readPhoneMap(File(root, info.charPhones))
        phrasePhones = readPhoneMap(File(root, info.phrasePhones))
        punctuation = readPhoneMap(File(root, info.punctuationIds), singleGroup = true)
    }

    private fun <T> readSimpleMap(file: File, convert: (String) -> T): Map<String, T> {
        val result = HashMap<String, T>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2) result[parts[0]] = convert(parts[1])
        }
        return result
    }

    private fun readPhoneMap(file: File, singleGroup: Boolean = false): Map<String, List<LongArray>> {
        return readSimpleMap(file) { encoded ->
            if (singleGroup) {
                listOf(longArrayOf(encoded.toLong()))
            } else {
                encoded.split('|').map { group -> group.split(',').map(String::toLong).toLongArray() }
            }
        }
    }

    private data class PhoneResult(
        val sequence: LongArray,
        val word2ph: IntArray,
        val phraseHits: Int,
    )

    private fun phonesFor(text: String, maxPhraseChars: Int): PhoneResult {
        val groups = ArrayList<LongArray>()
        var phraseHits = 0
        var index = 0
        while (index < text.length) {
            val character = text[index].toString()
            punctuation!![character]?.let {
                groups.addAll(it)
                index += 1
                return@let
            } ?: run {
                var matched: List<LongArray>? = null
                var matchedLength = 0
                for (length in minOf(maxPhraseChars, text.length - index) downTo 2) {
                    val candidate = text.substring(index, index + length)
                    phrasePhones!![candidate]?.let {
                        matched = it
                        matchedLength = length
                        return@let
                    }
                    if (matched != null) break
                }
                if (matched != null) {
                    check(matched!!.size == matchedLength) { "词典条目长度异常" }
                    groups.addAll(matched!!)
                    phraseHits += 1
                    index += matchedLength
                } else {
                    val fallback = charPhones!![character]
                        ?: error("暂不支持字符“$character”，请换成常用中文")
                    groups.addAll(fallback)
                    index += 1
                }
            }
        }
        val sequence = LongArray(groups.sumOf { it.size })
        val word2ph = IntArray(groups.size)
        var offset = 0
        groups.forEachIndexed { groupIndex, group ->
            word2ph[groupIndex] = group.size
            group.copyInto(sequence, offset)
            offset += group.size
        }
        check(word2ph.size == text.length) { "中文音素与文本长度不一致" }
        return PhoneResult(sequence, word2ph, phraseHits)
    }

    private data class LatinExpansion(
        val text: String,
        val spelledLetters: Int,
        val tokenHits: Int,
    )

    private fun expandLatin(text: String): LatinExpansion {
        val letterNames = mapOf(
            'A' to "诶", 'B' to "比", 'C' to "西", 'D' to "迪", 'E' to "伊",
            'F' to "艾弗", 'G' to "吉", 'H' to "艾尺", 'I' to "爱", 'J' to "杰",
            'K' to "开", 'L' to "艾勒", 'M' to "艾姆", 'N' to "艾恩", 'O' to "欧",
            'P' to "皮", 'Q' to "丘", 'R' to "阿尔", 'S' to "艾斯", 'T' to "提",
            'U' to "优", 'V' to "维", 'W' to "达布流", 'X' to "艾克斯", 'Y' to "歪",
            'Z' to "贼德",
        )
        val output = StringBuilder()
        var index = 0
        var spelledLetters = 0
        var tokenHits = 0
        while (index < text.length) {
            if (text[index] !in 'A'..'Z' && text[index] !in 'a'..'z') {
                output.append(text[index++])
                continue
            }
            val start = index
            while (index < text.length && (text[index] in 'A'..'Z' || text[index] in 'a'..'z')) index++
            val word = text.substring(start, index)
            if (word.equals("token", ignoreCase = true)) {
                output.append("拖肯")
                tokenHits += 1
            } else {
                word.forEachIndexed { letterIndex, letter ->
                    if (letterIndex > 0) output.append('，')
                    output.append(letterNames.getValue(letter.uppercaseChar()))
                }
                spelledLetters += word.length
            }
        }
        return LatinExpansion(output.toString(), spelledLetters, tokenHits)
    }

    private fun isJapaneseKana(char: Char): Boolean =
        char in '\u3040'..'\u30ff' || char in '\u31f0'..'\u31ff' || char in '\uff66'..'\uff9d'

    private fun normalize(text: String): String {
        val digits = mapOf('0' to '零', '1' to '一', '2' to '二', '3' to '三', '4' to '四',
            '5' to '五', '6' to '六', '7' to '七', '8' to '八', '9' to '九')
        val replacements = mapOf(
            '：' to ',', '；' to ',', '，' to ',', '。' to '.', '！' to '!', '？' to '?',
            '\n' to '.', '·' to ',', '、' to ',', '$' to '.', '/' to ',', '—' to '-',
            '~' to '…', '～' to '…'
        )
        val allowedPunctuation = setOf('!', '?', '…', ',', '.', '-')
        val output = StringBuilder(".")
        text.replace("...", "…").replace("快看快看", "快看，快看").forEach { char ->
            val normalized = when {
                char in digits -> digits.getValue(char)
                char in replacements -> replacements.getValue(char)
                char in '\u4e00'..'\u9fff' || char in allowedPunctuation -> char
                else -> null
            }
            if (normalized != null && !(normalized in allowedPunctuation && output.lastOrNull() == normalized)) {
                output.append(normalized)
            }
        }
        require(output.any { it in '\u4e00'..'\u9fff' }) { "没有找到可生成的中文或英文字母内容" }
        return output.toString()
    }

    private fun runRoberta(root: File, info: FrontendSpec, tokenIds: LongArray): FloatArray {
        val modelPath = engine.frontendModelFile(root).absolutePath
        if (robertaSession == null || robertaPath != modelPath) {
            closeModel()
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(1)
                setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors()))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            robertaSession = try {
                env.createSession(modelPath, options)
            } finally {
                options.close()
            }
            robertaPath = modelPath
        }
        val ortSession = checkNotNull(robertaSession)
        val shape = longArrayOf(1, tokenIds.size.toLong())
        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape)
        val tokenTypes = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(tokenIds.size)), shape)
        val attention = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(tokenIds.size) { 1L }), shape)
        try {
            ortSession.run(mapOf("input_ids" to ids, "token_type_ids" to tokenTypes, "attention_mask" to attention)).use { result ->
                val tensor = result.get(0) as OnnxTensor
                val tensorInfo = tensor.info as TensorInfo
                val values = FloatArray(tensorInfo.shape.fold(1L) { total, item -> total * item }.toInt())
                tensor.floatBuffer.get(values)
                return values
            }
        } finally {
            ids.close()
            tokenTypes.close()
            attention.close()
        }
    }
}
