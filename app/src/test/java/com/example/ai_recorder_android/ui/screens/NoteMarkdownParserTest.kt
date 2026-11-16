package com.example.ai_recorder_android.ui.screens

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
import com.example.ai_recorder_android.cloud.llm.FusionAttachment
import com.example.ai_recorder_android.cloud.llm.FusionChunk
import com.example.ai_recorder_android.cloud.llm.FusionNoteRequest
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 笔记 Markdown 解析单测（纯 JVM）：
 * - 真实模板笔记（DeepSeekConnector.buildTemplateNote 输出）端到端解析；
 * - LLM 风格样例：三节 + 照片引用（块级/行内剥离）、加粗、行内代码、待办、有序列表、分隔线；
 * - 未识别语法降级 Paragraph 的容错；时间戳提取与附件就近匹配边界。
 */
class NoteMarkdownParserTest {

    // ---- 真实模板笔记（buildTemplateNote 输出 → 解析）----

    @Test
    fun templateNote_parsesFullStructure() {
        val note = noKeyConnector().buildTemplateNote(
            FusionNoteRequest(
                title = "产品评审会",
                durationSeconds = 120.0,
                transcription = "第一段内容\n第二段内容",
                chunks = listOf(
                    FusionChunk(startTime = 10.0, endTime = 20.0, speaker = "张三", content = "讨论需求优先级"),
                    FusionChunk(startTime = 30.0, endTime = 40.0, speaker = null, content = "确定排期"),
                ),
                attachments = listOf(
                    FusionAttachment(timestamp = 15.0, description = "白板上的架构图"),
                ),
            )
        )
        val blocks = parseNoteMarkdown(note)

        assertEquals(NoteBlock.Heading(1, listOf(NoteInline.Plain("产品评审会"))), blocks[0])
        assertEquals(NoteBlock.Quote(listOf(NoteInline.Plain("录音时长：120.0 秒"))), blocks[1])
        assertEquals(NoteBlock.Heading(2, listOf(NoteInline.Plain("主题摘要"))), blocks[2])

        // 关键要点：时间戳列表（模板 `%.0f` 格式）
        val bullets = blocks.filterIsInstance<NoteBlock.Bullet>()
        assertEquals(
            listOf(10.0, 30.0, 10.0, 15.0, 30.0),
            bullets.map { it.timestampSec },
        )
        // 时间轴照片行：`- [15s] [照片] 白板上的架构图`（[照片] 为文本标记，不产出 PhotoRef）
        val photoBullet = bullets[3]
        assertEquals("[照片] 白板上的架构图", (photoBullet.text.single() as NoteInline.Plain).text)

        // 三节标题齐备且 level=2
        val headings = blocks.filterIsInstance<NoteBlock.Heading>().filter { it.level == 2 }
        assertEquals(listOf("主题摘要", "关键要点", "时间轴要点"), headings.map { it.text.plainText() })
    }

    @Test
    fun templateNote_withoutTitle_usesDefaultTitle() {
        val note = noKeyConnector().buildTemplateNote(FusionNoteRequest(transcription = "只有一句话"))
        val blocks = parseNoteMarkdown(note)
        assertEquals(NoteBlock.Heading(1, listOf(NoteInline.Plain("录音笔记"))), blocks[0])
    }

    // ---- LLM 风格样例 ----

    @Test
    fun llmNote_threeSectionsWithInlineFormatting() {
        val markdown = """
            ## 主题摘要
            本次评审**确定了三个**核心结论，见 `排期表`。
            ## 关键要点
            - **优先级**：P0 需求先行
            - [45.5s] 排期确定
            ## 时间轴要点
            - [10s] 开场
        """.trimIndent()
        val blocks = parseNoteMarkdown(markdown)

        assertEquals(NoteBlock.Heading(2, listOf(NoteInline.Plain("主题摘要"))), blocks[0])
        // 加粗 + 行内代码
        val paragraph = blocks[1] as NoteBlock.Paragraph
        assertEquals(
            listOf(
                NoteInline.Plain("本次评审"),
                NoteInline.Bold(listOf(NoteInline.Plain("确定了三个"))),
                NoteInline.Plain("核心结论，见 "),
                NoteInline.Code("排期表"),
                NoteInline.Plain("。"),
            ),
            paragraph.text,
        )
        // bullet 行首时间戳提取
        val bullets = blocks.filterIsInstance<NoteBlock.Bullet>()
        assertEquals(null, bullets[0].timestampSec)
        assertEquals(45.5, bullets[1].timestampSec!!, 0.001)
    }

    @Test
    fun llmNote_blockLevelPhotoRef_becomesPhotoRefBlock() {
        val blocks = parseNoteMarkdown("## 时间轴要点\n![白板架构图](attachment:15)\n- [10s] 开场")
        assertEquals(NoteBlock.Heading(2, listOf(NoteInline.Plain("时间轴要点"))), blocks[0])
        assertEquals(NoteBlock.PhotoRef("白板架构图", 15.0), blocks[1])
        assertEquals(NoteBlock.Bullet(listOf(NoteInline.Plain("开场")), 10.0), blocks[2])
    }

    @Test
    fun llmNote_inlinePhotoRefInBullet_strippedToPhotoRefBlock() {
        val blocks = parseNoteMarkdown("- 讲解架构 ![白板](attachment:15.0s) 细节")
        assertEquals(NoteBlock.Bullet(listOf(NoteInline.Plain("讲解架构  细节")), null), blocks[0])
        assertEquals(NoteBlock.PhotoRef("白板", 15.0), blocks[1])
    }

    @Test
    fun llmNote_purePhotoBullet_dropsEmptyBullet() {
        val blocks = parseNoteMarkdown("- [15s] ![白板](attachment:15s)")
        assertEquals(listOf<NoteBlock>(NoteBlock.PhotoRef("白板", 15.0)), blocks)
    }

    @Test
    fun llmNote_photoRefWithoutDescription_fallsBackToGenericLabel() {
        val blocks = parseNoteMarkdown("![](attachment:8s)")
        assertEquals(NoteBlock.PhotoRef("照片", 8.0), blocks[0])
    }

    @Test
    fun llmNote_checkboxesOrderedAndDivider() {
        val markdown = """
            ### 补充
            1. 待办一
            2) 待办二
            - [ ] 跟进测试
            - [x] 已发周报
            ---
        """.trimIndent()
        val blocks = parseNoteMarkdown(markdown)

        assertEquals(NoteBlock.Heading(3, listOf(NoteInline.Plain("补充"))), blocks[0])
        assertEquals(NoteBlock.OrderedItem(1, listOf(NoteInline.Plain("待办一"))), blocks[1])
        assertEquals(NoteBlock.OrderedItem(2, listOf(NoteInline.Plain("待办二"))), blocks[2])
        assertEquals(NoteBlock.CheckboxItem(listOf(NoteInline.Plain("跟进测试")), checked = false), blocks[3])
        assertEquals(NoteBlock.CheckboxItem(listOf(NoteInline.Plain("已发周报")), checked = true), blocks[4])
        assertEquals(NoteBlock.Divider, blocks[5])
    }

    // ---- 容错降级 ----

    @Test
    fun fallback_unrecognizedSyntax_staysAsParagraphWithoutException() {
        val markdown = """
            > > 嵌套引用按文本处理
            |表|格|
            **未闭合加粗
            未闭合`代码
            - 
        """.trimIndent()
        val blocks = parseNoteMarkdown(markdown)
        // 无崩溃；未闭合标记保留原文
        assertTrue(blocks.all { it is NoteBlock.Paragraph || it is NoteBlock.Quote })
        val boldLine = blocks.map { it.textString() }.first { it.contains("未闭合加粗") }
        assertTrue(boldLine.contains("**未闭合加粗"))
        val codeLine = blocks.map { it.textString() }.first { it.contains("未闭合`代码") }
        assertTrue(codeLine.contains("`代码"))
        // 孤立 `-` 行（trim 后无列表语法）降级为普通段落，不产生空块
        assertTrue(blocks.map { it.textString() }.none { it.isBlank() })
    }

    @Test
    fun fallback_headingWithoutSpace_stillParses() {
        val blocks = parseNoteMarkdown("##主题摘要")
        assertEquals(NoteBlock.Heading(2, listOf(NoteInline.Plain("主题摘要"))), blocks[0])
    }

    @Test
    fun fallback_paragraphWithTimestampPrefix_extractsTimestamp() {
        val blocks = parseNoteMarkdown("[30s] 排期确定")
        val paragraph = blocks.single() as NoteBlock.Paragraph
        assertEquals(30.0, paragraph.timestampSec!!, 0.001)
        assertEquals("排期确定", paragraph.text.plainText())
    }

    @Test
    fun fallback_transcriptionRangeTimestamp_notMistakenAsPrefix() {
        // 转写行格式 [10.0-20.0s] 不应被当作时间戳前缀剥离
        val blocks = parseNoteMarkdown("[10.0-20.0s] 讨论需求")
        val paragraph = blocks.single() as NoteBlock.Paragraph
        assertNull(paragraph.timestampSec)
        assertEquals("[10.0-20.0s] 讨论需求", paragraph.text.plainText())
    }

    // ---- 附件匹配 ----

    @Test
    fun matchAttachment_nearestWithinTolerance() {
        val attachments = listOf(att(timestamp = 15.0), att(timestamp = 40.0))
        assertEquals(15.0, matchAttachment(attachments, 18.0)?.timestamp!!, 0.001)
        assertEquals(40.0, matchAttachment(attachments, 37.0)?.timestamp!!, 0.001)
    }

    @Test
    fun matchAttachment_outOfTolerance_returnsNull() {
        val attachments = listOf(att(timestamp = 15.0))
        assertNull(matchAttachment(attachments, 25.0))
    }

    @Test
    fun matchAttachment_tieBreaksToFirst() {
        val attachments = listOf(att(timestamp = 15.0, id = 1), att(timestamp = 15.0, id = 2))
        assertEquals(1L, matchAttachment(attachments, 15.0)?.id)
    }

    // ---- 时间戳格式化 ----

    @Test
    fun formatNoteTimestamp_matchesPlayerFormat() {
        assertEquals("0:10", formatNoteTimestamp(10.0))
        assertEquals("1:05", formatNoteTimestamp(65.9))
        assertEquals("0:00", formatNoteTimestamp(-3.0))
    }

    // ---- 工具 ----

    private fun noKeyConnector(): DeepSeekConnector = DeepSeekConnector(apiKey = "", client = OkHttpClient())

    private fun att(timestamp: Double, id: Long = 0): RecordingAttachmentEntity = RecordingAttachmentEntity(
        id = id,
        recordingId = 1,
        storageLocator = "att/$id.jpg",
        timestamp = timestamp,
        createdAt = 0,
    )

    private fun List<NoteInline>.plainText(): String = joinToString("") { (it as? NoteInline.Plain)?.text.orEmpty() }

    private fun NoteBlock.textString(): String = when (this) {
        is NoteBlock.Heading -> text.plainText()
        is NoteBlock.Bullet -> text.plainText()
        is NoteBlock.CheckboxItem -> text.plainText()
        is NoteBlock.OrderedItem -> text.plainText()
        is NoteBlock.Quote -> text.plainText()
        is NoteBlock.Paragraph -> text.plainText()
        is NoteBlock.PhotoRef -> description
        NoteBlock.Divider -> "---"
    }
}
