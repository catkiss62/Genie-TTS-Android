package com.catkiss62.geniettsbenchmark

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JiuhuOnlyPackageTest {
    @Test
    fun catalogContainsOnlyJiuhu() {
        assertEquals(listOf("jiuhu"), VoicePackageCatalog.all.map { it.id })
        val jiuhu = VoicePackageCatalog.all.single()
        assertEquals("benchmark_jiuhu", jiuhu.assetNamespace)
        assertTrue(jiuhu.supportsMultilingual)
        assertTrue(jiuhu.supportsDialogueStreaming)
        assertTrue(jiuhu.supportsPlaybackTuning)
    }

    @Test
    fun retiredTiandouCacheIsRemovedWithoutTouchingJiuhuOrSharedFrontend() {
        val filesDir = Files.createTempDirectory("genie-v077-test").toFile()
        try {
            val base = File(filesDir, "genie-benchmark")
            val tiandou = File(base, "genie-tts-v2.0.2-tiandou-v2-final-v0.3.3")
            val jiuhu = File(base, "voices/jiuhu/current")
            val shared = File(base, "shared")
            tiandou.mkdirs()
            jiuhu.mkdirs()
            File(tiandou, "model.bin").writeText("retired")
            val legacyFrontend = File(tiandou, "frontend/chinese_roberta_int8.onnx")
            legacyFrontend.parentFile.mkdirs()
            legacyFrontend.writeText("shared-model")
            File(legacyFrontend.parentFile, legacyFrontend.name + ".sha256").writeText("digest")
            File(jiuhu, "model.bin").writeText("keep")

            val removed = RetiredVoiceAssetCleaner.removeTiandou(filesDir)

            assertEquals(listOf(tiandou.name), removed)
            assertFalse(tiandou.exists())
            assertTrue(jiuhu.isDirectory)
            assertTrue(shared.isDirectory)
            assertEquals(
                "shared-model",
                File(shared, "chinese_roberta_int8.onnx").readText(),
            )
            assertEquals(
                "digest",
                File(shared, "chinese_roberta_int8.onnx.sha256").readText(),
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
