package com.example.ai_recorder_android.cloud.asr.dto

import kotlinx.serialization.Serializable

/**
 * 火山引擎 豆包录音文件识别 2.0（pro_lite 档）DTO。
 * 对齐 PC `volc_doubao_file.py` 的 submit 请求体与 query 响应结构。
 */

/** submit 请求体（PC `submit_body`）。 */
@Serializable
data class VolcSubmitRequest(
    val user: VolcUser = VolcUser(uid = "airecorder"),
    val audio: VolcAudio,
    val request: VolcRequest = VolcRequest(),
)

@Serializable
data class VolcUser(val uid: String)

@Serializable
data class VolcAudio(
    val format: String = "wav",
    val url: String,
    val rate: Int = 16000,
    val channel: Int = 1,
    val language: String? = null,
)

@Serializable
data class VolcRequest(
    val model_name: String = "bigmodel",
    val enable_itn: Boolean = true,
    val enable_punc: Boolean = true,
    val show_utterances: Boolean = true,
)

/** query 响应体（任务状态在 X-Api-Status-Code 头，body 为任务数据）。 */
@Serializable
data class VolcQueryResponse(
    val code: Int? = null,
    val message: String? = null,
    val request_id: String? = null,
    val audio_info: VolcAudioInfo? = null,
    val result: VolcResult? = null,
)

@Serializable
data class VolcAudioInfo(val duration: Long? = null)

@Serializable
data class VolcResult(
    val text: String? = null,
    val utterances: List<VolcUtterance>? = null,
)

@Serializable
data class VolcUtterance(
    val text: String? = null,
    val start_time: Long? = null,
    val end_time: Long? = null,
)