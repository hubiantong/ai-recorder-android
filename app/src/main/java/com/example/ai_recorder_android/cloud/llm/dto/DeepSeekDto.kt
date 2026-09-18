package com.example.ai_recorder_android.cloud.llm.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DeepSeek OpenAI 兼容 chat/completions 请求/响应 DTO。
 * 对齐 PC `note_fuser.py`（OpenAI 兼容客户端）：融合笔记与自动标题共用。
 *
 * 端点：POST https://api.deepseek.com/chat/completions
 */

@Serializable
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
)

@Serializable
data class DeepSeekMessage(
    val role: String,
    val content: String,
)

@Serializable
data class DeepSeekChatResponse(
    val choices: List<DeepSeekChoice>? = null,
    val usage: DeepSeekUsage? = null,
    val error: DeepSeekError? = null,
)

@Serializable
data class DeepSeekChoice(
    val message: DeepSeekResponseMessage? = null,
)

@Serializable
data class DeepSeekResponseMessage(
    val content: String? = null,
)

@Serializable
data class DeepSeekUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class DeepSeekError(
    val message: String? = null,
    val code: String? = null,
)