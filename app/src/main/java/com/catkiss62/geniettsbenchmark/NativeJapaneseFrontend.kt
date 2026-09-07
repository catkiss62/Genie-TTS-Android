package com.catkiss62.geniettsbenchmark

import android.content.Context
import java.io.File

class NativeJapaneseFrontend(private val context: Context) : AutoCloseable {
    private var handle = 0L

    fun prepare(text: String, bertDim: Int, progress: (String) -> Unit): PreparedText {
        val input = text.trim()
        require(input.isNotEmpty()) { "日语测试文本不能为空" }
        val started = System.nanoTime()
        ensureInitialized(progress)
        val output = ArrayList<Long>()
        output += GenieSymbolsV2.id.getValue(".")
        val segment = StringBuilder()
        var nativeCalls = 0

        fun flush() {
            if (segment.isEmpty()) return
            output.addAll(phonemizeSegment(segment.toString()).toList())
            segment.clear()
            nativeCalls += 1
        }

        input.forEach { char ->
            val punctuation = when (char) {
                '。', '.' -> "."
                '、', '，', ',', '；', ';', '：', ':' -> ","
                '！', '!' -> "!"
                '？', '?' -> "?"
                else -> null
            }
            if (punctuation == null) {
                segment.append(char)
            } else {
                flush()
                val id = GenieSymbolsV2.id.getValue(punctuation)
                if (output.lastOrNull() != id) output += id
            }
        }
        flush()
        require(output.size > 1) { "OpenJTalk 没有生成有效日语音素" }
        val sequence = output.toLongArray()
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        return PreparedText(
            text = input,
            normalizedText = input,
            sequence = sequence,
            bert = FloatArray(sequence.size * bertDim),
            bertDim = bertDim,
            frontendMs = elapsed,
            diagnostic = "Android OpenJTalk 1.11 · $nativeCalls 段/${sequence.size}音素 · BERT 全零",
        )
    }

    private fun ensureInitialized(progress: (String) -> Unit) {
        if (handle != 0L) return
        val root = File(context.filesDir, "genie-frontends/openjtalk-1.11")
        DICTIONARY_FILES.forEachIndexed { index, name ->
            val target = File(root, name)
            if (!target.isFile || target.length() == 0L) {
                progress("首次释放日语 OpenJTalk 词典 ${index + 1}/${DICTIONARY_FILES.size}：$name")
                target.parentFile?.mkdirs()
                context.assets.open("openjtalk/$name").use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
                }
            }
        }
        handle = nativeCreate(root.absolutePath)
        check(handle != 0L) { "OpenJTalk 初始化失败，请检查日语词典完整性" }
    }

    private fun phonemizeSegment(text: String): LongArray {
        val raw = nativePhonemize(handle, text)
            ?: error("OpenJTalk 转换失败：${nativeLastError(handle)}")
        check(raw.size == 4) { "OpenJTalk 返回结构异常" }
        val phones = raw[0].trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val a1 = parseCsv(raw[1])
        val a2 = parseCsv(raw[2])
        val a3 = parseCsv(raw[3])
        check(phones.size == a1.size && phones.size == a2.size && phones.size == a3.size) {
            "OpenJTalk 韵律数组长度异常"
        }
        val validIndices = phones.indices.filter { phones[it] != "pau" && phones[it] != "sil" }
        val output = ArrayList<String>()
        validIndices.forEachIndexed { compactIndex, sourceIndex ->
            val phone = phones[sourceIndex].let { if (it in listOf("A", "E", "I", "O", "U")) it.lowercase() else it }
            if (phone in GenieSymbolsV2.id) output += phone
            val nextIndex = validIndices.getOrNull(compactIndex + 1)
            val nextA2 = nextIndex?.let { a2[it] } ?: -50
            val dropsUnsupportedPhraseMarker =
                a3[sourceIndex] == 1 && nextA2 == 1 && phone in setOf("a", "e", "i", "o", "u", "N", "cl")
            if (!dropsUnsupportedPhraseMarker) {
                when {
                    a1[sourceIndex] == 0 && nextA2 == a2[sourceIndex] + 1 -> output += "]"
                    a2[sourceIndex] == 1 && nextA2 == 2 -> output += "["
                }
            }
        }
        return GenieSymbolsV2.ids(output)
    }

    private fun parseCsv(value: String): IntArray = if (value.isBlank()) IntArray(0) else
        value.split(',').map(String::toInt).toIntArray()

    private external fun nativeCreate(dictionaryPath: String): Long
    private external fun nativePhonemize(handle: Long, text: String): Array<String>?
    private external fun nativeLastError(handle: Long): String
    private external fun nativeDestroy(handle: Long)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        private val DICTIONARY_FILES = listOf(
            "COPYING", "char.bin", "left-id.def", "matrix.bin", "pos-id.def",
            "rewrite.def", "right-id.def", "sys.dic", "unk.dic",
        )

        init {
            System.loadLibrary("openjtalk_native")
            System.loadLibrary("genie_frontend")
        }
    }
}
