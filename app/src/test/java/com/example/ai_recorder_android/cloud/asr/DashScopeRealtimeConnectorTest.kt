package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeClientHeader
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeFinishTask
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeParameters
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeRunTask
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeRunTaskPayload
import com.example.ai_recorder_android.core.audio.Resampler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DashScope realtime 连接器纯 JVM 单测（本机不运行，其他机器验证）：
 * 1. DTO 序列化（run-task / finish-task 帧结构对齐官方文档）
 * 2. 服务端消息解析 + 回调分发（partial / sentence / heartbeat / task-failed / 畸形 JSON）
 * 3. 帧大小数学（frameBytes(8000)=1600 bytes = 100ms）
 * 4. 16k→8k 重采样（Resampler.to8k + PCM bytes 往返）
 * 5. PcmFrameBuffer 分帧逻辑
 */
class DashScopeRealtimeConnectorTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------- 1. DTO 序列化 ----------

    @Test
    fun `run-task 序列化对齐官方帧结构`() {
        val message = DashScopeRealtimeRunTask(
            header = DashScopeRealtimeClientHeader(
                action = "run-task",
                taskId = "2bf83b9a-baeb-4fda-8d9a-xxxxxxxxxxxx",
            ),
            payload = DashScopeRealtimeRunTaskPayload(
                model = "paraformer-realtime-8k-v2",
                parameters = DashScopeRealtimeParameters(sampleRate = 8000),
            ),
        )
        val encoded = json.encodeToString(message)
        val obj = json.parseToJsonElement(encoded).jsonObject

        val header = obj.getValue("header").jsonObject
        assertEquals("run-task", header.getValue("action").jsonPrimitive.content)
        assertEquals("2bf83b9a-baeb-4fda-8d9a-xxxxxxxxxxxx", header.getValue("task_id").jsonPrimitive.content)
        assertEquals("duplex", header.getValue("streaming").jsonPrimitive.content)

        val payload = obj.getValue("payload").jsonObject
        assertEquals("audio", payload.getValue("task_group").jsonPrimitive.content)
        assertEquals("asr", payload.getValue("task").jsonPrimitive.content)
        assertEquals("recognition", payload.getValue("function").jsonPrimitive.content)
        assertEquals("paraformer-realtime-8k-v2", payload.getValue("model").jsonPrimitive.content)
        assertEquals("{}", payload.getValue("input").toString())

        val parameters = payload.getValue("parameters").jsonObject
        assertEquals("pcm", parameters.getValue("format").jsonPrimitive.content)
        assertEquals(8000, parameters.getValue("sample_rate").jsonPrimitive.int)
    }

    @Test
    fun `run-task 带 language_hints 序列化`() {
        val message = DashScopeRealtimeRunTask(
            header = DashScopeRealtimeClientHeader(action = "run-task", taskId = "t1"),
            payload = DashScopeRealtimeRunTaskPayload(
                model = "paraformer-realtime-8k-v2",
                parameters = DashScopeRealtimeParameters(sampleRate = 8000, languageHints = listOf("zh")),
            ),
        )
        val obj = json.parseToJsonElement(json.encodeToString(message)).jsonObject
        val hints = obj.getValue("payload").jsonObject
            .getValue("parameters").jsonObject
            .getValue("language_hints").jsonArray
        assertEquals("zh", hints[0].jsonPrimitive.content)
    }

    @Test
    fun `finish-task 序列化对齐官方帧结构`() {
        val message = DashScopeRealtimeFinishTask(
            header = DashScopeRealtimeClientHeader(action = "finish-task", taskId = "t1"),
        )
        val obj = json.parseToJsonElement(json.encodeToString(message)).jsonObject
        val header = obj.getValue("header").jsonObject
        assertEquals("finish-task", header.getValue("action").jsonPrimitive.content)
        assertEquals("t1", header.getValue("task_id").jsonPrimitive.content)
        assertEquals("duplex", header.getValue("streaming").jsonPrimitive.content)
        assertEquals("{}", obj.getValue("payload").jsonObject.getValue("input").toString())
    }

    // ---------- 2. 服务端消息解析 + 回调分发 ----------

    @Test
    fun `task-started 触发 onTaskStarted`() {
        var started = false
        val handler = DashScopeRealtimeMessageHandler(json, RecordingCallback())
        handler.handle(
            """{"header":{"task_id":"t1","event":"task-started","attributes":{}},"payload":{}}""",
            onTaskStarted = { started = true },
        )
        assertTrue(started)
    }

    @Test
    fun `result-generated partial 触发 onPartial`() {
        val cb = RecordingCallback()
        val handler = DashScopeRealtimeMessageHandler(json, cb)
        handler.handle(
            """{"header":{"task_id":"t1","event":"result-generated","attributes":{}},
                "payload":{"output":{"sentence":{
                    "begin_time":170,"end_time":null,"text":"好，我","heartbeat":false,
                    "sentence_end":false,"words":[]}},"usage":null}}""",
        )
        assertEquals(listOf("好，我"), cb.partials)
        assertTrue(cb.sentences.isEmpty())
    }

    @Test
    fun `result-generated sentence_end 触发 onSentence 带时间戳`() {
        val cb = RecordingCallback()
        val handler = DashScopeRealtimeMessageHandler(json, cb)
        handler.handle(
            """{"header":{"task_id":"t1","event":"result-generated","attributes":{}},
                "payload":{"output":{"sentence":{
                    "begin_time":170,"end_time":920,"text":"好，我知道了","heartbeat":false,
                    "sentence_end":true,"words":[
                        {"begin_time":170,"end_time":295,"text":"好","punctuation":"，"}]}},
                    "usage":{"duration":3}}}""",
        )
        assertTrue(cb.partials.isEmpty())
        assertEquals(1, cb.sentences.size)
        assertEquals("好，我知道了", cb.sentences[0].text)
        assertEquals(170, cb.sentences[0].beginTimeMs)
        assertEquals(920, cb.sentences[0].endTimeMs)
    }

    @Test
    fun `heartbeat 结果被跳过`() {
        val cb = RecordingCallback()
        val handler = DashScopeRealtimeMessageHandler(json, cb)
        handler.handle(
            """{"header":{"task_id":"t1","event":"result-generated","attributes":{}},
                "payload":{"output":{"sentence":{
                    "begin_time":0,"end_time":null,"text":"","heartbeat":true,
                    "sentence_end":false}},"usage":null}}""",
        )
        assertTrue(cb.partials.isEmpty())
        assertTrue(cb.sentences.isEmpty())
    }

    @Test
    fun `task-failed 触发不可重试 onError`() {
        val cb = RecordingCallback()
        val handler = DashScopeRealtimeMessageHandler(json, cb)
        handler.handle(
            """{"header":{"task_id":"t1","event":"task-failed",
                "error_code":"CLIENT_ERROR","error_message":"request timeout after 23 seconds.",
                "attributes":{}},"payload":{}}""",
        )
        assertEquals(1, cb.errors.size)
        val error = cb.errors[0] as DashScopeRealtimeException
        assertEquals("request timeout after 23 seconds.", error.message)
        assertFalse(error.retryable)
    }

    @Test
    fun `task-finished 触发 onClosed`() {
        val cb = RecordingCallback()
        val handler = DashScopeRealtimeMessageHandler(json, cb)
        handler.handle(
            """{"header":{"task_id":"t1","event":"task-finished","attributes":{}},
                "payload":{"output":{},"usage":null}}""",
        )
        assertEquals(1, cb.closedCount)
    }

    @Test
    fun `畸形 JSON 触发 onError 不崩溃`() {
        val cb = RecordingCallback()
        val handler = DashScopeRealtimeMessageHandler(json, cb)
        handler.handle("not-json{{{")
        assertEquals(1, cb.errors.size)
    }

    // ---------- 3. 帧大小数学 ----------

    @Test
    fun `frameBytes 8kHz 为 1600 bytes 且等于 100ms`() {
        assertEquals(1600, DashScopeRealtimeConnector.frameBytes(8000))
        // 1600 bytes = 800 样本（16bit mono），800 / 8000 Hz = 0.1s = 100ms
        assertEquals(800, 1600 / 2)
        assertEquals(0.1, 800 / 8000.0, 1e-9)
    }

    @Test
    fun `frameBytes 16kHz 为 3200 bytes（PC 公式对齐）`() {
        assertEquals(3200, DashScopeRealtimeConnector.frameBytes(16000))
        // 与 PC int(sample_rate * 0.2) 一致
        assertEquals((16000 * 0.2).toInt(), DashScopeRealtimeConnector.frameBytes(16000))
        assertEquals((8000 * 0.2).toInt(), DashScopeRealtimeConnector.frameBytes(8000))
    }

    // ---------- 4. 16k→8k 重采样 ----------

    @Test
    fun `Resampler to8k 输出样本数减半`() {
        val input = ShortArray(16000) { (it % 1000).toShort() } // 1s @ 16kHz
        val output = Resampler.to8k(input)
        assertEquals(8000, output.size) // 1s @ 8kHz
    }

    @Test
    fun `PCM bytes 往返 16k 到 8k 长度减半`() {
        val pcm16k = DashScopeRealtimeConnector.toByteArray(ShortArray(16000) { 42 })
        assertEquals(32000, pcm16k.size)
        val resampled = Resampler.to8k(DashScopeRealtimeConnector.toShortArray(pcm16k))
        assertEquals(8000, resampled.size)
        val pcm8k = DashScopeRealtimeConnector.toByteArray(resampled)
        assertEquals(16000, pcm8k.size) // 1s @ 8kHz = 16000 bytes
    }

    @Test
    fun `toShortArray 小端解析与 toByteArray 往返一致`() {
        val original = shortArrayOf(0, 1, -1, 32767, -32768, 12345)
        val bytes = DashScopeRealtimeConnector.toByteArray(original)
        val roundtrip = DashScopeRealtimeConnector.toShortArray(bytes)
        assertTrue(original.contentEquals(roundtrip))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toShortArray 拒绝奇数长度`() {
        DashScopeRealtimeConnector.toShortArray(byteArrayOf(1, 2, 3))
    }

    // ---------- 5. PcmFrameBuffer 分帧 ----------

    @Test
    fun `frameBuffer 切出完整帧并保留余量`() {
        val buffer = PcmFrameBuffer(1600)
        val frames = buffer.add(ByteArray(3200) { 1 })
        assertEquals(2, frames.size)
        assertEquals(1600, frames[0].size)
        assertEquals(1600, frames[1].size)
        assertEquals(0, buffer.remaining().size)
    }

    @Test
    fun `frameBuffer 余量不足一帧时保留`() {
        val buffer = PcmFrameBuffer(1600)
        val frames = buffer.add(ByteArray(1700) { 1 })
        assertEquals(1, frames.size)
        assertEquals(100, buffer.remaining().size)
        buffer.clear()
        assertEquals(0, buffer.remaining().size)
    }

    @Test
    fun `frameBuffer 跨次追加累积成帧`() {
        val buffer = PcmFrameBuffer(1600)
        assertEquals(0, buffer.add(ByteArray(1000) { 1 }).size)
        val frames = buffer.add(ByteArray(1000) { 1 })
        assertEquals(1, frames.size)
        assertEquals(400, buffer.remaining().size)
    }

    // ---------- 辅助 ----------

    private class RecordingCallback : DashScopeRealtimeCallback {
        val partials = mutableListOf<String>()
        val sentences = mutableListOf<Sentence>()
        val errors = mutableListOf<Throwable>()
        var closedCount = 0

        override fun onPartial(text: String) {
            partials.add(text)
        }

        override fun onSentence(text: String, beginTimeMs: Int?, endTimeMs: Int?) {
            sentences.add(Sentence(text, beginTimeMs, endTimeMs))
        }

        override fun onError(error: Throwable) {
            errors.add(error)
        }

        override fun onClosed() {
            closedCount++
        }
    }

    private data class Sentence(val text: String, val beginTimeMs: Int?, val endTimeMs: Int?)
}