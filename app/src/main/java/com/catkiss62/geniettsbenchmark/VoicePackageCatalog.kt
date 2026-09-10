package com.catkiss62.geniettsbenchmark

data class VoicePackageSpec(
    val id: String,
    val title: String,
    val assetNamespace: String,
    val supportsMultilingual: Boolean,
    val supportsDialogueStreaming: Boolean,
    val supportsPlaybackTuning: Boolean = false,
)

object VoicePackageCatalog {
    val all = listOf(
        VoicePackageSpec(
            id = "tiandou",
            title = "恬豆 V2（主测试包）",
            assetNamespace = "benchmark",
            supportsMultilingual = true,
            supportsDialogueStreaming = true,
        ),
        VoicePackageSpec(
            id = "jiuhu",
            title = "小酒狐 V2Pro（4 候选）",
            assetNamespace = "benchmark_jiuhu",
            supportsMultilingual = true,
            supportsDialogueStreaming = false,
            supportsPlaybackTuning = true,
        ),
    )
}
