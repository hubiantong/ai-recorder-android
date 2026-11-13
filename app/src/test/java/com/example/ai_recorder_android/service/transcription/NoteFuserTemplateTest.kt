package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
import com.example.ai_recorder_android.cloud.llm.FusionChunk
import com.example.ai_recorder_android.cloud.llm.FusionNoteRequest
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatResponse
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChoice
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekResponseMessage
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoteFuser（[NoteFusionWorker] + [DeepSeekConnector.buildTemplateNote]）规则模板输出格式单测
 * （纯 JVM，本机不跑 gradlew，其他机器验证）。
 *
 * 覆盖任务书要求：**无 key / LLM 失败** 时的模板输出格式——
 * `# 标题 / > 录音时长 / ## 主题摘要 / ## 关键要点 / ## 时间轴要点` 三节结构，
 * 时间轴按时间合并排序（文本 + 照片）。
 */
class NoteFuserTemplateTest {

    private val store = FakeTranscriptionStore()

    private fun worker(connector: DeepSeekConnector) = NoteFusionWorker(connector, store)

    /**
     * 无 key 场景：注入总是返回 500 的 client，让主用 GLM（本机可能配置了真实 key）
     * 与备用 DeepSeek 都确定性失败，不触网，稳定回退规则模板笔记。
     */
    private fun noKeyConnector(): DeepSeekConnector =
        DeepSeekConnector(apiKey = "", client = stubClient(500, "LLM unavailable"))

    private fun llmConnector(code: Int, body: String): DeepSeekConnector =
        DeepSeekConnector(apiKey = "test-key", client = stubClient(code, body))

    private fun chunks(): List<TranscriptChunkEntity> = listOf(
        TranscriptChunkEntity(
            recordingId = 1L,
            chunkIndex = 0,
            content = "讨论需求优先级",
            startTime = 10.0,
            endTime = 20.0,
            speakerName = "张三",
            createdAt = 0L,
        ),
        TranscriptChunkEntity(
            recordingId = 1L,
            chunkIndex = 1,
            content = "确定排期",
            startTime = 30.0,
            endTime = 40.0,
            speakerName = null,
            createdAt = 0L,
        ),
    )

    // ---------- 无 key → 模板笔记 ----------

    @Test
    fun `无 key 时模板笔记含完整三节结构与时间轴`() = runBlocking {
        val note = worker(noKeyConnector()).generateNote(
            recordingId = 1L,
            title = "产品评审会",
            transcription = "",
            chunks = chunks(),
            durationSeconds = 120.0,
        )

        // 标题 + 时长
        assertTrue(note.contains("# 产品评审会"))
        assertTrue(note.contains("> 录音时长：120.0 秒"))
        // 三节结构
        assertTrue(note.contains("## 主题摘要"))
        assertTrue(note.contains("## 关键要点"))
        assertTrue(note.contains("## 时间轴要点"))
        // 关键要点带时间戳
        assertTrue(note.contains("- [10s] 讨论需求优先级"))
        assertTrue(note.contains("- [30s] 确定排期"))
        // 时间轴按时间排序
        val first = note.indexOf("[10s] 讨论需求优先级")
        val second = note.indexOf("[30s] 确定排期")
        assertTrue(first in 0 until second)
        // 落库
        assertEquals(note, store.saveSummaryCalls.single().second)
    }

    // ---------- LLM 失败 → 模板笔记 ----------

    @Test
    fun `LLM HTTP 500 时回退模板笔记`() = runBlocking {
        val connector = llmConnector(500, "Internal Server Error")
        val note = worker(connector).generateNote(
            recordingId = 1L,
            title = "周会",
            transcription = "",
            chunks = chunks(),
            durationSeconds = 90.0,
        )

        assertTrue("应回退模板笔记，实际: $note", note.contains("# 周会"))
        assertTrue(note.contains("## 主题摘要"))
        assertTrue(note.contains("## 关键要点"))
        assertTrue(note.contains("## 时间轴要点"))
        assertTrue(note.contains("[10s] 讨论需求优先级"))
    }

    @Test
    fun `LLM 返回空白内容时回退模板笔记`() = runBlocking {
        val connector = llmConnector(200, chatJson(""))
        val note = worker(connector).generateNote(
            recordingId = 1L,
            title = "周会",
            transcription = "",
            chunks = chunks(),
            durationSeconds = 90.0,
        )

        assertTrue("空白内容应回退模板笔记，实际: $note", note.contains("# 周会"))
        assertTrue(note.contains("## 主题摘要"))
        assertTrue(note.contains("## 时间轴要点"))
    }

    // ---------- 模板细节 ----------

    @Test
    fun `模板笔记无 chunks 时关键要点用转写行`() {
        val note = noKeyConnector().buildTemplateNote(
            FusionNoteRequest(
                title = "会议",
                transcription = "第一行内容\n第二行内容\n\n第三行内容",
            )
        )
        assertTrue(note.contains("- 第一行内容"))
        assertTrue(note.contains("- 第二行内容"))
        assertTrue(note.contains("- 第三行内容"))
        assertFalse("空白行不应成为要点", note.contains("- \n"))
    }

    @Test
    fun `模板笔记空转写时三节为空但不崩`() {
        val note = noKeyConnector().buildTemplateNote(
            FusionNoteRequest(title = "空", transcription = "")
        )
        assertTrue(note.contains("# 空"))
        assertTrue(note.contains("## 主题摘要"))
        assertTrue(note.contains("## 关键要点"))
        assertTrue(note.contains("## 时间轴要点"))
        assertFalse(note.contains("- "))
    }

    @Test
    fun `模板笔记标题空白时用默认标题`() {
        val note = noKeyConnector().buildTemplateNote(
            FusionNoteRequest(title = "", transcription = "内容")
        )
        assertTrue(note.contains("# 录音笔记"))
        assertFalse("空白标题不应输出空标题行", note.contains("# \n"))
    }

    @Test
    fun `模板笔记时间轴合并文本与照片按时间排序`() {
        val note = noKeyConnector().buildTemplateNote(
            FusionNoteRequest(
                title = "带照片",
                transcription = "",
                chunks = listOf(
                    FusionChunk(startTime = 30.0, endTime = 40.0, speaker = null, content = "确定排期"),
                    FusionChunk(startTime = 10.0, endTime = 20.0, speaker = null, content = "讨论需求"),
                ),
                attachments = listOf(
                    com.example.ai_recorder_android.cloud.llm.FusionAttachment(timestamp = 15.0, description = "白板图"),
                ),
            )
        )
        // 关键要点节同样带 [Ns] 前缀，须从时间轴节内起查，避免 indexOf 命中前一节
        val timelineStart = note.indexOf("## 时间轴要点")
        val text1 = note.indexOf("[10s] 讨论需求", timelineStart)
        val photo = note.indexOf("[15s] [照片] 白板图", timelineStart)
        val text2 = note.indexOf("[30s] 确定排期", timelineStart)
        assertTrue(text1 in 0 until photo)
        assertTrue(photo in 0 until text2)
    }

    // ---- 辅助 ----

    private fun chatJson(content: String): String =
        Json.encodeToString(
            DeepSeekChatResponse(
                choices = listOf(DeepSeekChoice(message = DeepSeekResponseMessage(content = content))),
            )
        )

    private fun jsonResponse(code: Int, body: String, request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    /** 对任意请求返回固定响应的 client（OkHttp 5 已移除 Builder.callFactory，改用拦截器）。 */
    private fun stubClient(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> jsonResponse(code, body, chain.request()) }
            .build()

    private class FakeTranscriptionStore : TranscriptionStore {
        val saveSummaryCalls = mutableListOf<Pair<Long, String>>()

        override suspend fun saveTranscription(
            recordingId: Long,
            transcriptionText: String,
            chunks: List<TranscriptChunkEntity>,
            durationSeconds: Double?,
        ): Int = chunks.size

        override suspend fun saveSegmentTranscription(
            recordingId: Long,
            segmentIndex: Int,
            chunks: List<TranscriptChunkEntity>,
        ): Int = chunks.size

        override suspend fun markSegmentFailed(recordingId: Long, segmentIndex: Int, error: String?) = Unit
        override suspend fun markRecordingFailed(recordingId: Long, error: String?) = Unit
        override suspend fun markRecordingCompleted(recordingId: Long, durationSeconds: Double?) = Unit

        override suspend fun saveSummary(recordingId: Long, summary: String) {
            saveSummaryCalls += recordingId to summary
        }

        override suspend fun saveTitle(recordingId: Long, title: String) = Unit
    }
}