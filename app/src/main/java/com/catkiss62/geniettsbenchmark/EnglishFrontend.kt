package com.catkiss62.geniettsbenchmark

import android.content.Context
import java.util.Locale
import java.util.zip.GZIPInputStream

data class EnglishPhoneResult(
    val sequence: LongArray,
    val dictionaryHits: Int,
    val hotwordHits: Int,
    val spelledFallbacks: Int,
)

/** Lightweight Android port of the dictionary-first part of Genie's English G2P. */
class EnglishFrontend(private val context: Context) {
    private var dictionary: Map<String, LongArray>? = null

    private val hotwords = mapOf(
        "deepseek" to phones("D IY1 P S IY1 K"),
        "token" to phones("T OW1 K AH0 N"),
        "ai" to phones("EY1 AY1"),
        "api" to phones("EY1 P IY1 AY1"),
        "gpt" to phones("JH IY1 P IY1 T IY1"),
        "cpu" to phones("S IY1 P IY1 Y UW1"),
        "gpu" to phones("JH IY1 P IY1 Y UW1"),
        "onnx" to phones("AA1 N EH1 K S"),
    )

    private val letterPhones = mapOf(
        'A' to phones("EY1"), 'B' to phones("B IY1"), 'C' to phones("S IY1"),
        'D' to phones("D IY1"), 'E' to phones("IY1"), 'F' to phones("EH1 F"),
        'G' to phones("JH IY1"), 'H' to phones("EY1 CH"), 'I' to phones("AY1"),
        'J' to phones("JH EY1"), 'K' to phones("K EY1"), 'L' to phones("EH1 L"),
        'M' to phones("EH1 M"), 'N' to phones("EH1 N"), 'O' to phones("OW1"),
        'P' to phones("P IY1"), 'Q' to phones("K Y UW1"), 'R' to phones("AA1 R"),
        'S' to phones("EH1 S"), 'T' to phones("T IY1"), 'U' to phones("Y UW1"),
        'V' to phones("V IY1"), 'W' to phones("D AH1 B AH0 L Y UW0"),
        'X' to phones("EH1 K S"), 'Y' to phones("W AY1"), 'Z' to phones("Z IY1"),
    )

    fun prepare(text: String, bertDim: Int, progress: (String) -> Unit): PreparedText {
        val started = System.nanoTime()
        val result = phoneIds(text, progress)
        val sequence = if (result.sequence.firstOrNull() == GenieSymbolsV2.id.getValue(".")) {
            result.sequence
        } else {
            longArrayOf(GenieSymbolsV2.id.getValue(".")) + result.sequence
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        return PreparedText(
            text = text,
            normalizedText = text,
            sequence = sequence,
            bert = FloatArray(sequence.size * bertDim),
            bertDim = bertDim,
            frontendMs = elapsed,
            diagnostic = "本地 CMUdict · 词典命中${result.dictionaryHits} · 热词命中${result.hotwordHits} · " +
                "逐字母回退${result.spelledFallbacks} · ${sequence.size}音素 · BERT 全零",
        )
    }

    fun phoneIds(text: String, progress: (String) -> Unit = {}): EnglishPhoneResult {
        ensureDictionary(progress)
        val output = ArrayList<Long>()
        var dictionaryHits = 0
        var hotwordHits = 0
        var spelledFallbacks = 0
        val tokenRegex = Regex("[A-Za-z]+(?:'[A-Za-z]+)?|[0-9]+|[.,!?;:—-]")
        tokenRegex.findAll(normalize(text)).forEach { match ->
            val token = match.value
            val punctuation = when (token) {
                ";", ":", "—" -> ","
                else -> token
            }
            GenieSymbolsV2.id[punctuation]?.let {
                if (output.lastOrNull() != it) output += it
                return@forEach
            }
            val key = token.lowercase(Locale.US)
            val tokenPhones = when {
                hotwords.containsKey(key) -> hotwords.getValue(key).also { hotwordHits++ }
                dictionary!!.containsKey(key) -> dictionary!!.getValue(key).also { dictionaryHits++ }
                token.all(Char::isDigit) -> token.flatMap { digit ->
                    val word = DIGIT_WORDS.getValue(digit)
                    dictionary!![word]?.toList() ?: emptyList()
                }.toLongArray().also { spelledFallbacks++ }
                else -> token.uppercase(Locale.US).flatMap { letter ->
                    letterPhones[letter]?.toList() ?: emptyList()
                }.toLongArray().also { spelledFallbacks++ }
            }
            output.addAll(tokenPhones.toList())
        }
        require(output.isNotEmpty()) { "没有找到可生成的英文内容" }
        return EnglishPhoneResult(output.toLongArray(), dictionaryHits, hotwordHits, spelledFallbacks)
    }

    private fun ensureDictionary(progress: (String) -> Unit) {
        if (dictionary != null) return
        progress("首次加载英文 CMU 发音词典……")
        val loaded = HashMap<String, LongArray>(140_000)
        GZIPInputStream(context.assets.open("frontend/english/cmudict.rep.gz")).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank() || line.startsWith(";;;")) return@forEach
                val split = line.trim().split(Regex("\\s+"))
                if (split.size < 2) return@forEach
                val word = split.first().lowercase(Locale.US).replace(Regex("\\(\\d+\\)$"), "")
                if (word !in loaded) {
                    val ids = split.drop(1).mapNotNull(GenieSymbolsV2.id::get).toLongArray()
                    if (ids.size == split.size - 1) loaded[word] = ids
                }
            }
        }
        dictionary = loaded
    }

    private fun normalize(text: String): String = text
        .replace('，', ',').replace('。', '.').replace('！', '!').replace('？', '?')
        .replace('；', ';').replace('：', ':')

    private fun phones(value: String) = GenieSymbolsV2.ids(value.split(' '))

    companion object {
        private val DIGIT_WORDS = mapOf(
            '0' to "zero", '1' to "one", '2' to "two", '3' to "three", '4' to "four",
            '5' to "five", '6' to "six", '7' to "seven", '8' to "eight", '9' to "nine",
        )
    }
}
