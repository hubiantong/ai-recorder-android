package com.example.ai_recorder_android.core.audio

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PcmFrameDispatcher] 单测：引擎线程非阻塞投递 + 串行消费 + 有界丢帧 + 异常隔离。
 */
class PcmFrameDispatcherTest {

    @Test
    fun `offer 非阻塞且按入队顺序串行消费`() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val allReceived = CountDownLatch(3)
        val dispatcher = PcmFrameDispatcher(consumer = { pcm ->
            received += pcm[0].toInt()
            allReceived.countDown()
        })

        repeat(3) { dispatcher.offer(byteArrayOf(it.toByte())) }

        assertTrue("应在 2s 内消费完 3 帧", allReceived.await(2, TimeUnit.SECONDS))
        dispatcher.closeAndDrain()
        assertEquals(listOf(0, 1, 2), received.toList())
    }

    @Test
    fun `单帧消费异常不终止后续帧投递`() = runBlocking {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val secondConsumed = CountDownLatch(1)
        val dispatcher = PcmFrameDispatcher(
            consumer = { pcm ->
                if (pcm[0] == 1.toByte()) throw IllegalStateException("模拟网络投递失败")
                secondConsumed.countDown()
            },
            onConsumerError = { errors += it },
        )

        dispatcher.offer(byteArrayOf(1))
        dispatcher.offer(byteArrayOf(2))

        assertTrue("异常帧之后的帧应继续消费", secondConsumed.await(2, TimeUnit.SECONDS))
        dispatcher.closeAndDrain()
        assertEquals(1, errors.size)
    }

    @Test
    fun `队列满时丢弃最旧帧并保持后续帧实时性`() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val firstProcessing = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val dispatcher = PcmFrameDispatcher(
            consumer = { pcm ->
                received += pcm[0].toInt()
                if (pcm[0] == 1.toByte()) {
                    firstProcessing.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
            },
            capacity = 1,
        )

        dispatcher.offer(byteArrayOf(1))
        assertTrue("消费协程应已开始处理首帧", firstProcessing.await(2, TimeUnit.SECONDS))
        dispatcher.offer(byteArrayOf(2)) // 入队（容量 1）
        dispatcher.offer(byteArrayOf(3)) // 队列满 → 丢弃最旧的 2
        releaseFirst.countDown()
        dispatcher.closeAndDrain()

        assertEquals("应保留最新帧 3，丢弃旧帧 2", listOf(1, 3), received.toList())
    }

    @Test
    fun `关闭后 offer 静默丢弃不抛异常`() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = PcmFrameDispatcher(consumer = { received += it[0].toInt() })

        dispatcher.closeAndDrain()
        dispatcher.offer(byteArrayOf(9)) // 关闭后投递：静默丢弃

        assertEquals(emptyList<Int>(), received.toList())
    }

    @Test
    fun `closeAndDrain 投递完已入队帧`() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = PcmFrameDispatcher(consumer = { received += it[0].toInt() })

        repeat(5) { dispatcher.offer(byteArrayOf(it.toByte())) }
        dispatcher.closeAndDrain()

        assertEquals(listOf(0, 1, 2, 3, 4), received.toList())
    }
}
