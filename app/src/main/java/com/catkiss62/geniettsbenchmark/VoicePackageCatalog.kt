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
            id = "naiyou",
            title = "奶油 V2（新增试听包）",
            assetNamespace = "benchmark_naiyou",
            supportsMultilingual = false,
            supportsDialogueStreaming = false,
        ),
    )
}
