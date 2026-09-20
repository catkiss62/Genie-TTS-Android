package com.catkiss62.geniettsbenchmark

import java.io.File

/**
 * v0.7.6 and earlier extracted the retired Tiandou bundle directly below
 * files/genie-benchmark. Jiuhu lives below voices/jiuhu, so an overwrite install can reclaim the
 * old model without touching the shared RoBERTa file or any Jiuhu data.
 */
object RetiredVoiceAssetCleaner {
    fun removeTiandou(
        filesDir: File,
        sharedFrontendFileName: String = "chinese_roberta_int8.onnx",
    ): List<String> {
        val base = File(filesDir, "genie-benchmark")
        if (!base.isDirectory) return emptyList()
        val retiredDirectories = base.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.contains("tiandou", ignoreCase = true) }
        preserveLegacyFrontend(base, retiredDirectories, sharedFrontendFileName)
        val removed = ArrayList<String>()
        retiredDirectories.forEach { retired ->
            if (retired.deleteRecursively()) removed += retired.name
        }
        return removed
    }

    private fun preserveLegacyFrontend(
        base: File,
        retiredDirectories: List<File>,
        fileName: String,
    ) {
        val shared = File(base, "shared/$fileName")
        val targetMarker = File(shared.parentFile, shared.name + ".sha256")
        if (shared.isFile && targetMarker.isFile) return
        val source = retiredDirectories.asSequence()
            .map { File(it, "frontend/$fileName") }
            .firstOrNull { it.isFile && File(it.parentFile, it.name + ".sha256").isFile }
            ?: return
        val sourceMarker = File(source.parentFile, source.name + ".sha256")
        val incoming = File(shared.parentFile, shared.name + ".incoming")
        val incomingMarker = File(shared.parentFile, shared.name + ".sha256.incoming")
        shared.parentFile?.mkdirs()
        shared.delete()
        targetMarker.delete()
        source.copyTo(incoming, overwrite = true)
        sourceMarker.copyTo(incomingMarker, overwrite = true)
        check(incoming.renameTo(shared)) { "无法迁移旧版共享 RoBERTa" }
        check(incomingMarker.renameTo(targetMarker)) { "无法迁移旧版 RoBERTa 校验标记" }
    }
}
