package com.example.ai_recorder_android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Result 包装纯 JVM 单测（不依赖 Android，本机可写不可跑，其他机器验证）。
 * 核心断言：成功携带数据 / 异常包装为 Error / 取消原样传播不包装。
 */
class ErrorHandlingTest {

    @Test
    fun runCatchingResult_success_returnsData() = runBlocking {
        val result = runCatchingResult { 42 }
        assertTrue(result is Result.Success)
        assertEquals(42, (result as Result.Success).data)
    }

    @Test
    fun runCatchingResult_exception_wrapsMessage() = runBlocking {
        val result = runCatchingResult<Int> { throw IllegalStateException("boom") }
        assertTrue(result is Result.Error)
        assertEquals("boom", (result as Result.Error).message)
    }

    @Test
    fun runCatchingResult_exceptionWithoutMessage_usesFallback() = runBlocking {
        val result = runCatchingResult<Int> { throw IllegalStateException() }
        assertTrue(result is Result.Error)
        assertEquals("未知错误", (result as Result.Error).message)
    }

    @Test
    fun runCatchingResult_cancellation_rethrowsNotWrapped() = runBlocking {
        try {
            runCatchingResult<Int> { throw CancellationException("cancelled") }
            fail("CancellationException 应原样传播")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun result_isExhaustiveWhen() {
        val result: Result<String> = Result.Success("ok")
        val label = when (result) {
            is Result.Success -> "success:${result.data}"
            is Result.Error -> "error:${result.message}"
        }
        assertEquals("success:ok", label)
    }
}