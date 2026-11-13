package com.example.ai_recorder_android.service

import com.example.ai_recorder_android.core.audio.RecordingAudioEngine
import com.example.ai_recorder_android.core.audio.RecordingAudioEngineFactory
import com.example.ai_recorder_android.core.audio.RecordingEngineEvents
import com.example.ai_recorder_android.core.audio.WavFileWriter
import com.example.ai_recorder_android.data.db.AttachmentSource
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.AudioSource
import com.example.ai_recorder_android.data.db.DescriptionStatus
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.repo.RecordingStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RecordingCoordinator 编排层单测（纯 JVM：假存储 + 假引擎 + 假时钟，Unconfined 调度器确定性执行）：
 * 状态机流转、暂停时长累计、附件时间戳对齐（Metis P6.8）、stop 无活动录音抛异常、
 * 引擎启动失败回滚、段落库、删除会话守卫、finalize 委托。
 */
class RecordingCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FakeRecordingStore
    private lateinit var factory: FakeEngineFactory
    private lateinit var monotonicClock: FakeClock
    private lateinit var wallClock: FakeClock
    private lateinit var coordinator: RecordingCoordinator

    @Before
    fun setUp() {
        store = FakeRecordingStore()
        factory = FakeEngineFactory()
        monotonicClock = FakeClock(1_000_000L)
        wallClock = FakeClock(1_752_000_000_000L)
        coordinator = RecordingCoordinator(
            repository = store,
            engineFactory = factory,
            monotonicClock = monotonicClock,
            wallClock = wallClock,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    // ---------- 状态机：idle → recording → paused → recording → completed ----------

    @Test
    fun startRecording_createsRowStartsEngineAndTransitionsToRecording() = runBlocking {
        val rec = coordinator.startRecording(tmp.newFolder(), "测试录音", AudioSource.MIC, true)

        assertEquals("应创建 PENDING 行", 1, store.created.size)
        assertEquals(listOf(rec.id), store.started)
        assertEquals(1, factory.engine.startCalls)
        assertEquals(RecordingPhase.RECORDING, coordinator.sessionState.value.phase)
        assertEquals(rec.id, coordinator.currentRecordingId)
        assertTrue(factory.engine.isRecording)
    }

    @Test
    fun startRecording_whileActive_throwsIllegalState() = runBlocking<Unit> {
        coordinator.startRecording(tmp.newFolder())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.startRecording(tmp.newFolder()) }
        }
    }

    @Test
    fun startRecording_engineFailure_rollsBackRowAndRethrows() = runBlocking {
        factory.failStart = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.startRecording(tmp.newFolder()) }
        }

        assertEquals("引擎启动失败应回滚 DB 行", listOf(1L), store.deleted)
        assertEquals("启动失败应进入 ERROR（UI 据此提示并允许重试）", RecordingPhase.ERROR, coordinator.sessionState.value.phase)
        assertNotNull("失败原因应回推 UI", coordinator.sessionState.value.errorMessage)
        assertNull(coordinator.currentRecordingId)
    }

    @Test
    fun pauseResume_transitionsAndAccumulatesPause() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        monotonicClock.advance(10_000L)

        coordinator.pause()
        assertEquals(RecordingPhase.PAUSED, coordinator.sessionState.value.phase)
        monotonicClock.advance(5_000L)

        coordinator.resume()
        assertEquals(RecordingPhase.RECORDING, coordinator.sessionState.value.phase)
        assertEquals("暂停 5s 应累计", 5_000L, coordinator.pausedDurationMs)
        // 已过时长 = 15s 单调 - 5s 暂停
        assertEquals(10_000L, coordinator.elapsedMs())
    }

    @Test
    fun pause_whenIdleOrAlreadyPaused_isNoop() = runBlocking {
        coordinator.pause()
        assertEquals(RecordingPhase.IDLE, coordinator.sessionState.value.phase)

        coordinator.startRecording(tmp.newFolder())
        coordinator.pause()
        val pausedDuration = coordinator.pausedDurationMs
        coordinator.pause()
        coordinator.resume()
        assertEquals("重复暂停应忽略", pausedDuration, coordinator.pausedDurationMs)
    }

    @Test
    fun stop_whenNoActiveRecording_throwsIllegalState() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.stop() }
        }
    }

    @Test
    fun stop_singleSegment_persistsSegmentAndTriggersUnifiedMerge() = runBlocking {
        val rec = coordinator.startRecording(tmp.newFolder(), "a", AudioSource.MIC, false)
        // 模拟引擎关闭段 0：1 秒音频（16kHz/16-bit/mono → 32000 字节 PCM + 44 头）
        val segmentFile = wavFile(32_000)
        factory.engine.simulateSegmentClosed(0, segmentFile)

        val duration = coordinator.stop()

        assertEquals("时长应从 PCM 载荷计算", 1.0, duration, 1e-9)
        assertEquals(RecordingPhase.COMPLETED, coordinator.sessionState.value.phase)
        assertNull("stop 后无活动录音", coordinator.currentRecordingId)
        assertEquals(1, store.stopped.size)
        assertEquals(rec.id, store.stopped[0])

        val seg = store.segments.single()
        assertEquals("单段也必须落库", 0, seg.index)
        assertEquals(0.0, seg.startTime, 1e-9)
        assertEquals(1.0, seg.endTime, 1e-9)
        assertEquals(1.0, seg.duration, 1e-9)

        // 单段同样走统一合并入口（不再直接 store 原始段字节）
        assertEquals("单段场景应触发合并", 1, store.finalized.size)
        assertEquals(rec.id to 1.0, store.finalized.single())
        assertTrue("单段不应再走 store()", store.stored.isEmpty())
    }

    @Test
    fun stop_multipleSegments_accumulatesAudioTimeline() = runBlocking {
        val rec = coordinator.startRecording(tmp.newFolder())
        val seg0 = wavFile(16_000) // 0.5s
        val seg1 = wavFile(32_000) // 1.0s
        factory.engine.simulateSegmentClosed(0, seg0)
        factory.engine.simulateSegmentClosed(1, seg1)

        val duration = coordinator.stop()

        assertEquals(1.5, duration, 1e-9)
        assertEquals("全部段必须落库", 2, store.segments.size)
        val s0 = store.segments[0]
        val s1 = store.segments[1]
        assertEquals(0.0, s0.startTime, 1e-9)
        assertEquals(0.5, s0.endTime, 1e-9)
        assertEquals(0.5, s0.duration, 1e-9)
        assertEquals("段 1 起点应为段 0 终点", 0.5, s1.startTime, 1e-9)
        assertEquals(1.5, s1.endTime, 1e-9)
        assertEquals(1.0, s1.duration, 1e-9)

        assertEquals("多段场景应触发合并", 1, store.finalized.size)
        assertEquals(rec.id to 1.5, store.finalized.single())
        assertTrue("多段不再跳过整段 WAV", store.stored.isEmpty())
    }

    @Test
    fun stop_mergeFailure_keepsSessionCompletedAndSupportsRetry() = runBlocking {
        val errors = mutableListOf<Throwable>()
        coordinator.errorListener = { errors += it }
        val rec = coordinator.startRecording(tmp.newFolder())
        factory.engine.simulateSegmentClosed(0, wavFile(16_000))
        factory.engine.simulateSegmentClosed(1, wavFile(32_000))
        store.failFinalize = true

        val duration = coordinator.stop()

        assertEquals("合并失败不改变时长计算", 1.5, duration, 1e-9)
        assertEquals("合并失败仍需置 COMPLETED", RecordingPhase.COMPLETED, coordinator.sessionState.value.phase)
        assertEquals("录音状态应已落库", listOf(rec.id), store.stopped)
        assertEquals("全部段数据必须已落库", 2, store.segments.size)
        assertTrue("失败后不得删除/覆盖任何段数据", store.stored.isEmpty())
        assertEquals("合并失败应上报 errorListener", 1, errors.size)
        assertTrue(errors[0] is IllegalStateException)

        // 重试入口：仍可对同一录音重新触发合并
        store.failFinalize = false
        val locator = coordinator.finalizeSegmented(rec.id, duration)

        assertNotNull("重试合并应成功", locator)
        assertEquals(2, store.finalized.size)
        assertEquals(rec.id to 1.5, store.finalized.last())
    }

    @Test
    fun stop_afterPause_timestampAlignmentAndDurationExcludePause() = runBlocking {
        val rec = coordinator.startRecording(tmp.newFolder())
        monotonicClock.advance(10_000L)
        wallClock.advance(10_000L) // 时钟未调整：墙钟与单调同步推进（漂移 0）
        coordinator.pause()
        monotonicClock.advance(10_000L)
        wallClock.advance(10_000L) // 暂停 10s
        coordinator.resume()
        factory.engine.simulateSegmentClosed(0, wavFile(32_000))

        // 拍照时刻：resume 后再走 5s（双钟同步推进），拍照墙钟 = 开始后 25s
        monotonicClock.advance(5_000L)
        wallClock.advance(5_000L)
        val photoWallMs = wallClock.nowMs()

        assertEquals("25s 墙钟 - 10s 暂停 = 15s", 15.0, coordinator.alignedTimestampSeconds(photoWallMs), 1e-9)

        val duration = coordinator.stop()
        assertEquals("时长不含暂停", 1.0, duration, 1e-9)
        assertEquals(rec.id, store.segments.single().recordingId)
    }

    // ---------- 附件时间戳对齐（Metis P6.8） ----------

    @Test
    fun addAttachment_alignsTimestampAndDelegatesToStore() = runBlocking {
        val rec = coordinator.startRecording(tmp.newFolder())
        monotonicClock.advance(10_000L)
        wallClock.advance(10_000L) // 时钟未调整：双钟同步
        coordinator.pause()
        monotonicClock.advance(10_000L)
        wallClock.advance(10_000L) // 暂停 10s
        coordinator.resume()
        monotonicClock.advance(15_000L)
        wallClock.advance(15_000L) // resume 后 15s，拍照墙钟 = 开始后 35s

        val att = coordinator.addAttachment(
            photoWallClockMs = wallClock.nowMs(),
            fileName = "photo.jpg",
            data = byteArrayOf(1, 2, 3),
        )

        assertNotNull(att)
        val call = store.attachments.single()
        assertEquals(rec.id, call.recordingId)
        assertEquals("35s 墙钟 - 10s 暂停 = 25s", 25.0, call.timestamp, 1e-9)
    }

    @Test
    fun addAttachment_whenNoActiveRecording_throws() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.addAttachment(wallClock.nowMs(), "p.jpg", byteArrayOf(1)) }
        }
    }

    @Test
    fun alignedTimestamp_whenNotRecording_returnsZero() {
        assertEquals(0.0, coordinator.alignedTimestampSeconds(wallClock.nowMs()), 1e-9)
    }

    @Test
    fun alignedTimestamp_duringPause_includesInProgressPause() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        monotonicClock.advance(5_000L)
        coordinator.pause()
        monotonicClock.advance(3_000L) // 暂停进行中
        wallClock.advance(8_000L)

        assertEquals("8s 墙钟 - 3s 进行中暂停 = 5s", 5.0, coordinator.alignedTimestampSeconds(wallClock.nowMs()), 1e-9)
    }

    // ---------- 删除 / finalize ----------

    @Test
    fun delete_activeRecordingSession_throws() = runBlocking {
        val rec = coordinator.startRecording(tmp.newFolder())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.delete(rec.id) }
        }
        assertTrue("活动会话禁止删除", store.deleted.isEmpty())
    }

    @Test
    fun delete_completedOrForeignRecording_delegatesToStore() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        factory.engine.simulateSegmentClosed(0, wavFile(0))
        coordinator.stop()

        coordinator.delete(999L)

        assertEquals(listOf(999L), store.deleted)
    }

    @Test
    fun finalizeSegmented_delegatesToStore() = runBlocking {
        val rec = coordinator.startRecording(tmp.newFolder())
        factory.engine.simulateSegmentClosed(0, wavFile(0))
        coordinator.stop()
        assertEquals("stop 已自动触发一次合并", 1, store.finalized.size)

        val locator = coordinator.finalizeSegmented(999L, totalDuration = 3.5)

        assertNotNull(locator)
        assertEquals(2, store.finalized.size)
        assertEquals(rec.id to 0.0, store.finalized.first())
        assertEquals(999L to 3.5, store.finalized.last())
    }

    // ---------- 会话事件 ----------

    @Test
    fun engineEvents_forwardToListenersAndCollectSegments() = runBlocking {
        var lastLevel: Double? = null
        var lastError: Throwable? = null
        coordinator.levelListener = { lastLevel = it }
        coordinator.errorListener = { lastError = it }
        coordinator.startRecording(tmp.newFolder())

        factory.engine.emitLevel(0.5)
        factory.engine.emitError(RuntimeException("boom"))

        assertEquals(0.5, lastLevel!!, 1e-9)
        assertEquals("boom", lastError!!.message)
    }

    // ---------- 假实现 ----------

    private class FakeClock(var nowMs: Long) : Clock {
        override fun nowMs(): Long = nowMs
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    private class FakeRecordingStore : RecordingStore {
        val created = mutableListOf<RecordingEntity>()
        val started = mutableListOf<Long>()
        val stopped = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        val stored = mutableListOf<Triple<Long, String, ByteArray>>()
        val segments = mutableListOf<SegmentCall>()
        val attachments = mutableListOf<AttachmentCall>()
        val finalized = mutableListOf<Pair<Long, Double>>()
        var nextId = 1L

        /** true 时 finalizeSegmented 抛错（模拟合并/校验失败）。 */
        var failFinalize = false

        data class SegmentCall(
            val recordingId: Long,
            val index: Int,
            val startTime: Double,
            val endTime: Double,
            val duration: Double,
        )

        data class AttachmentCall(
            val recordingId: Long,
            val fileName: String,
            val data: ByteArray,
            val timestamp: Double,
            val source: AttachmentSource,
        )

        override suspend fun create(
            title: String?,
            audioSource: AudioSource?,
            meetingMode: Boolean,
        ): RecordingEntity {
            val entity = RecordingEntity(
                id = nextId++,
                title = title ?: "录音 测试",
                audioPath = "",
                status = RecordingStatus.PENDING,
                audioSource = audioSource,
                meetingMode = meetingMode,
                createdAt = System.currentTimeMillis(),
            )
            created += entity
            return entity
        }

        override suspend fun start(recordingId: Long): RecordingEntity? {
            started += recordingId
            return created.firstOrNull { it.id == recordingId }
        }

        override suspend fun stop(recordingId: Long, durationSeconds: Double): RecordingEntity? {
            stopped += recordingId
            return created.firstOrNull { it.id == recordingId }
        }

        override suspend fun store(recordingId: Long, fileName: String, data: ByteArray): String? {
            stored += Triple(recordingId, fileName, data)
            return "recordings/rec_$recordingId.wav"
        }

        override suspend fun delete(recordingId: Long) {
            deleted += recordingId
        }

        override suspend fun finalizeSegmented(recordingId: Long, totalDuration: Double): String? {
            finalized += recordingId to totalDuration
            if (failFinalize) throw IllegalStateException("merge failed")
            return "recordings/rec_${recordingId}_merged.wav"
        }

        override suspend fun addSegment(
            recordingId: Long,
            segmentIndex: Int,
            data: ByteArray,
            startTime: Double,
            endTime: Double,
            duration: Double,
        ): Long? {
            segments += SegmentCall(recordingId, segmentIndex, startTime, endTime, duration)
            return segments.size.toLong()
        }

        override suspend fun addAttachment(
            recordingId: Long,
            fileName: String,
            data: ByteArray,
            timestamp: Double,
            source: AttachmentSource,
            attachmentType: AttachmentType,
        ): RecordingAttachmentEntity? {
            attachments += AttachmentCall(recordingId, fileName, data, timestamp, source)
            return RecordingAttachmentEntity(
                id = 1L,
                recordingId = recordingId,
                attachmentType = attachmentType,
                storageLocator = "attachments/att_test.jpg",
                timestamp = timestamp,
                descriptionStatus = DescriptionStatus.PENDING,
                source = source,
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    private class FakeEngineFactory : RecordingAudioEngineFactory {
        val engine = FakeEngine()
        var failStart = false
        var lastEvents: RecordingEngineEvents? = null

        override fun create(recordingId: Long, outputDir: File, events: RecordingEngineEvents): RecordingAudioEngine {
            lastEvents = events
            engine.failStart = failStart
            engine.attachEvents(events)
            return engine
        }
    }

    private class FakeEngine : RecordingAudioEngine {
        var startCalls = 0
        var pauseCalls = 0
        var resumeCalls = 0
        var stopCalls = 0
        var failStart = false
        private var events: RecordingEngineEvents? = null
        private var recording = false
        private var paused = false

        fun attachEvents(events: RecordingEngineEvents) {
            this.events = events
        }

        fun simulateSegmentClosed(index: Int, file: File) {
            events?.onSegmentClosed(index, file, 0L)
        }

        fun emitLevel(rms: Double) = events?.onLevel(rms)

        fun emitError(error: Throwable) = events?.onError(error)

        override val isRecording: Boolean get() = recording
        override val isPaused: Boolean get() = paused
        override val pausedDurationMs: Long get() = 0L

        override fun elapsedMs(): Long = 0L

        override fun start() {
            startCalls++
            if (failStart) throw IllegalStateException("engine start failed")
            recording = true
        }

        override fun pause() {
            pauseCalls++
            if (recording && !paused) paused = true
        }

        override fun resume() {
            resumeCalls++
            if (paused) paused = false
        }

        override fun stop() {
            stopCalls++
            recording = false
            paused = false
        }
    }

    /** 构造合法 WAV 字节文件（44 字节头 + [payloadSize] 字节 PCM），返回文件。 */
    private fun wavFile(payloadSize: Int): File {
        val file = tmp.newFile("seg_${System.nanoTime()}.wav")
        file.writeBytes(wavBytes(payloadSize))
        return file
    }

    private fun wavBytes(payloadSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(WavFileWriter.HEADER_SIZE + payloadSize)
        WavFileWriter.writeHeader(
            out,
            WavFileWriter.DEFAULT_SAMPLE_RATE,
            WavFileWriter.DEFAULT_CHANNELS,
            WavFileWriter.DEFAULT_BITS_PER_SAMPLE,
        )
        out.write(ByteArray(payloadSize))
        return out.toByteArray()
    }
}
