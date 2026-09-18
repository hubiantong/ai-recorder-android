package com.example.ai_recorder_android.cloud.vlm.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Qwen-VL OpenAI 兼容模式（DashScope compatible-mode）请求/响应 DTO。
 * 对齐 PC `qwen_vl.py`：image_url 传 data URI（data:image/<type>;base64,...）。
 *
 * 端点：POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 */

@Serializable
data class QwenVlChatRequest(
    val model: String,
    val messages: List<QwenVlMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 500,
)

@Serializable
data class QwenVlMessage(
    val role: String,
    val content: List<QwenVlContentPart>,
)

@Serializable
data class QwenVlContentPart(
    val type: String,
    @SerialName("image_url") val imageUrl: QwenVlImageUrl? = null,
    val text: String? = null,
)

@Serializable
data class QwenVlImageUrl(
    val url: String,
)

@Serializable
data class QwenVlChatResponse(
    val choices: List<QwenVlChoice>? = null,
    val usage: QwenVlUsage? = null,
    val error: QwenVlError? = null,
)

@Serializable
data class QwenVlChoice(
    val message: QwenVlResponseMessage? = null,
)

@Serializable
data class QwenVlResponseMessage(
    val content: String? = null,
)

@Serializable
data class QwenVlUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class QwenVlError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)