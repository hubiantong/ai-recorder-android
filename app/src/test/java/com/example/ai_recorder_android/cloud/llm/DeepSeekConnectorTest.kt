package com.example.ai_recorder_android.cloud.llm

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector.Companion.SYSTEM_PROMPT
import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector.Companion.TITLE_SYSTEM_PROMPT
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatRequest
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeekConnector 纯 JVM 单测（本机不跑 gradlew，其他机器验证）。
 * 覆盖：中文标题清洗 ≤12 字（Metis V4.5）、无 key 规则模板回退（不崩）、
 * 融合笔记 prompt 结构、DTO 序列化（snake_case / null 省略）、响应解析。
 */
class DeepSeekConnectorTest {

    /**
     * 无 key 场景：注入总是返回 500 的 client，让主用 GLM（本机可能配置了真实 key）
     * 与备用 DeepSeek 都确定性失败，不触网，稳定验证回退逻辑。
     */
    private fun noKeyConnector(): DeepSeekConnector = DeepSeekConnector(apiKey = "", client = failingClient())

    private fun failingClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("LLM unavailable")
                    .body("LLM unavailable".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

    // ---- 标题清洗（对齐 PC note_fuser.generate_title 清理正则）----

    @Test
    fun cleanTitle_truncatesLongChineseTitleTo12Chars() {
        val raw = "关于人工智能在医疗领域的应用前景探讨" // 18 字
        val cleaned = DeepSeekConnector.cleanTitle(raw)
        assertTrue("清洗后应 ≤12 字，实际 ${cleaned.length}", cleaned.length <= 12)
        assertEquals("关于人工智能在医疗领域的", cleaned)
    }

    @Test
    fun cleanTitle_stripsTrailingSentencePunctuation() {
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要。"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("人工智能会议纪要！"))
    }

    @Test
    fun cleanTitle_stripsQuotesAndInternalWhitespace() {
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("「人工智能 会议 纪要」"))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("\"人工智能会议纪要\""))
        assertEquals("人工智能会议纪要", DeepSeekConnector.cleanTitle("《人工智能会议纪要》"))
    }

    @Test
    fun cleanTitle_shortTitleUnchanged() {
        assertEquals("会议", DeepSeekConnector.cleanTitle("会议"))
    }

    // ---- 无 key 规则模板回退 ----

    @Test
    fun generateTitle_withoutKey_returnsEmptyWithoutCrash() = runBlocking {
        val connector = noKeyConnector()
        assertFalse(connector.hasApiKey)
        assertEquals("", connector.generateTitle("这是一段足够长的转写文本，用于验证无 key 时标题生成不崩溃。"))
    }

    @Test
    fun generateFusionNote_withoutKey_returnsTemplateNote() = runBlocking {
        val note = noKeyConnector().generateFusionNote(
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
        // 三节结构 + 标题 + 时长
        assertTrue(note.contains("# 产品评审会"))
        assertTrue(note.contains("> 录音时长：120.0 秒"))
        assertTrue(note.contains("## 主题摘要"))
        assertTrue(note.contains("## 关键要点"))
        assertTrue(note.contains("## 时间轴要点"))
        // 时间轴按时间合并排序：10s 文本 → 15s 照片 → 30s 文本
        // 关键要点节同样带 [Ns] 前缀，须从时间轴节内起查，避免 indexOf 命中前一节
        val timelineStart = note.indexOf("## 时间轴要点")
        val textIdx = note.indexOf("[10s] 讨论需求优先级", timelineStart)
        val photoIdx = note.indexOf("[15s] [照片] 白板上的架构图", timelineStart)
        val text2Idx = note.indexOf("[30s] 确定排期", timelineStart)
        assertTrue(textIdx in 0 until photoIdx)
        assertTrue(photoIdx in 0 until text2Idx)
    }

    @Test
    fun buildTemplateNote_summaryUsesFirst200CharsWithEllipsis() {
        val longText = "字".repeat(250)
        val note = noKeyConnector().buildTemplateNote(
            FusionNoteRequest(title = null, transcription = longText)
        )
        assertTrue(note.contains("# 录音笔记")) // 无标题 → 默认标题
        assertTrue(note.contains("## 主题摘要"))
        // 截断只约束"主题摘要"节：关键要点节按行原样列出（可含超 200 字的行），不能全篇断言
        val summary = note.substringAfter("## 主题摘要").substringBefore("## 关键要点")
        assertTrue(summary.contains("字".repeat(200) + "..."))
        assertFalse(summary.contains("字".repeat(201)))
    }

    @Test
    fun buildTemplateNote_shortText_noEllipsis() {
        val note = noKeyConnector().buildTemplateNote(
            FusionNoteRequest(title = "短", transcription = "只有一句话")
        )
        assertTrue(note.contains("只有一句话"))
        assertFalse(note.contains("..."))
    }

    // ---- 融合笔记 prompt 结构 ----

    @Test
    fun systemPrompt_containsThreeSections() {
        assertTrue(SYSTEM_PROMPT.contains("## 主题摘要"))
        assertTrue(SYSTEM_PROMPT.contains("## 关键要点"))
        assertTrue(SYSTEM_PROMPT.contains("## 时间轴要点"))
    }

    @Test
    fun titleSystemPrompt_requiresChineseTitleWithin12Chars() {
        assertTrue(TITLE_SYSTEM_PROMPT.contains("≤12 字"))
    }

    @Test
    fun buildPrompt_includesTitleDurationChunksAndAttachments() {
        val prompt = noKeyConnector().buildPrompt(
            FusionNoteRequest(
                title = "周会",
                durationSeconds = 90.5,
                transcription = "（chunks 优先，此字段不出现）",
                chunks = listOf(FusionChunk(startTime = 1.5, endTime = 3.0, speaker = "李四", content = "汇报进度")),
                attachments = listOf(FusionAttachment(timestamp = 2.0, description = "截图")),
            )
        )
        assertTrue(prompt.contains("# 录音标题：周会"))
        assertTrue(prompt.contains("# 录音时长：90.5 秒"))
        assertTrue(prompt.contains("[1.5-3.0s] [李四] 汇报进度"))
        assertTrue(prompt.contains("- [2.0s] 截图"))
        assertFalse(prompt.contains("（chunks 优先"))
    }

    // ---- DTO 序列化 ----

    @Test
    fun chatRequest_serializesSnakeCaseAndOmitsNull() {
        val json = Json { explicitNulls = false }
        val withParams = json.encodeToString(
            DeepSeekChatRequest(
                model = "deepseek-v4-flash",
                messages = listOf(DeepSeekMessage(role = "system", content = "s")),
                maxTokens = 64,
                temperature = 0.3,
            )
        )
        assertTrue(withParams.contains("\"max_tokens\":64"))
        assertTrue(withParams.contains("\"temperature\":0.3"))
        assertTrue(withParams.contains("\"role\":\"system\""))

        val withoutParams = json.encodeToString(
            DeepSeekChatRequest(
                model = "deepseek-v4-flash",
                messages = listOf(DeepSeekMessage(role = "user", content = "u")),
                maxTokens = null,
                temperature = null,
            )
        )
        assertFalse(withoutParams.contains("max_tokens"))
        assertFalse(withoutParams.contains("temperature"))
    }

    // ---- 响应解析 ----

    @Test
    fun parseChatResponse_extractsFirstChoiceContent() {
        val json = """
            {
              "choices": [
                {"message": {"role": "assistant", "content": "人工智能会议纪要"}},
                {"message": {"role": "assistant", "content": "第二个候选"}}
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
        """.trimIndent()
        assertEquals("人工智能会议纪要", noKeyConnector().parseChatResponse(json))
    }

    @Test
    fun parseChatResponse_error_throws() {
        val json = """{"error": {"message": "Invalid API key", "code": "401"}}""".trimIndent()
        try {
            noKeyConnector().parseChatResponse(json)
            org.junit.Assert.fail("应抛出 DeepSeekException")
        } catch (e: DeepSeekException) {
            assertTrue(e.message!!.contains("Invalid API key"))
        }
    }
}