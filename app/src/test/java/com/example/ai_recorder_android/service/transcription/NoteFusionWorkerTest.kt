package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
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
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoteFusionWorker 纯 JVM 单测（本机不跑 gradlew，其他机器验证）。
 * 覆盖：LLM 返回笔记 → 落库 Recording.summary；无 key → 规则模板回退仍落库；
 * chunks 映射为 FusionChunk（时间戳 / speaker 进 prompt）。
 */
class NoteFusionWorkerTest {

    private val store = FakeTranscriptionStore()

    private val capturedRequest = java.util.concurrent.atomic.AtomicReference<Request?>()

    private fun worker(connector: DeepSeekConnector) = NoteFusionWorker(connector, store)

    private fun llmConnector(llmContent: String): DeepSeekConnector =
        DeepSeekConnector(apiKey = "test-key", client = stubClient(200, chatJson(llmContent), capture = true))

    /**
     * 无 key 场景：注入总是返回 500 的 client，让主用 GLM（本机可能配置了真实 key）
     * 与备用 DeepSeek 都确定性失败，不触网，稳定回退规则模板笔记。
     */
    private fun noKeyConnector(): DeepSeekConnector =
        DeepSeekConnector(apiKey = "", client = stubClient(500, "LLM unavailable"))

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

    @Test
    fun `LLM 返回笔记时返回 LLM 内容并落库 summary`() = runBlocking {
        val llmNote = "## 主题摘要\n产品评审结论\n## 关键要点\n- 需求优先级已定"
        val worker = worker(llmConnector(llmNote))

        val note = worker.generateNote(
            recordingId = 1L,
            title = "产品评审会",
            transcription = "（chunks 优先）",
            chunks = chunks(),
            durationSeconds = 120.0,
        )

        assertEquals(llmNote, note)
        val call = store.saveSummaryCalls.single()
        assertEquals(1L, call.first)
        assertEquals(llmNote, call.second)
    }

    @Test
    fun `无 key 时回退规则模板笔记并落库 summary`() = runBlocking {
        val worker = worker(noKeyConnector())

        val note = worker.generateNote(
            recordingId = 1L,
            title = "产品评审会",
            transcription = "",
            chunks = chunks(),
            durationSeconds = 120.0,
        )

        assertTrue(note.contains("# 产品评审会"))
        assertTrue(note.contains("## 主题摘要"))
        assertTrue(note.contains("## 关键要点"))
        assertTrue(note.contains("## 时间轴要点"))
        assertTrue(note.contains("[10s] 讨论需求优先级"))
        assertTrue(note.contains("[30s] 确定排期"))
        val call = store.saveSummaryCalls.single()
        assertEquals(1L, call.first)
        assertEquals(note, call.second)
    }

    @Test
    fun `chunks 映射为 FusionChunk 时间戳与 speaker 进 prompt`() = runBlocking {
        val connector = DeepSeekConnector(
            apiKey = "test-key",
            client = stubClient(200, chatJson("笔记"), capture = true),
        )

        worker(connector).generateNote(
            recordingId = 1L,
            title = "周会",
            transcription = "（chunks 优先）",
            chunks = chunks(),
            durationSeconds = 90.5,
        )

        val body = capturedRequest.get()!!.body!!.utf8()
        assertTrue(body.contains("[10.0-20.0s] [张三] 讨论需求优先级"))
        assertTrue(body.contains("[30.0-40.0s] 确定排期"))
        assertTrue(body.contains("# 录音标题：周会"))
        assertTrue(body.contains("# 录音时长：90.5 秒"))
        assertTrue("chunks 非空时不应出现纯文本转写", !body.contains("（chunks 优先）"))
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

    /** 返回固定响应的 client；capture=true 时记录最后一次请求（OkHttp 5 已移除 Builder.callFactory）。 */
    private fun stubClient(code: Int, body: String, capture: Boolean = false): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (capture) capturedRequest.set(chain.request())
                jsonResponse(code, body, chain.request())
            }
            .build()

    private fun RequestBody.utf8(): String {
        val sink = Buffer()
        writeTo(sink)
        return sink.readUtf8()
    }

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