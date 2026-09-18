package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatResponse
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChoice
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekResponseMessage
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import java.time.Instant
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
 * AutoTitleWorker 纯 JVM 单测（本机不跑 gradlew，其他机器验证）。
 * 覆盖：LLM 标题 → 落库 Recording.title；LLM 空串 → 回退默认标题；
 * 无 key → 直接默认标题；LLM 返回 >12 字 → 清洗截断 ≤12 字；默认标题格式。
 */
class AutoTitleWorkerTest {

    private val store = FakeTranscriptionStore()

    private fun worker(connector: DeepSeekConnector) = AutoTitleWorker(connector, store)

    private fun llmConnector(llmContent: String): DeepSeekConnector {
        val factory = FakeCallFactory(jsonResponse(200, chatJson(llmContent)))
        return DeepSeekConnector(apiKey = "test-key", client = OkHttpClient.Builder().callFactory(factory).build())
    }

    private fun noKeyConnector(): DeepSeekConnector = DeepSeekConnector(apiKey = "")

    private val transcript = "这是一段足够长的转写文本，用于验证自动标题生成逻辑的完整流程。"

    @Test
    fun `LLM 返回标题时保存并返回该标题`() = runBlocking {
        val worker = worker(llmConnector("人工智能会议纪要"))

        val title = worker.generateTitle(recordingId = 1L, transcription = transcript)

        assertEquals("人工智能会议纪要", title)
        val call = store.saveTitleCalls.single()
        assertEquals(1L, call.first)
        assertEquals("人工智能会议纪要", call.second)
    }

    @Test
    fun `LLM 返回空串时回退默认标题`() = runBlocking {
        val worker = worker(llmConnector(""))

        val title = worker.generateTitle(recordingId = 1L, transcription = transcript)

        assertTrue("应回退默认标题，实际 $title", title.startsWith("录音 "))
        val call = store.saveTitleCalls.single()
        assertEquals(1L, call.first)
        assertEquals(title, call.second)
    }

    @Test
    fun `无 key 时直接使用默认标题`() = runBlocking {
        val worker = worker(noKeyConnector())

        val title = worker.generateTitle(recordingId = 1L, transcription = transcript)

        assertTrue("应使用默认标题，实际 $title", title.startsWith("录音 "))
        val call = store.saveTitleCalls.single()
        assertEquals(1L, call.first)
        assertEquals(title, call.second)
    }

    @Test
    fun `LLM 返回超过 12 字标题时清洗截断`() = runBlocking {
        val worker = worker(llmConnector("关于人工智能在医疗领域的应用前景探讨")) // 18 字

        val title = worker.generateTitle(recordingId = 1L, transcription = transcript)

        assertTrue("清洗后应 ≤12 字，实际 ${title.length}", title.length <= 12)
        assertEquals("关于人工智能在医疗领域的应用前", title)
        assertEquals(title, store.saveTitleCalls.single().second)
    }

    @Test
    fun `默认标题格式为 录音 yyyy-MM-dd HH:mm:ss`() {
        val title = AutoTitleWorker.defaultTitle(Instant.parse("2026-08-16T10:30:00Z").toEpochMilli())
        assertTrue("格式不符，实际 $title", title.matches(Regex("录音 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
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
        override fun newCall(request: Request): Call = FakeCall(request, response)
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
        val saveTitleCalls = mutableListOf<Pair<Long, String>>()

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
        override suspend fun saveSummary(recordingId: Long, summary: String) = Unit

        override suspend fun saveTitle(recordingId: Long, title: String) {
            saveTitleCalls += recordingId to title
        }
    }
}