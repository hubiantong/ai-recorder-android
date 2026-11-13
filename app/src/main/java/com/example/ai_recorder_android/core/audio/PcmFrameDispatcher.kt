package com.example.ai_recorder_android.core.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PCM 帧有界分发器：把录音读循环线程的帧产出与消费端（实时识别网络投递）解耦。
 *
 * 线程模型：
 * - [offer] 由引擎读循环线程调用，只做一次非阻塞 `trySend`，**不阻塞、不抛异常**；
 *   消费端（WebSocket 重采样/发送）再慢也不会反压录音线程导致 AudioRecord 溢出丢音；
 * - 消费协程在 [dispatcher] 上**串行**取帧调用 [consumer]，保证帧序；
 * - 队列满时丢弃最旧帧（实时转写宁可丢旧音频，也要保持字幕与当前语音对齐）。
 *
 * 生命周期：`coordinator.pcmListener = dispatcher::offer` 开始投递；
 * 停止时先摘监听再 [closeAndDrain]（把已入队帧投递完），最后结束识别会话。
 */
class PcmFrameDispatcher(
    private val consumer: suspend (ByteArray) -> Unit,
    capacity: Int = DEFAULT_CAPACITY,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onConsumerError: (Throwable) -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val channel = Channel<ByteArray>(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val consumerJob = scope.launch {
        for (pcm in channel) {
            try {
                consumer(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 单帧投递失败不终止循环：实时识别降级（停止后回退文件转写），录音不受影响
                onConsumerError(e)
            }
        }
    }

    /** 引擎线程调用：非阻塞投递一帧 16kHz/16-bit/mono PCM。关闭后调用为空操作。 */
    fun offer(pcm: ByteArray) {
        channel.trySend(pcm)
    }

    /** 等待已入队帧投递完（最多 [timeoutMs]），随后释放消费协程。 */
    suspend fun closeAndDrain(timeoutMs: Long = DRAIN_TIMEOUT_MS) {
        channel.close()
        withTimeoutOrNull(timeoutMs) { consumerJob.join() }
        scope.cancel()
    }

    /** 立即关闭并丢弃未投递帧（异常收尾路径用，不等待网络）。 */
    fun close() {
        channel.close()
        scope.cancel()
    }

    companion object {
        /** 队列容量：帧长 100ms，50 帧 ≈ 5 秒网络抖动容忍。 */
        const val DEFAULT_CAPACITY = 50

        /** 停止时排空超时：网络拥塞时不拖住停止流程。 */
        const val DRAIN_TIMEOUT_MS = 1000L
    }
}
