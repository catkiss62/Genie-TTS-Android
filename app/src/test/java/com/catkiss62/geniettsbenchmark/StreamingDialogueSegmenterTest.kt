package com.catkiss62.geniettsbenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDialogueSegmenterTest {
    @Test
    fun punctuationAtEndOfDeltaClosesImmediately() {
        val segmenter = StreamingDialogueSegmenter(DialogueLanguage.CHINESE)

        val closed = segmenter.addDelta("第一句话现在结束。")

        assertEquals(listOf("第一句话现在结束。"), closed.map { it.text })
        assertTrue(segmenter.pendingText().isEmpty())
    }

    @Test
    fun unpunctuatedTextNeverExceedsHardLimit() {
        val segmenter = StreamingDialogueSegmenter(DialogueLanguage.CHINESE)
        val text = "这是一段完全没有任何标点而且会持续很久的测试文字".repeat(8)
        val closed = buildList {
            text.chunked(7).forEach { addAll(segmenter.addDelta(it)) }
            addAll(segmenter.finish())
        }

        assertTrue(closed.isNotEmpty())
        assertTrue(closed.all { it.text.length <= DialogueLanguage.CHINESE.maxChars })
    }

    @Test
    fun finalResidualTextIsFlushed() {
        val segmenter = StreamingDialogueSegmenter(DialogueLanguage.ENGLISH)
        assertTrue(segmenter.addDelta("A short final phrase without punctuation").isEmpty())

        assertEquals(
            listOf("A short final phrase without punctuation"),
            segmenter.finish().map { it.text },
        )
    }

    @Test
    fun queuedShortSentencesArePackedAfterTheFirstSegment() {
        val packed = DialogueSegmentPacker.packPrefix(
            listOf("我忍不住笑出声。", "她也跟着笑了。", "后来我们继续往前走。"),
            DialogueLanguage.CHINESE,
        )

        assertEquals("我忍不住笑出声。她也跟着笑了。后来我们继续往前走。", packed.text)
        assertEquals(3, packed.sourceUnits)
        assertTrue(packed.text.length <= DialogueLanguage.CHINESE.maxChars)
    }

    @Test
    fun packerDoesNotCrossTheHardMaximum() {
        val first = "这是一段已经比较接近目标长度的自然语句。"
        val second = "这一个后续句子很长，所以必须留到下一轮单独处理。".repeat(2)
        val packed = DialogueSegmentPacker.packPrefix(
            listOf(first, second),
            DialogueLanguage.CHINESE,
        )

        assertEquals(first, packed.text)
        assertEquals(1, packed.sourceUnits)
    }

    @Test
    fun englishPackingRestoresSpaceBetweenClosedUnits() {
        val packed = DialogueSegmentPacker.packPrefix(
            listOf("A short sentence.", "Another short sentence."),
            DialogueLanguage.ENGLISH,
        )

        assertEquals("A short sentence. Another short sentence.", packed.text)
        assertEquals(2, packed.sourceUnits)
    }
}
