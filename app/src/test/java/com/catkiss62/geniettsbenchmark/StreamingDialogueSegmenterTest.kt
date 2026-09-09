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
}
