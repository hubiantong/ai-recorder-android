package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatResponse
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChoice
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekResponseMessage
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Timeout
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

    private fun worker(connector: DeepSeekConnector) = NoteFusionWorker(connector, store)

    private fun llmConnector(llmContent: String): DeepSeekConnector {
        val factory = FakeCallFactory(jsonResponse(200, chatJson(llmContent)))
        return DeepSeekConnector(apiKey = "test-key", client = OkHttpClient.Builder().callFactory(factory).build())
    }

    private fun noKeyConnector(): DeepSeekConnector = DeepSeekConnector(apiKey = "")

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
        val factory = FakeCallFactory(jsonResponse(200, chatJson("笔记")))
        val connector = DeepSeekConnector(
            apiKey = "test-key",
            client = OkHttpClient.Builder().callFactory(factory).build(),
        )

        worker(connector).generateNote(
            recordingId = 1L,
            title = "周会",
            transcription = "（chunks 优先）",
            chunks = chunks(),
            durationSeconds = 90.5,
        )

        val body = factory.lastRequest!!.body!!.buffer().readUtf8()
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

    private fun jsonResponse(code: Int, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(DeepSeekConnector.DEFAULT_BASE_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private class FakeCallFactory(private val response: Response) : Call.Factory {
        var lastRequest: Request? = null
            private set

        override fun newCall(request: Request): Call {
            lastRequest = request
            return FakeCall(request, response)
        }
    }

    private class FakeCall(
        private val request: Request,
        private val response: Response,
    ) : Call {
        override fun request(): Request = request
        override fun execute(): Response = response
        override fun enqueue(responseCallback: Callback) = responseCallback.onResponse(this, response)
        override fun cancel() {}
        override fun isCanceled(): Boolean = false
        override fun isExecuted(): Boolean = true
        override fun clone(): Call = FakeCall(request, response)
        override fun timeout(): Timeout = Timeout()
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