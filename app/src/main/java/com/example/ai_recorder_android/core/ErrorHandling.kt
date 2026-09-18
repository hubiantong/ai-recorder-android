package com.example.ai_recorder_android.core

import kotlinx.coroutines.CancellationException

/**
 * 统一结果包装（Metis M5.14：统一错误处理）。
 *
 * UI 层（如 FileUploadScreen）用 [Result] 替代裸异常传播：
 * - 成功路径携带数据 [Success]；
 * - 失败路径携带用户可见消息 [Error]（连接器异常族已自带中文消息，直接透传 message）。
 *
 * 配合 [runCatchingResult] 使用：suspend 块内异常自动包装为 [Error]，
 * 协程取消（[CancellationException]）原样重抛——取消不是错误，不吞不包装
 * （与 TranscriptionPipeline 的取消语义一致）。
 */
sealed class Result<out T> {

    /** 成功：携带结果数据。 */
    data class Success<T>(val data: T) : Result<T>()

    /** 失败：携带用户可见错误消息。 */
    data class Error(val message: String) : Result<Nothing>()
}

/**
 * 执行 suspend 块并包装为 [Result]。
 *
 * @throws CancellationException 协程取消原样传播（不包装为 Error）
 */
suspend fun <T> runCatchingResult(block: suspend () -> T): Result<T> = try {
    Result.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.Error(e.message ?: "未知错误")
}