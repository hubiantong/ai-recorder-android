package com.example.ai_recorder_android.cloud.asr.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * fun-asr-flash-2026-06-15 同步多模态生成接口（multimodal-generation）请求/响应 DTO。
 * 对齐 PC `dashscope_funasr.py`：音频以 base64 data URI 内联，响应 output.output 嵌套两层。
 *
 * 端点：POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
 * 限制：单音频 ≤ 5 分钟（连接器预检，见 DashScopeFlashAsrConnector.MAX_DURATION_SECONDS）。
 */

@Serializable
data class DashScopeFlashRequest(
    val model: String,
    val input: DashScopeFlashInput,
    val parameters: DashScopeFlashParameters = DashScopeFlashParameters(),
)

@Serializable
data class DashScopeFlashInput(
    val messages: List<DashScopeFlashMessage>,
)

@Serializable
data class DashScopeFlashMessage(
    val role: String,
    val content: List<DashScopeFlashContent>,
)

@Serializable
data class DashScopeFlashContent(
    val type: String,
    @SerialName("input_audio") val inputAudio: DashScopeFlashInputAudio? = null,
    val text: String? = null,
)

@Serializable
data class DashScopeFlashInputAudio(
    val data: String,
)

@Serializable
data class DashScopeFlashParameters(
    val format: String = "wav",
    @SerialName("sample_rate") val sampleRate: Int = 16000,
    @SerialName("language_hints") val languageHints: List<String>? = null,
)

@Serializable
data class DashScopeFlashResponse(
    val output: DashScopeFlashOutput? = null,
    @SerialName("request_id") val requestId: String? = null,
    val code: String? = null,
    val message: String? = null,
)

/** 外层 output（文档提及 text 在外层，实测为嵌套层，两层都解析）。 */
@Serializable
data class DashScopeFlashOutput(
    val output: DashScopeFlashInnerOutput? = null,
    val text: String? = null,
    val sentence: DashScopeFlashSentence? = null,
)

/** 嵌套层 output：实际响应结构。 */
@Serializable
data class DashScopeFlashInnerOutput(
    val text: String? = null,
    val sentence: DashScopeFlashSentence? = null,
)

@Serializable
data class DashScopeFlashSentence(
    val text: String? = null,
    @SerialName("begin_time") val beginTime: Long? = null,
    @SerialName("end_time") val endTime: Long? = null,
    val words: List<DashScopeFlashWordDto>? = null,
)

/** 词级时间戳（毫秒，解析时转秒）。 */
@Serializable
data class DashScopeFlashWordDto(
    val text: String = "",
    @SerialName("begin_time") val beginTime: Long? = null,
    @SerialName("end_time") val endTime: Long? = null,
    val punctuation: String = "",
)