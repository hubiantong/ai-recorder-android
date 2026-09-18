package com.example.ai_recorder_android.cloud.asr

/**
 * ASR 连接器异常族（cloud/asr 包内定义，供 todo 16/17 编排层映射为用户可见错误）。
 */

/** API Key 缺失或无效（BuildConfig.SILICONFLOW_API_KEY 为空/空白）。 */
class ApiKeyException(
    message: String = "未配置 SiliconFlow API Key，请在设置中填写后重试",
) : Exception(message)

/** 上传前预检失败：音频文件超过连接器 25MB 上限（Metis B1.1，以连接器为准）。 */
class FileTooLargeException(
    val fileSizeBytes: Long,
    val maxSizeBytes: Long,
) : Exception("音频文件 $fileSizeBytes 字节超过 $maxSizeBytes 字节（25MB）上限，请分段后重试")

/** HTTP 调用失败（非 2xx 或响应体解析失败）。 */
class AsrApiException(
    val statusCode: Int,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : Exception(
    buildString {
        append("ASR 服务调用失败（HTTP ").append(statusCode).append("）")
        if (!responseBody.isNullOrBlank()) append(": ").append(responseBody.take(200))
    },
    cause,
)