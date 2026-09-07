package com.catkiss62.geniettsbenchmark

enum class RuntimeLanguageMode(val title: String) {
    HYBRID("中文＋英文"),
    ENGLISH("纯英文"),
    JAPANESE("纯日语"),
}

data class RuntimeLanguagePreset(
    val id: String,
    val mode: RuntimeLanguageMode,
    val title: String,
    val text: String,
    val translation: String,
    val expectedPhones: LongArray? = null,
) {
    val displayLabel: String get() = "${mode.title} · $title"
}

object RuntimeLanguagePresets {
    val all = listOf(
        RuntimeLanguagePreset(
            "hybrid_deepseek", RuntimeLanguageMode.HYBRID, "日常热词",
            "我是 DeepSeek 啊，今天也会认真陪你聊天。", "测试 DeepSeek 在中文句子中的自然衔接",
        ),
        RuntimeLanguagePreset(
            "hybrid_token", RuntimeLanguageMode.HYBRID, "轻音 token",
            "刚才用了三十二个 token，生成速度还不错。", "测试 token 的标准轻音与中文衔接",
        ),
        RuntimeLanguagePreset(
            "hybrid_acronym", RuntimeLanguageMode.HYBRID, "英文缩写",
            "API 已经连接好了，GPT 会继续回答。", "测试 API、GPT 按英文字母逐个发音",
        ),
        RuntimeLanguagePreset(
            "hybrid_onnx", RuntimeLanguageMode.HYBRID, "技术词组合",
            "这个 ONNX 模型只在 CPU 上运行。", "测试 ONNX、CPU 热词和句中切换",
        ),
        RuntimeLanguagePreset(
            "english_daily", RuntimeLanguageMode.ENGLISH, "日常问候",
            "Good morning. Did you sleep well last night?", "早上好。昨晚睡得好吗？",
            longArrayOf(3, 50, 84, 26, 63, 16, 74, 64, 54, 65, 3, 26, 55, 26, 92, 88, 75, 62, 58, 73, 91, 35, 62, 62, 10, 75, 80, 64, 22, 80, 4),
        ),
        RuntimeLanguagePreset(
            "english_question", RuntimeLanguageMode.ENGLISH, "自然疑问",
            "What would you like to do together today?", "今天想一起做什么？",
            longArrayOf(3, 91, 13, 80, 91, 84, 26, 92, 88, 62, 22, 61, 80, 88, 26, 88, 80, 12, 50, 35, 27, 38, 80, 12, 26, 42, 4),
        ),
        RuntimeLanguagePreset(
            "english_comfort", RuntimeLanguageMode.ENGLISH, "安慰陪伴",
            "It's okay. You don't have to rush. I'll stay here with you.", "没关系，不用着急，我会陪在这里。",
            longArrayOf(3, 55, 80, 75, 69, 61, 42, 3, 92, 88, 26, 68, 64, 80, 51, 10, 90, 80, 88, 74, 13, 76, 3, 22, 62, 75, 80, 42, 51, 58, 74, 91, 55, 27, 92, 88, 3),
        ),
        RuntimeLanguagePreset(
            "english_technical", RuntimeLanguageMode.ENGLISH, "技术长句",
            "DeepSeek generated this response with thirty-two tokens.", "DeepSeek 用三十二个 token 生成了这条回复。",
            longArrayOf(3, 26, 58, 73, 75, 58, 61, 60, 35, 64, 38, 43, 80, 12, 26, 27, 55, 75, 74, 54, 75, 73, 7, 64, 75, 91, 55, 27, 81, 39, 26, 59, 2, 80, 88, 80, 68, 61, 12, 64, 93, 3),
        ),
        RuntimeLanguagePreset(
            "japanese_daily", RuntimeLanguageMode.JAPANESE, "日常问候",
            "おはよう。昨日はよく眠れた？", "早上好。昨晚睡得好吗？",
            longArrayOf(3, 229, 322, 158, 96, 318, 229, 229, 3, 222, 160, 322, 227, 229, 323, 229, 316, 96, 318, 229, 323, 222, 254, 227, 129, 322, 225, 254, 248, 129, 252, 96, 4),
        ),
        RuntimeLanguagePreset(
            "japanese_question", RuntimeLanguageMode.JAPANESE, "自然疑问",
            "今日は一緒に何をして過ごしたい？", "今天想一起做什么？",
            longArrayOf(3, 223, 229, 323, 229, 316, 96, 160, 322, 126, 251, 229, 227, 160, 227, 96, 323, 227, 160, 229, 251, 160, 252, 129, 250, 254, 322, 156, 229, 323, 251, 160, 252, 96, 160, 4),
        ),
        RuntimeLanguagePreset(
            "japanese_comfort", RuntimeLanguageMode.JAPANESE, "安慰陪伴",
            "大丈夫。急がなくてもいいよ。ここでゆっくり待っているから。", "没关系，不用着急，我会在这里慢慢等你。",
            longArrayOf(3, 127, 96, 322, 160, 221, 229, 323, 229, 122, 254, 3, 160, 322, 250, 229, 156, 96, 323, 227, 96, 222, 254, 252, 129, 225, 229, 160, 323, 160, 318, 229, 3, 222, 229, 322, 222, 229, 127, 129, 318, 254, 322, 126, 222, 254, 323, 248, 160, 225, 96, 323, 126, 252, 129, 160, 248, 254, 222, 96, 248, 96, 3),
        ),
        RuntimeLanguagePreset(
            "japanese_lively", RuntimeLanguageMode.JAPANESE, "活泼分享",
            "ねえ、聞いて！すごく面白いことを思いついたよ。", "听我说！我想到了一件非常有趣的事。",
            longArrayOf(3, 227, 129, 323, 129, 1, 222, 160, 322, 160, 252, 129, 0, 250, 254, 322, 156, 229, 323, 222, 254, 229, 322, 225, 229, 251, 160, 248, 229, 323, 160, 222, 229, 252, 229, 229, 229, 322, 225, 229, 160, 253, 254, 323, 160, 252, 96, 318, 229, 3),
        ),
    )
}
