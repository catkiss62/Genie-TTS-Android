package com.catkiss62.geniettsbenchmark

enum class DialogueLanguage(
    val title: String,
    val targetChars: Int,
    val maxChars: Int,
) {
    CHINESE("中文", 42, 54),
    ENGLISH("英文", 88, 110),
    JAPANESE("日文", 42, 54),
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

data class PackedDialogueSegment(
    val text: String,
    val sourceUnits: Int,
)

/**
 * Packs only the natural units that are already waiting in the text queue. The first unit is
 * still synthesized immediately; after that, text accumulated while TTS was busy is combined
 * toward the normal target length. There is no timer and therefore no artificial network wait.
 */
object DialogueSegmentPacker {
    fun packPrefix(available: List<String>, language: DialogueLanguage): PackedDialogueSegment {
        require(available.isNotEmpty()) { "没有可拼合的流式文本" }
        val packed = StringBuilder(available.first().trim())
        var used = 1
        while (used < available.size && packed.length < language.targetChars) {
            val next = available[used].trim()
            if (next.isEmpty()) {
                used += 1
                continue
            }
            val separator = if (
                language == DialogueLanguage.ENGLISH &&
                packed.lastOrNull()?.isWhitespace() != true &&
                next.firstOrNull()?.isWhitespace() != true
            ) " " else ""
            if (packed.length + separator.length + next.length > language.maxChars) break
            packed.append(separator).append(next)
            used += 1
        }
        return PackedDialogueSegment(packed.toString(), used)
    }
}

/**
 * Incremental splitter matching the AI companion's immersive-room TTS boundary policy.
 * Natural sentence punctuation closes immediately. Commas are only a safety fallback for an
 * exceptional punctuation-free run; they do not prematurely cut an ordinary sentence merely
 * because it crossed a target length.
 */
class StreamingDialogueSegmenter(private val language: DialogueLanguage) {
    companion object {
        private val HARD_STOPS = setOf('。', '！', '？', '.', '!', '?', '；', ';', '\n')
        private val SOFT_STOPS = setOf('，', ',', '、', '：', ':', ' ', '—')
        private val TRAILING_CLOSERS = setOf('”', '’', '」', '』', '）', ')', '】', '》')
    }

    private val buffer = StringBuilder()
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
            val hardBoundary = firstHardBoundary()
            val safetyBoundary = if (buffer.length > language.maxChars) hardMaximumBoundary() else -1
            val cut = when {
                hardBoundary > 0 && safetyBoundary > 0 -> minOf(hardBoundary, safetyBoundary)
                hardBoundary > 0 -> hardBoundary
                safetyBoundary > 0 -> safetyBoundary
                flush -> buffer.length
                else -> -1
            }
            if (cut <= 0) break
            val text = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            while (buffer.isNotEmpty() && buffer.first().isWhitespace()) buffer.deleteCharAt(0)
            if (text.isNotEmpty()) {
                result += ClosedDialogueSegment(text, receivedChars)
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

    private fun hardMaximumBoundary(): Int {
        for (index in language.maxChars - 1 downTo language.maxChars / 2) {
            if (buffer[index] in SOFT_STOPS) return index + 1
        }
        return language.maxChars
    }
}

/**
 * Plans a deterministic long-text benchmark with the same queue behavior used by the AI
 * companion's immersive room: the first complete sentence is submitted immediately; only the
 * already-available later sentences are packed toward the normal target, without a timer or a
 * wait for future text.
 */
object ImmersiveLongTextPlanner {
    fun split(text: String, language: DialogueLanguage): List<String> {
        require(text.isNotBlank()) { "长文本不能为空" }
        val segmenter = StreamingDialogueSegmenter(language)
        val naturalUnits = buildList {
            addAll(segmenter.addDelta(text))
            addAll(segmenter.finish())
        }.map { it.text }
        if (naturalUnits.size <= 1) return naturalUnits

        val result = ArrayList<String>()
        result += naturalUnits.first()
        val pending = naturalUnits.drop(1).toMutableList()
        while (pending.isNotEmpty()) {
            val packed = DialogueSegmentPacker.packPrefix(pending, language)
            result += packed.text
            repeat(packed.sourceUnits) { pending.removeAt(0) }
        }
        check(result.all { it.length <= language.maxChars }) { "沉浸房间长文本分段超过安全上限" }
        return result
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
        "窗外的声音偶尔会打断思路，但也会带来新的话题。也许是一辆慢慢经过的车，也许是风碰到窗帘的轻响，或者楼下有人忽然笑了一声。注意到这些以后，原本安静的房间好像也有了自己的节奏。",
        "如果说着说着觉得累了，我们就把语速放慢一点。没有必要为了填满沉默而不停寻找新句子，也不用担心停顿会让气氛变得尴尬。真正舒服的陪伴，本来就允许两个人安静地待上一会儿。",
        "等这一段话结束时，我希望留下来的不是某个标准答案，而是一种可以继续聊下去的感觉。下一次再从这里开始，不管接上旧话题还是突然转向新的念头，都算是这场对话自然的一部分。",
    ).joinToString("")

    private val LONG_EN = listOf(
        "When the room became quiet, I remembered a small moment from earlier today. Nothing dramatic happened, but the afternoon light reached the window at just the right angle, and the warm cup on the table made the whole room feel softer. We often forget details like that, even though they can quietly change the mood of an ordinary day. If you want, tell me one small thing you noticed. It does not need to be important, funny, or complete. Start wherever you like, pause whenever you need, and let the conversation wander. I will listen carefully, although I cannot promise that I will never interrupt with a playful comment. ",
        "A comfortable conversation does not have to rush toward a conclusion. It can begin with what you ate, a strange sign you saw on the way home, or a song that suddenly returned to your memory. One detail may lead to another, and the subject may change before either of us notices. Silence is allowed too. If you lose the thread, we can wait for it to come back instead of forcing the next sentence. Later we may forget the exact words, but we may still remember that someone stayed, listened, and answered. That feeling is enough reason to keep talking at our own pace."
    ).joinToString("")

    private val LONG_JA = listOf(
        "部屋が静かになったとき、今日あった小さな出来事を思い出しました。特別な事件ではないけれど、午後の光が窓から差し込んで、まだ温かいお茶が机の上にあって、その普通の瞬間を覚えておきたいと思ったんです。毎日の中には、気づいてもすぐ忘れてしまうことがたくさんあります。でも、遠くから聞こえた音楽や、ちょうどいいタイミングで届いた言葉のように、小さなものが一日を優しくしてくれることもあります。よかったら、あなたが今日見つけたことも教えてください。立派な話でなくても大丈夫です。",
        "会話はいつも急いで結論を出さなくてもいいと思います。今日食べたものや、帰り道で見かけた変な看板や、ふと思い出した昔の歌から始めてもいいんです。一つの話から別の話へ移って、気づいたら最初とは全然違う場所にたどり着いていることもあります。途中で言葉が見つからなくなったら、少し黙って考えましょう。静かな時間も会話の一部ですし、無理に次の言葉を探す必要はありません。あなたのペースで話してくれたら、私はその続きをちゃんと待っています。",
        "しばらく時間がたてば、今日交わした言葉を全部覚えているわけではないかもしれません。それでも、誰かがそばにいて、最後まで話を聞いて、時々笑わせてくれたという感覚は残る気がします。だから今は、上手に話そうとしなくても大丈夫です。楽しかったことも、少し腹が立ったことも、どうでもいいような小さなことも、そのまま聞かせてください。話が終わるころに少しだけ気持ちが軽くなっていたら、それだけで十分です。次にまた話したくなったときは、今日止まった場所からでも、新しい話題からでも、好きなように始めましょう。",
        "窓の外から聞こえる音に気を取られたら、そのことを話題にしてもいいですね。風がカーテンを揺らす音や、遠くを通る車の音にも、それぞれ小さな物語があるように感じます。そんな寄り道を重ねながら話していると、何でもない夜が少しだけ特別になります。急ぐ予定はありませんから、思い出したことを一つずつ置いていってください。私はその言葉を受け取りながら、次にどんな景色が見えてくるのか楽しみにしています。最後まできれいにまとめなくても、続きを話したいと思えるところで終われたなら、それが一番自然な会話だと思います。",
    ).joinToString("")

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
