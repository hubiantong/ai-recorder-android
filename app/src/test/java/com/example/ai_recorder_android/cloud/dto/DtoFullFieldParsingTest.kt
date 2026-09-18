package com.example.ai_recorder_android.cloud.dto

import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeClientHeader
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeFinishTask
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeParameters
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeResultPayload
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeRunTask
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeRunTaskPayload
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeServerMessage
import com.example.ai_recorder_android.cloud.asr.dto.OpenAiWhisperResponse
import com.example.ai_recorder_android.cloud.asr.dto.VolcAudio
import com.example.ai_recorder_android.cloud.asr.dto.VolcQueryResponse
import com.example.ai_recorder_android.cloud.asr.dto.VolcRequest
import com.example.ai_recorder_android.cloud.asr.dto.VolcSubmitRequest
import com.example.ai_recorder_android.cloud.asr.dto.VolcUser
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatRequest
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatResponse
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekMessage
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlChatRequest
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlChatResponse
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlContentPart
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlImageUrl
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO 全字段解析/序列化单测（纯 JVM，本机不跑 gradlew，其他机器验证）。
 *
 * 覆盖 Wave 2-6 五个 DTO 族的**全部字段**：
 * - [OpenAiWhisperResponse]（free 档 SiliconFlow）
 * - [VolcSubmitRequest] / [VolcQueryResponse]（pro_lite 档火山）
 * - [DashScopeRealtimeDto]（realtime 档 WebSocket）
 * - [QwenVlDto]（VLM 图片描述）
 * - [DeepSeekDto]（LLM 融合笔记/标题）
 *
 * 解析用 `Json { ignoreUnknownKeys = true }`（对齐生产配置，容忍服务端附加字段）；
 * 序列化用 `Json { encodeDefaults = true; explicitNulls = false }`（对齐生产请求构造）。
 */
class DtoFullFieldParsingTest {

    private val parseJson = Json { ignoreUnknownKeys = true }
    private val encodeJson = Json { encodeDefaults = true; explicitNulls = false }

    // ---------- OpenAiWhisperResponse（free 档） ----------

    @Test
    fun openAiWhisperResponse_parsesAllFields() {
        val json = """
            {
              "text": "你好，世界。",
              "language": "zh",
              "duration": 12.5,
              "segments": [
                {
                  "text": "你好", "start": 0.0, "end": 1.5,
                  "id": 0, "seek": 0, "tokens": [50364, 2300], "temperature": 0.0,
                  "avg_logprob": -0.2, "compression_ratio": 1.0, "no_speech_prob": 0.01
                },
                {"text": "世界", "start": 1.5, "end": 3.0}
              ]
            }
        """.trimIndent()

        val resp = parseJson.decodeFromString<OpenAiWhisperResponse>(json)

        assertEquals("你好，世界。", resp.text)
        assertEquals("zh", resp.language)
        assertEquals(12.5, resp.duration!!, 1e-9)
        assertEquals(2, resp.segments.size)
        assertEquals("你好", resp.segments[0].text)
        assertEquals(0.0, resp.segments[0].start!!, 1e-9)
        assertEquals(1.5, resp.segments[0].end!!, 1e-9)
        assertEquals("世界", resp.segments[1].text)
        assertEquals(1.5, resp.segments[1].start!!, 1e-9)
        assertEquals(3.0, resp.segments[1].end!!, 1e-9)
    }

    @Test
    fun openAiWhisperResponse_unknownSegmentFieldsTolerated() {
        // verbose_json 分段含 id/seek/tokens/temperature/avg_logprob 等附加字段 → ignoreUnknownKeys 容忍
        val json = """
            {"text": "x", "segments": [{"text": "y", "start": 0.0, "end": 1.0, "id": 7, "seek": 0, "tokens": [1]}]}
        """.trimIndent()
        val resp = parseJson.decodeFromString<OpenAiWhisperResponse>(json)
        assertEquals("y", resp.segments.single().text)
    }

    @Test
    fun openAiWhisperResponse_emptyJson_usesDefaults() {
        val resp = parseJson.decodeFromString<OpenAiWhisperResponse>("{}")
        assertEquals("", resp.text)
        assertNull(resp.language)
        assertTrue(resp.segments.isEmpty())
        assertNull(resp.duration)
    }

    // ---------- VolcDoubaoDto（pro_lite 档） ----------

    @Test
    fun volcSubmitRequest_serializesAllFields() {
        val request = VolcSubmitRequest(
            user = VolcUser(uid = "airecorder"),
            audio = VolcAudio(
                format = "wav",
                url = "https://example.com/audio.wav",
                rate = 16000,
                channel = 1,
                language = "zh",
            ),
            request = VolcRequest(
                model_name = "bigmodel",
                enable_itn = true,
                enable_punc = true,
                show_utterances = true,
            ),
        )

        val json = encodeJson.encodeToString(request)

        assertTrue(json.contains("\"user\":{\"uid\":\"airecorder\"}"))
        assertTrue(json.contains("\"audio\":{\"format\":\"wav\""))
        assertTrue(json.contains("\"url\":\"https://example.com/audio.wav\""))
        assertTrue(json.contains("\"rate\":16000"))
        assertTrue(json.contains("\"channel\":1"))
        assertTrue(json.contains("\"language\":\"zh\""))
        assertTrue(json.contains("\"request\":{\"model_name\":\"bigmodel\""))
        assertTrue(json.contains("\"enable_itn\":true"))
        assertTrue(json.contains("\"enable_punc\":true"))
        assertTrue(json.contains("\"show_utterances\":true"))
    }

    @Test
    fun volcSubmitRequest_languageNull_omitted() {
        val request = VolcSubmitRequest(
            audio = VolcAudio(url = "https://example.com/a.wav", language = null),
        )
        val json = encodeJson.encodeToString(request)
        assertFalse("language=null 应省略", json.contains("language"))
    }

    @Test
    fun volcQueryResponse_parsesAllFields() {
        val json = """
            {
              "code": 1000,
              "message": "success",
              "request_id": "req-123",
              "audio_info": {"duration": 120},
              "result": {
                "text": "完整转写文本",
                "utterances": [
                  {"text": "第一句", "start_time": 0, "end_time": 1500},
                  {"text": "第二句", "start_time": 1500, "end_time": 3000}
                ]
              }
            }
        """.trimIndent()

        val resp = parseJson.decodeFromString<VolcQueryResponse>(json)

        assertEquals(1000, resp.code)
        assertEquals("success", resp.message)
        assertEquals("req-123", resp.request_id)
        assertEquals(120L, resp.audio_info?.duration)
        assertEquals("完整转写文本", resp.result?.text)
        assertEquals(2, resp.result?.utterances?.size)
        assertEquals("第一句", resp.result?.utterances?.get(0)?.text)
        assertEquals(0L, resp.result?.utterances?.get(0)?.start_time)
        assertEquals(1500L, resp.result?.utterances?.get(0)?.end_time)
        assertEquals("第二句", resp.result?.utterances?.get(1)?.text)
        assertEquals(1500L, resp.result?.utterances?.get(1)?.start_time)
        assertEquals(3000L, resp.result?.utterances?.get(1)?.end_time)
    }

    @Test
    fun volcQueryResponse_emptyJson_usesDefaults() {
        val resp = parseJson.decodeFromString<VolcQueryResponse>("{}")
        assertNull(resp.code)
        assertNull(resp.message)
        assertNull(resp.request_id)
        assertNull(resp.audio_info)
        assertNull(resp.result)
    }

    // ---------- DashScopeRealtimeDto（realtime 档） ----------

    @Test
    fun realtimeClientHeader_serializesAllFields() {
        val json = encodeJson.encodeToString(
            DashScopeRealtimeClientHeader(action = "run-task", taskId = "task-1", streaming = "duplex")
        )
        assertTrue(json.contains("\"action\":\"run-task\""))
        assertTrue(json.contains("\"task_id\":\"task-1\""))
        assertTrue(json.contains("\"streaming\":\"duplex\""))
    }

    @Test
    fun realtimeRunTask_serializesAllFields() {
        val task = DashScopeRealtimeRunTask(
            header = DashScopeRealtimeClientHeader(action = "run-task", taskId = "task-1"),
            payload = DashScopeRealtimeRunTaskPayload(
                taskGroup = "audio",
                task = "asr",
                function = "recognition",
                model = "paraformer-realtime-8k-v2",
                parameters = DashScopeRealtimeParameters(
                    format = "pcm",
                    sampleRate = 8000,
                    languageHints = listOf("zh"),
                    disfluencyRemovalEnabled = true,
                    semanticPunctuationEnabled = true,
                    maxSentenceSilence = 500,
                    punctuationPredictionEnabled = true,
                    inverseTextNormalizationEnabled = true,
                ),
            ),
        )

        val json = encodeJson.encodeToString(task)

        assertTrue(json.contains("\"task_group\":\"audio\""))
        assertTrue(json.contains("\"task\":\"asr\""))
        assertTrue(json.contains("\"function\":\"recognition\""))
        assertTrue(json.contains("\"model\":\"paraformer-realtime-8k-v2\""))
        assertTrue(json.contains("\"format\":\"pcm\""))
        assertTrue(json.contains("\"sample_rate\":8000"))
        assertTrue(json.contains("\"language_hints\":[\"zh\"]"))
        assertTrue(json.contains("\"disfluency_removal_enabled\":true"))
        assertTrue(json.contains("\"semantic_punctuation_enabled\":true"))
        assertTrue(json.contains("\"max_sentence_silence\":500"))
        assertTrue(json.contains("\"punctuation_prediction_enabled\":true"))
        assertTrue(json.contains("\"inverse_text_normalization_enabled\":true"))
        assertTrue(json.contains("\"input\":{}"))
    }

    @Test
    fun realtimeRunTask_nullOptionalParameters_omitted() {
        val task = DashScopeRealtimeRunTask(
            header = DashScopeRealtimeClientHeader(action = "run-task", taskId = "task-1"),
            payload = DashScopeRealtimeRunTaskPayload(
                model = "paraformer-realtime-8k-v2",
                parameters = DashScopeRealtimeParameters(sampleRate = 8000),
            ),
        )
        val json = encodeJson.encodeToString(task)
        assertFalse(json.contains("language_hints"))
        assertFalse(json.contains("disfluency_removal_enabled"))
        assertFalse(json.contains("max_sentence_silence"))
    }

    @Test
    fun realtimeFinishTask_serializes() {
        val json = encodeJson.encodeToString(
            DashScopeRealtimeFinishTask(
                header = DashScopeRealtimeClientHeader(action = "finish-task", taskId = "task-1"),
            )
        )
        assertTrue(json.contains("\"action\":\"finish-task\""))
        assertTrue(json.contains("\"task_id\":\"task-1\""))
        assertTrue(json.contains("\"payload\":{\"input\":{}}"))
    }

    @Test
    fun realtimeServerMessage_parsesAllFields() {
        val json = """
            {
              "header": {
                "task_id": "task-1",
                "event": "result-generated",
                "attributes": {"key": "value"},
                "error_code": null,
                "error_message": null
              },
              "payload": {
                "output": {
                  "sentence": {
                    "begin_time": 1000,
                    "end_time": 2500,
                    "text": "你好",
                    "heartbeat": false,
                    "sentence_end": true,
                    "words": [
                      {"begin_time": 1000, "end_time": 1500, "text": "你好", "punctuation": ""}
                    ]
                  }
                },
                "usage": {"duration": 2500}
              }
            }
        """.trimIndent()

        val msg = parseJson.decodeFromString<DashScopeRealtimeServerMessage>(json)

        assertEquals("task-1", msg.header.taskId)
        assertEquals("result-generated", msg.header.event)
        assertEquals("value", msg.header.attributes?.get("key")?.jsonPrimitive?.content)
        assertNull(msg.header.errorCode)
        assertNull(msg.header.errorMessage)

        val payload = parseJson.decodeFromString<DashScopeRealtimeResultPayload>(msg.payload.toString())
        val sentence = payload.output?.sentence
        assertEquals(1000, sentence?.beginTimeMs)
        assertEquals(2500, sentence?.endTimeMs)
        assertEquals("你好", sentence?.text)
        assertFalse(sentence?.heartbeat ?: true)
        assertTrue(sentence?.sentenceEnd ?: false)
        assertEquals(1, sentence?.words?.size)
        assertEquals(1000, sentence?.words?.get(0)?.beginTimeMs)
        assertEquals(1500, sentence?.words?.get(0)?.endTimeMs)
        assertEquals("你好", sentence?.words?.get(0)?.text)
        assertEquals("", sentence?.words?.get(0)?.punctuation)
        assertEquals(2500, payload.usage?.duration)
    }

    @Test
    fun realtimeResultPayload_partialSentence_parses() {
        // partial：sentence_end=false、end_time=null、heartbeat=false
        val json = """{"output": {"sentence": {"begin_time": 1000, "text": "你好"}}}"""
        val payload = parseJson.decodeFromString<DashScopeRealtimeResultPayload>(json)
        val sentence = payload.output?.sentence
        assertEquals(1000, sentence?.beginTimeMs)
        assertNull(sentence?.endTimeMs)
        assertFalse(sentence?.sentenceEnd ?: true)
    }

    // ---------- QwenVlDto（VLM） ----------

    @Test
    fun qwenVlRequest_serializesAllFields() {
        val request = QwenVlChatRequest(
            model = "qwen-vl-max",
            messages = listOf(
                QwenVlMessage(
                    role = "user",
                    content = listOf(
                        QwenVlContentPart(
                            type = "image_url",
                            imageUrl = QwenVlImageUrl(url = "data:image/jpeg;base64,xxx"),
                        ),
                        QwenVlContentPart(type = "text", text = "描述这张图片"),
                    ),
                ),
            ),
            maxTokens = 500,
        )

        val json = encodeJson.encodeToString(request)

        assertTrue(json.contains("\"model\":\"qwen-vl-max\""))
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("\"type\":\"image_url\""))
        assertTrue(json.contains("\"image_url\":{\"url\":\"data:image/jpeg;base64,xxx\"}"))
        assertTrue(json.contains("\"type\":\"text\""))
        assertTrue(json.contains("\"text\":\"描述这张图片\""))
        assertTrue(json.contains("\"max_tokens\":500"))
    }

    @Test
    fun qwenVlResponse_parsesAllFields() {
        val json = """
            {
              "choices": [{"message": {"role": "assistant", "content": "这是一张白板照片"}}],
              "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
              "error": null
            }
        """.trimIndent()

        val resp = parseJson.decodeFromString<QwenVlChatResponse>(json)

        assertEquals("这是一张白板照片", resp.choices?.single()?.message?.content)
        assertEquals(100, resp.usage?.promptTokens)
        assertEquals(50, resp.usage?.completionTokens)
        assertEquals(150, resp.usage?.totalTokens)
        assertNull(resp.error)
    }

    @Test
    fun qwenVlResponse_error_parsesAllFields() {
        val json = """{"error": {"message": "Invalid API key", "type": "invalid_request_error", "code": "401"}}"""
        val resp = parseJson.decodeFromString<QwenVlChatResponse>(json)
        assertEquals("Invalid API key", resp.error?.message)
        assertEquals("invalid_request_error", resp.error?.type)
        assertEquals("401", resp.error?.code)
    }

    // ---------- DeepSeekDto（LLM） ----------

    @Test
    fun deepSeekRequest_serializesAllFields() {
        val request = DeepSeekChatRequest(
            model = "deepseek-v4-flash",
            messages = listOf(
                DeepSeekMessage(role = "system", content = "你是笔记助手"),
                DeepSeekMessage(role = "user", content = "生成融合笔记"),
            ),
            maxTokens = 2048,
            temperature = 0.3,
        )

        val json = encodeJson.encodeToString(request)

        assertTrue(json.contains("\"model\":\"deepseek-v4-flash\""))
        assertTrue(json.contains("\"role\":\"system\""))
        assertTrue(json.contains("\"content\":\"你是笔记助手\""))
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("\"content\":\"生成融合笔记\""))
        assertTrue(json.contains("\"max_tokens\":2048"))
        assertTrue(json.contains("\"temperature\":0.3"))
    }

    @Test
    fun deepSeekRequest_nullOptionalFields_omitted() {
        val request = DeepSeekChatRequest(
            model = "deepseek-v4-flash",
            messages = listOf(DeepSeekMessage(role = "user", content = "u")),
            maxTokens = null,
            temperature = null,
        )
        val json = encodeJson.encodeToString(request)
        assertFalse(json.contains("max_tokens"))
        assertFalse(json.contains("temperature"))
    }

    @Test
    fun deepSeekResponse_parsesAllFields() {
        val json = """
            {
              "choices": [{"message": {"role": "assistant", "content": "人工智能会议纪要"}}],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
              "error": null
            }
        """.trimIndent()

        val resp = parseJson.decodeFromString<DeepSeekChatResponse>(json)

        assertEquals("人工智能会议纪要", resp.choices?.single()?.message?.content)
        assertEquals(10, resp.usage?.promptTokens)
        assertEquals(5, resp.usage?.completionTokens)
        assertEquals(15, resp.usage?.totalTokens)
        assertNull(resp.error)
    }

    @Test
    fun deepSeekResponse_error_parsesAllFields() {
        val json = """{"error": {"message": "Invalid API key", "code": "401"}}"""
        val resp = parseJson.decodeFromString<DeepSeekChatResponse>(json)
        assertEquals("Invalid API key", resp.error?.message)
        assertEquals("401", resp.error?.code)
    }
}