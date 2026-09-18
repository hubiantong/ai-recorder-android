package com.example.ai_recorder_android.cloud.asr.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DashScope Paraformer 实时语音识别 WebSocket 消息帧 DTO。
 *
 * 协议依据 `.omo/notes/dashscope-realtime-protocol.md`（官方 action 协议）：
 * - 客户端事件：run-task / finish-task（header.action + task_id + streaming=duplex）
 * - 服务端事件：task-started / result-generated / task-finished / task-failed（header.event）
 * - 音频为独立二进制帧（单声道 PCM），不在此 DTO 内。
 *
 * 字段名与官方文档 JSON 完全一致（snake_case），kotlinx-serialization 显式 @SerialName。
 */

/** 客户端事件 header（run-task / finish-task）。 */
@Serializable
data class DashScopeRealtimeClientHeader(
    @SerialName("action") val action: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("streaming") val streaming: String = "duplex",
)

/** run-task 指令（连接建立后立即发送）。 */
@Serializable
data class DashScopeRealtimeRunTask(
    @SerialName("header") val header: DashScopeRealtimeClientHeader,
    @SerialName("payload") val payload: DashScopeRealtimeRunTaskPayload,
)

@Serializable
data class DashScopeRealtimeRunTaskPayload(
    @SerialName("task_group") val taskGroup: String = "audio",
    @SerialName("task") val task: String = "asr",
    @SerialName("function") val function: String = "recognition",
    @SerialName("model") val model: String,
    @SerialName("parameters") val parameters: DashScopeRealtimeParameters,
    @SerialName("input") val input: JsonObject = JsonObject(emptyMap()),
)

/** run-task 语音识别参数（官方文档 parameters 字段子集，未启用的参数不序列化）。 */
@Serializable
data class DashScopeRealtimeParameters(
    @SerialName("format") val format: String = "pcm",
    @SerialName("sample_rate") val sampleRate: Int,
    @SerialName("language_hints") val languageHints: List<String>? = null,
    @SerialName("disfluency_removal_enabled") val disfluencyRemovalEnabled: Boolean? = null,
    @SerialName("semantic_punctuation_enabled") val semanticPunctuationEnabled: Boolean? = null,
    @SerialName("max_sentence_silence") val maxSentenceSilence: Int? = null,
    @SerialName("punctuation_prediction_enabled") val punctuationPredictionEnabled: Boolean? = null,
    @SerialName("inverse_text_normalization_enabled") val inverseTextNormalizationEnabled: Boolean? = null,
)

/** finish-task 指令（音频发送完毕后发送）。 */
@Serializable
data class DashScopeRealtimeFinishTask(
    @SerialName("header") val header: DashScopeRealtimeClientHeader,
    @SerialName("payload") val payload: DashScopeRealtimeFinishTaskPayload =
        DashScopeRealtimeFinishTaskPayload(),
)

@Serializable
data class DashScopeRealtimeFinishTaskPayload(
    @SerialName("input") val input: JsonObject = JsonObject(emptyMap()),
)

/** 服务端事件 header（task-started / result-generated / task-finished / task-failed）。 */
@Serializable
data class DashScopeRealtimeServerHeader(
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("event") val event: String? = null,
    @SerialName("attributes") val attributes: JsonObject? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

/** 服务端事件通用信封：header + payload（payload 按 event 类型二次解码）。 */
@Serializable
data class DashScopeRealtimeServerMessage(
    @SerialName("header") val header: DashScopeRealtimeServerHeader,
    @SerialName("payload") val payload: JsonObject = JsonObject(emptyMap()),
)

/** result-generated 的 payload（output.sentence + usage）。 */
@Serializable
data class DashScopeRealtimeResultPayload(
    @SerialName("output") val output: DashScopeRealtimeOutput? = null,
    @SerialName("usage") val usage: DashScopeRealtimeUsage? = null,
)

@Serializable
data class DashScopeRealtimeOutput(
    @SerialName("sentence") val sentence: DashScopeRealtimeSentence? = null,
)

/**
 * 识别句子。
 *
 * - [sentenceEnd]=false → 中间结果（partial），[endTimeMs] 通常为 null；
 * - [sentenceEnd]=true → 最终句子，[beginTimeMs]/[endTimeMs] 为毫秒时间戳；
 * - [heartbeat]=true → 心跳包，应跳过。
 */
@Serializable
data class DashScopeRealtimeSentence(
    @SerialName("begin_time") val beginTimeMs: Int? = null,
    @SerialName("end_time") val endTimeMs: Int? = null,
    @SerialName("text") val text: String = "",
    @SerialName("heartbeat") val heartbeat: Boolean = false,
    @SerialName("sentence_end") val sentenceEnd: Boolean = false,
    @SerialName("words") val words: List<DashScopeRealtimeWord>? = null,
)

@Serializable
data class DashScopeRealtimeWord(
    @SerialName("begin_time") val beginTimeMs: Int? = null,
    @SerialName("end_time") val endTimeMs: Int? = null,
    @SerialName("text") val text: String = "",
    @SerialName("punctuation") val punctuation: String = "",
)

@Serializable
data class DashScopeRealtimeUsage(
    @SerialName("duration") val duration: Int? = null,
)