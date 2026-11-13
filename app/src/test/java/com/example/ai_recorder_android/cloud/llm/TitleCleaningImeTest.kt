package com.example.ai_recorder_android.cloud.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题清洗中文 IME 用例单测（纯 JVM，本机不跑 gradlew，其他机器验证）。
 *
 * 覆盖任务书 V4.5 要求：中文标点（全角 `。！？，；：、`）、emoji、混合中英文。
 * 中文 IME 输入产生全角标点，清洗正则必须同时剥离全角与半角句末标点
 * （DeepSeekConnector.cleanTitle 已补齐全角 `！？，；`，见 learnings）。
 */
class TitleCleaningImeTest {

    // ---------- 中文标点（全角） ----------

    @Test
    fun cleanTitle_stripsFullWidthChinesePunctuation() {
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要。"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要！"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要？"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要，"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要；"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要："))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要、"))
    }

    @Test
    fun cleanTitle_stripsHalfWidthPunctuation() {
        assertEquals("会议纪要", DeepSeekConnector.cleanTitle("会议纪要!"))
        assertEquals("会议纪要", DeepSeekConnector.cleanTitle("会议纪要?"))
        assertEquals("会议纪要", DeepSeekConnector.cleanTitle("会议纪要,"))
        assertEquals("会议纪要", DeepSeekConnector.cleanTitle("会议纪要;"))
        assertEquals("会议纪要", DeepSeekConnector.cleanTitle("会议纪要:"))
        assertEquals("会议纪要", DeepSeekConnector.cleanTitle("会议纪要."))
    }

    @Test
    fun cleanTitle_stripsLeadingPunctuation() {
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("。！？人工智能会议纪要"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("...人工智能会议纪要"))
    }

    @Test
    fun cleanTitle_stripsMixedPunctuationAndQuotes() {
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("《人工智能会议纪要》！"))
        // 清洗顺序为先标点后引号（对齐 PC）：引号剥除后句号已不在句尾，故保留
        assertEquals("人工智能会议纪要。", DeepSeekConnector.cleanTitle("「人工智能会议纪要。」"))
    }

    // ---------- emoji ----------

    @Test
    fun cleanTitle_preservesEmoji() {
        // 清洗只剥离标点/引号，emoji 保留（对齐 PC 正则语义）
        assertEquals("🎉会议纪要🎉", DeepSeekConnector.cleanTitle("🎉会议纪要🎉"))
    }

    @Test
    fun cleanTitle_emojiCountsTowardTwelveCharLimit() {
        val cleaned = DeepSeekConnector.cleanTitle("🎉".repeat(13))
        assertTrue("emoji 计入 12 字上限，实际 ${cleaned.length}", cleaned.length <= 12)
        assertEquals(12, cleaned.length)
    }

    // ---------- 混合中英文 ----------

    @Test
    fun cleanTitle_mixedChineseEnglish_removesWhitespaceAndTruncates() {
        // 去空白 → "AI会议纪要MeetingNotes"（18 字）→ take(12)
        assertEquals("AI会议纪要Meetin", DeepSeekConnector.cleanTitle("AI 会议纪要 Meeting Notes"))
    }

    @Test
    fun cleanTitle_mixedChineseEnglish_shortTitleUnchanged() {
        assertEquals("AI会议", DeepSeekConnector.cleanTitle("AI 会议"))
    }
}