package com.catkiss62.geniettsbenchmark

data class VoicePackageSpec(
    val id: String,
    val title: String,
    val assetNamespace: String,
    val supportsMultilingual: Boolean,
    val supportsDialogueStreaming: Boolean,
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
            id = "lenai",
            title = "乐奈 V2.1（日语参考包）",
            assetNamespace = "benchmark_lenai",
            supportsMultilingual = true,
            supportsDialogueStreaming = false,
        ),
    )
}
