package com.example.ai_recorder_android.cloud.asr.dto

import kotlinx.serialization.Serializable

/**
 * OpenAI Whisper 兼容端点（SiliconFlow `/v1/audio/transcriptions`）的 `verbose_json` 响应 DTO。
 *
 * 对齐 PC `core/transcription/connectors/openai_whisper.py` 的响应解析：
 * - [text]：整段转写文本（PC `transcript.text`）
 * - [language]：检测/指定的语言，可空（PC `getattr(transcript, 'language', None)`）
 * - [segments]：带时间戳的分段（PC `TranscriptionSegment(text, start_time, end_time)`）
 * - [duration]：音频时长（秒，verbose_json 附带，PC 亦读取）
 *
 * 解析使用 `Json { ignoreUnknownKeys = true }`，容忍服务端附加字段
 * （如分段里的 id/seek/tokens/temperature/avg_logprob/compression_ratio/no_speech_prob）。
 */
@Serializable
data class OpenAiWhisperResponse(
    val text: String = "",
    val language: String? = null,
    val segments: List<OpenAiWhisperSegment> = emptyList(),
    val duration: Double? = null,
)

/** `verbose_json` 的单个分段（对齐 PC `TranscriptionSegment`：text/start_time/end_time，秒）。 */
@Serializable
data class OpenAiWhisperSegment(
    val text: String = "",
    val start: Double? = null,
    val end: Double? = null,
)