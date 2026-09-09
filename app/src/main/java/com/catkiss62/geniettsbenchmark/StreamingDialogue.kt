package com.catkiss62.geniettsbenchmark

enum class DialogueLanguage(
    val title: String,
    val firstTargetChars: Int,
    val targetChars: Int,
    val maxChars: Int,
) {
    CHINESE("中文", 24, 42, 54),
    ENGLISH("英文", 55, 88, 110),
    JAPANESE("日文", 24, 42, 54),
}

enum class DialogueLengthMode(val title: String, val maxTokens: Int) {
    SHORT("普通短对话", 320),
    LONG("约 1000 字长对话", 2_600),
}

enum class DialogueStreamSource(val title: String) {
    SIMULATED("模拟流式（排除网络波动）"),
    DEEPSEEK("DeepSeek API 真流式"),
}

data class DeepSeekModelOption(val id: String, val title: String)

object DeepSeekModelCatalog {
    val all = listOf(
        DeepSeekModelOption("deepseek-v4-flash", "DeepSeek V4 Flash（常规）"),
        DeepSeekModelOption(
            "deepseek-v4.1-flash-expires-on-0910",
            "DeepSeek V4.1 Flash（临时，0910 到期）",
        ),
    )
}

data class ClosedDialogueSegment(
    val text: String,
    val receivedChars: Int,
)

/**
 * Incremental hard-bounded splitter. Unlike the MoeChat reference it never needs a following
 * network delta to release punctuation and never lets an unpunctuated sentence grow forever.
 */
class StreamingDialogueSegmenter(private val language: DialogueLanguage) {
    companion object {
        private val HARD_STOPS = setOf('。', '！', '？', '.', '!', '?', '；', ';', '\n')
        private val SOFT_STOPS = setOf('，', ',', '、', '：', ':', ' ', '—')
        private val TRAILING_CLOSERS = setOf('”', '’', '」', '』', '）', ')', '】', '》')
    }

    private val buffer = StringBuilder()
    private var firstSegment = true
    private var receivedChars = 0

    fun addDelta(delta: String): List<ClosedDialogueSegment> {
        if (delta.isEmpty()) return emptyList()
        val cleaned = delta.replace("\r\n", "\n").replace('\r', '\n')
        buffer.append(cleaned)
        receivedChars += cleaned.length
        return drain(flush = false)
    }

    fun finish(): List<ClosedDialogueSegment> = drain(flush = true)

    fun pendingText(): String = buffer.toString()

    private fun drain(flush: Boolean): List<ClosedDialogueSegment> {
        val result = ArrayList<ClosedDialogueSegment>()
        while (buffer.isNotEmpty()) {
            val target = if (firstSegment) language.firstTargetChars else language.targetChars
            val hardBoundary = firstHardBoundary()
            val cut = when {
                hardBoundary > 0 -> hardBoundary
                buffer.length >= target -> preferredSoftBoundary(target)
                else -> -1
            }.let { candidate ->
                when {
                    candidate > 0 -> candidate
                    buffer.length >= language.maxChars -> hardMaximumBoundary()
                    flush -> buffer.length
                    else -> -1
                }
            }
            if (cut <= 0) break
            val text = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            while (buffer.isNotEmpty() && buffer.first().isWhitespace()) buffer.deleteCharAt(0)
            if (text.isNotEmpty()) {
                result += ClosedDialogueSegment(text, receivedChars)
                firstSegment = false
            }
        }
        return result
    }

    private fun firstHardBoundary(): Int {
        for (index in buffer.indices) {
            if (buffer[index] !in HARD_STOPS) continue
            var end = index + 1
            while (end < buffer.length && (buffer[end] in HARD_STOPS || buffer[end] in TRAILING_CLOSERS)) end += 1
            return end
        }
        return -1
    }

    private fun preferredSoftBoundary(target: Int): Int {
        val upper = minOf(buffer.length, language.maxChars)
        val lower = maxOf(2, target / 2)
        for (index in upper - 1 downTo lower) {
            if (buffer[index] in SOFT_STOPS) return index + 1
        }
        return -1
    }

    private fun hardMaximumBoundary(): Int {
        for (index in language.maxChars - 1 downTo language.maxChars / 2) {
            if (buffer[index] in SOFT_STOPS) return index + 1
        }
        return language.maxChars
    }
}

object DialogueFixtures {
    private const val SHORT_ZH = "刚才我突然想到一件挺有意思的小事，第一时间就想讲给你听。你先别笑，等我慢慢说完。"
    private const val SHORT_EN = "I just remembered a funny little moment from today, and you were the first person I wanted to tell. Give me a minute and I will tell you the whole story."
    private const val SHORT_JA = "さっき今日の小さな出来事を思い出して、最初にあなたへ話したくなりました。笑わないで、ゆっくり最後まで聞いてくださいね。"

    private val LONG_ZH = listOf(
        "刚才房间安静下来以后，我忽然想起今天发生的一件小事。它其实没有多么特别，只是下午的光刚好落在窗边，桌上的杯子还冒着一点热气，我突然觉得这个普通的瞬间很值得记住。",
        "我们每天都会遇到很多这样的片段。当时可能没有在意，过一会儿再回头看，却会发现它们悄悄改变了那一天的心情。可能是路边亮起的一盏灯，也可能是耳机里随机播放到的一首旧歌。",
        "有时候真正让人放松的，并不是一件很大的好事，而是终于有人愿意坐下来听你把话说完。你不需要提前整理好顺序，想到哪里就说到哪里，中途停下来也完全没有关系。",
        "如果今天有让你开心的事情，我们就多聊一会儿；如果有让你烦躁的事情，也可以直接吐槽。话题不用郑重，更不用得出什么结论。我们可以顺着一个很小的细节，一路聊到完全没有预料的地方。",
        "也许过一段时间以后，我们会忘记今天具体说过哪些句子，但会记得当时的感觉。那种有人回应、有人接住话题、偶尔还会故意逗你一下的感觉，本身就很珍贵。",
        "所以现在不用赶时间。你可以先从眼前最简单的事情开始，比如今天吃了什么、路上看见了什么，或者此刻最想做什么。我已经准备好认真听了，不过也不保证一直老老实实不插嘴。",
        "等你讲完以后，我也会把我的版本告诉你。可能有点啰嗦，可能会突然跑题，还可能把一个普通的小插曲说得特别夸张。反正只要最后能一起笑出来，这段时间就没有被浪费。",
        "总之，接下来的话不用一次说完。安静也是对话的一部分，停顿并不意味着冷场。按照你舒服的速度慢慢来就好，我会记住刚才停在了哪里，然后继续陪你往下聊。",
    ).joinToString("")

    private val LONG_EN = List(6) {
        "When the room became quiet, I remembered a small moment from earlier today. Nothing dramatic happened, but the afternoon light reached the window at just the right angle, and the warm cup on the table made the whole room feel softer. We often forget details like that, even though they can quietly change the mood of an ordinary day. If you want, tell me one small thing you noticed. It does not need to be important, funny, or complete. Start wherever you like, pause whenever you need, and let the conversation wander. I will listen carefully, although I cannot promise that I will never interrupt with a playful comment. "
    }.joinToString("")

    private val LONG_JA = List(5) {
        "部屋が静かになったとき、今日あった小さな出来事を思い出しました。特別な事件ではないけれど、午後の光が窓から差し込んで、まだ温かいお茶が机の上にあって、その普通の瞬間を覚えておきたいと思ったんです。毎日の中には、気づいてもすぐ忘れてしまうことがたくさんあります。でも、遠くから聞こえた音楽や、ちょうどいいタイミングで届いた言葉のように、小さなものが一日を優しくしてくれることもあります。よかったら、あなたが今日見つけたことも教えてください。立派な話でなくても大丈夫です。思いついたところから始めて、途中で止まっても、話題が変わっても気にしないでください。私はここでゆっくり聞いています。"
    }.joinToString("")

    fun text(language: DialogueLanguage, length: DialogueLengthMode): String = when (length) {
        DialogueLengthMode.SHORT -> when (language) {
            DialogueLanguage.CHINESE -> SHORT_ZH
            DialogueLanguage.ENGLISH -> SHORT_EN
            DialogueLanguage.JAPANESE -> SHORT_JA
        }
        DialogueLengthMode.LONG -> when (language) {
            DialogueLanguage.CHINESE -> LONG_ZH
            DialogueLanguage.ENGLISH -> LONG_EN
            DialogueLanguage.JAPANESE -> LONG_JA
        }
    }

    fun systemPrompt(language: DialogueLanguage, length: DialogueLengthMode): String {
        val languageRule = when (language) {
            DialogueLanguage.CHINESE -> "只使用自然中文"
            DialogueLanguage.ENGLISH -> "Use natural English only"
            DialogueLanguage.JAPANESE -> "自然な日本語だけを使う"
        }
        val lengthRule = when (length) {
            DialogueLengthMode.SHORT -> "总长度约 60 到 150 个字符"
            DialogueLengthMode.LONG -> "总长度约 800 到 1200 个字符"
        }
        return "$languageRule。$lengthRule。只输出适合直接朗读的对话正文；不要标题、列表、Markdown、括号标签或引号。使用自然的句号、问号、感叹号和逗号。"
    }
}
