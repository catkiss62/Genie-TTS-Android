package com.catkiss62.geniettsbenchmark

/**
 * Stable names for the four Jiuhu reference-audio profiles in the private benchmark bundle.
 *
 * Keep [id] aligned with manifest.json. The AI companion should migrate only entries whose
 * [includeInCompanion] is true; the test-only backup remains here for later comparison without
 * adding another production voice branch.
 */
data class VoiceProfileDefinition(
    val id: String,
    val key: String,
    val displayName: String,
    val intendedUse: String,
    val includeInCompanion: Boolean,
)

object VoiceProfileCatalog {
    val profiles = listOf(
        VoiceProfileDefinition("jiuhu_idle50", "lively", "原始参考（活泼）", "活泼、兴奋与高能量表达", true),
        VoiceProfileDefinition("jiuhu_bento_tools", "daily", "便当与备用工具（日常）", "日常对话与无法判断时的默认", true),
        VoiceProfileDefinition("jiuhu_dream_days", "gentle", "如梦的日子（温柔）", "安慰、平静、担心与低强度亲密表达", true),
        VoiceProfileDefinition("jiuhu_devotion", "cute", "献给主人（可爱）", "调皮、无奈与偏可爱的表达", true),
    )

    private val byId = profiles.associateBy { it.id }

    fun resolve(id: String): VoiceProfileDefinition? = byId[id]

    fun displayName(id: String, fallback: String): String = resolve(id)?.displayName ?: fallback
}
