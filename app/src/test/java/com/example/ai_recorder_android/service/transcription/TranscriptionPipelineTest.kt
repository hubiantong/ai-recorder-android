package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.asr.AsrApiException
import com.example.ai_recorder_android.cloud.asr.DashScopeRealtimeCallback
import com.example.ai_recorder_android.core.audio.WavFileWriter
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TranscriptionPipeline 纯 JVM 单测（本机不运行 gradlew，代码必须完整）：
 * 1. 管线映射表（tier + vadEnabled → PipelineMode）
 * 2. 整文件管线（落库 + 无 segments 单 chunk 兜底）
 * 3. VAD 分段管线（逐段落库 / 无段回退 / 全部段失败回退 / 部分段失败继续）
 * 4. 取消语义（CancellationException 传播且不标记 FAILED）
 * 5. 实时流式管线（句子落库 / 无句仅 COMPLETED / 出错 aborted 不落库）
 */
class TranscriptionPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FakeTranscriptionStore

    @Before
    fun setUp() {
        store = FakeTranscriptionStore()
    }

    // =====================================================================================
    // 1. 管线映射表（tier + vadEnabled → PipelineMode）
    // =====================================================================================

    @Test
    fun `realtime tier 恒为 REALTIME 与 VAD 开关无关`() {
        assertEquals(PipelineMode.REALTIME, selectPipeline("realtime", vadEnabled = true))
        assertEquals(PipelineMode.REALTIME, selectPipeline("realtime", vadEnabled = false))
    }

    @Test
    fun `free pro_lite pro_pro 且 VAD 开启 为 VAD_SEGMENTED`() {
        for (tier in listOf("free", "pro_lite", "pro_pro")) {
            assertEquals("tier=$tier", PipelineMode.VAD_SEGMENTED, selectPipeline(tier, vadEnabled = true))
        }
    }

    @Test
    fun `free pro_lite pro_pro 且 VAD 关闭 为 WHOLE_FILE`() {
        for (tier in listOf("free", "pro_lite", "pro_pro")) {
            assertEquals("tier=$tier", PipelineMode.WHOLE_FILE, selectPipeline(tier, vadEnabled = false))
        }
    }

    @Test
    fun `未知 tier 回退 WHOLE_FILE`() {
        assertEquals(PipelineMode.WHOLE_FILE, selectPipeline("unknown_tier", vadEnabled = true))
        assertEquals(PipelineMode.WHOLE_FILE, selectPipeline("", vadEnabled = true))
    }

    // =====================================================================================
    // 2. 整文件管线
    // =====================================================================================

    @Test
    fun `整文件转写落库并置 COMPLETED`() = runBlocking {
        val transcriber = FakeTranscriber()
        val pipeline = pipeline(transcriber)
        val wav = loudWav(seconds = 2.0)

        val outcome = pipeline.transcribe(recordingId = 1L, wavFile = wav, tier = "free", vadEnabled = false)

        assertEquals(PipelineMode.WHOLE_FILE, outcome.mode)
        assertEquals("测试文本", outcome.text)
        assertEquals(1, outcome.chunkCount)
        assertFalse(outcome.fallbackUsed)

        val call = store.saveTranscriptionCalls.single()
        assertEquals(1L, call.recordingId)
        assertEquals("测试文本", call.text)
        assertEquals(2.0, call.durationSeconds!!, 1e-9)
        assertEquals(1, call.chunks.size)
        assertEquals("测试文本", call.chunks[0].content)
        assertEquals(0.0, call.chunks[0].startTime!!, 1e-9)
        assertEquals(1.0, call.chunks[0].endTime!!, 1e-9)
        assertTrue("整文件应直接转写原文件", transcriber.calls.single().name == wav.name)
    }

    @Test
    fun `整文件转写无 segments 时单 chunk 兜底`() = runBlocking {
        val transcriber = FakeTranscriber(
            result = TranscriptionResult(text = "全文无分段", segments = emptyList(), durationSeconds = 1.0),
        )
        val pipeline = pipeline(transcriber)

        pipeline.transcribe(recordingId = 1L, wavFile = loudWav(1.0), tier = "free", vadEnabled = false)

        val call = store.saveTranscriptionCalls.single()
        assertEquals(1, call.chunks.size)
        assertEquals("全文无分段", call.chunks[0].content)
        assertNull("无分段时时间戳留空", call.chunks[0].startTime)
        assertNull(call.chunks[0].endTime)
    }

    @Test
    fun `整文件转写失败标记 FAILED 并抛异常`() = runBlocking {
        val transcriber = FakeTranscriber(failAll = true)
        val pipeline = pipeline(transcriber)

        val ex = assertThrows(TranscriptionPipelineException::class.java) {
            runBlocking { pipeline.transcribe(1L, loudWav(1.0), "free", vadEnabled = false) }
        }
        assertTrue(ex.message!!.contains("mock 全失败"))
        assertEquals(1L to "mock 全失败", store.recordingFailedCalls.single())
    }

    // =====================================================================================
    // 3. VAD 分段管线
    // =====================================================================================

    @Test
    fun `VAD 分段逐段转写并落库`() = runBlocking {
        val transcriber = FakeTranscriber()
        val pipeline = pipeline(transcriber)
        // 2s 语音 + 2s 静音 + 2s 语音 → VAD 产出 2 段
        val wav = speechSilenceSpeechWav()

        val outcome = pipeline.transcribe(recordingId = 1L, wavFile = wav, tier = "free", vadEnabled = true)

        assertEquals(PipelineMode.VAD_SEGMENTED, outcome.mode)
        assertFalse(outcome.fallbackUsed)
        assertEquals("测试文本测试文本", outcome.text)
        assertEquals(2, outcome.chunkCount)

        assertEquals("两段各落库一次", 2, store.saveSegmentCalls.size)
        assertEquals(0, store.saveSegmentCalls[0].segmentIndex)
        assertEquals(1, store.saveSegmentCalls[1].segmentIndex)
        assertEquals("段文件应写入临时目录", 2, transcriber.calls.size)
        assertTrue(transcriber.calls.all { it.name.contains("_seg") })
        assertEquals("收尾置 COMPLETED", 1L to 6.0, store.recordingCompletedCalls.single())
        assertTrue("分段成功不应走整文件落库", store.saveTranscriptionCalls.isEmpty())
    }

    @Test
    fun `VAD 未检测到语音段回退整文件`() = runBlocking {
        val transcriber = FakeTranscriber()
        val pipeline = pipeline(transcriber)
        val wav = silentWav(seconds = 3.0)

        val outcome = pipeline.transcribe(recordingId = 1L, wavFile = wav, tier = "free", vadEnabled = true)

        assertTrue("应回退整文件", outcome.fallbackUsed)
        assertEquals(PipelineMode.WHOLE_FILE, outcome.mode)
        assertEquals("回退后走 saveTranscription", 1, store.saveTranscriptionCalls.size)
        assertTrue("整文件只转写一次原文件", transcriber.calls.single().name == wav.name)
        assertTrue(store.saveSegmentCalls.isEmpty())
    }

    @Test
    fun `全部段失败回退整文件转写`() = runBlocking {
        // 段文件（rec_*_seg*.wav）全部失败，原文件成功 → 触发回退
        val transcriber = FakeTranscriber(failSegmentFiles = true)
        val pipeline = pipeline(transcriber)
        val wav = loudWav(seconds = 2.0) // 1 段

        val outcome = pipeline.transcribe(recordingId = 1L, wavFile = wav, tier = "free", vadEnabled = true)

        assertTrue("全部段失败应回退", outcome.fallbackUsed)
        assertEquals(PipelineMode.WHOLE_FILE, outcome.mode)
        assertEquals("段失败标记 FAILED", 1, store.segmentFailedCalls.size)
        assertEquals(0, store.segmentFailedCalls[0].second)
        assertEquals("回退后整文件落库", 1, store.saveTranscriptionCalls.size)
        assertEquals("段转写 1 次 + 回退整文件 1 次", 2, transcriber.calls.size)
        assertTrue(store.saveSegmentCalls.isEmpty())
    }

    @Test
    fun `部分段失败继续其余段不回退`() = runBlocking {
        val transcriber = FakeTranscriber(failSegmentIndices = setOf(0))
        val pipeline = pipeline(transcriber)
        val wav = speechSilenceSpeechWav() // 2 段

        val outcome = pipeline.transcribe(recordingId = 1L, wavFile = wav, tier = "free", vadEnabled = true)

        assertEquals(PipelineMode.VAD_SEGMENTED, outcome.mode)
        assertFalse("有成功段不应回退", outcome.fallbackUsed)
        assertEquals("段 0 失败标记", listOf(1L to 0), store.segmentFailedCalls)
        assertEquals("段 1 成功落库", 1, store.saveSegmentCalls.size)
        assertEquals(1, store.saveSegmentCalls[0].segmentIndex)
        assertEquals("收尾置 COMPLETED", 1, store.recordingCompletedCalls.size)
        assertTrue("不应走整文件落库", store.saveTranscriptionCalls.isEmpty())
    }

    // =====================================================================================
    // 4. 取消语义
    // =====================================================================================

    @Test
    fun `取消异常原样传播且不标记 FAILED`() = runBlocking {
        val transcriber = object : FileTranscriber {
            override suspend fun transcribe(file: File, language: String?): TranscriptionResult {
                throw CancellationException("cancelled")
            }
        }
        val pipeline = pipeline(transcriber)

        try {
            pipeline.transcribe(1L, loudWav(1.0), "free", vadEnabled = false)
            throw AssertionError("应抛出 CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        assertTrue("取消不应标记 FAILED", store.recordingFailedCalls.isEmpty())
    }

    @Test
    fun `VAD 段转写取消异常传播且不标记段 FAILED`() = runBlocking {
        val transcriber = object : FileTranscriber {
            override suspend fun transcribe(file: File, language: String?): TranscriptionResult {
                throw CancellationException("cancelled")
            }
        }
        val pipeline = pipeline(transcriber)

        try {
            pipeline.transcribe(1L, loudWav(2.0), "free", vadEnabled = true)
            throw AssertionError("应抛出 CancellationException")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        assertTrue("取消不应标记段 FAILED", store.segmentFailedCalls.isEmpty())
        assertTrue(store.recordingFailedCalls.isEmpty())
    }

    // =====================================================================================
    // 5. 实时流式管线
    // =====================================================================================

    @Test
    fun `realtime 收集句子停止后落库`() = runBlocking {
        val fakeConnector = FakeRealtimeConnector()
        val pipeline = realtimePipeline(fakeConnector)
        var partials = mutableListOf<String>()
        var sentences = mutableListOf<Triple<String, Int?, Int?>>()

        pipeline.start(1L, RealtimeListeners(
            onPartial = { partials += it },
            onSentence = { t, b, e -> sentences += Triple(t, b, e) },
        ))
        fakeConnector.callback!!.onPartial("你好")
        fakeConnector.callback!!.onSentence("你好", 0, 500)
        fakeConnector.callback!!.onSentence("世界", 500, 1000)
        pipeline.feedPcm16k(ByteArray(3200))

        val result = pipeline.stop()

        assertFalse(result.aborted)
        assertEquals("你好世界", result.text)
        assertEquals(2, result.sentenceCount)
        assertEquals(listOf("你好"), partials)
        assertEquals(2, sentences.size)

        val call = store.saveTranscriptionCalls.single()
        assertEquals(1L, call.recordingId)
        assertEquals(2, call.chunks.size)
        assertEquals("你好", call.chunks[0].content)
        assertEquals(0.0, call.chunks[0].startTime!!, 1e-9)
        assertEquals(0.5, call.chunks[0].endTime!!, 1e-9)
        assertEquals(0.5, call.chunks[1].startTime!!, 1e-9)
        assertEquals(1.0, call.chunks[1].endTime!!, 1e-9)
        assertEquals(1, fakeConnector.connectCalls)
        assertEquals(1, fakeConnector.sendCalls)
        assertEquals(1, fakeConnector.finishCalls)
        assertEquals(1, fakeConnector.closeCalls)
    }

    @Test
    fun `realtime 无最终句时仅标记 COMPLETED`() = runBlocking {
        val fakeConnector = FakeRealtimeConnector()
        val pipeline = realtimePipeline(fakeConnector)

        pipeline.start(1L)
        pipeline.feedPcm16k(ByteArray(1600))
        val result = pipeline.stop()

        assertFalse(result.aborted)
        assertEquals("", result.text)
        assertEquals(0, result.sentenceCount)
        assertTrue("无句不落 chunks", store.saveTranscriptionCalls.isEmpty())
        assertEquals("仅标记 COMPLETED", 1L to null, store.recordingCompletedCalls.single())
    }

    @Test
    fun `realtime SDK 报错时 aborted 不落库`() = runBlocking {
        val fakeConnector = FakeRealtimeConnector()
        val pipeline = realtimePipeline(fakeConnector)
        var lastError: Throwable? = null

        pipeline.start(1L, RealtimeListeners(onError = { lastError = it }))
        fakeConnector.callback!!.onSentence("已识别", 0, 100)
        fakeConnector.callback!!.onError(AsrApiException(500, "task-failed"))

        val result = pipeline.stop()

        assertTrue("SDK 报错应 aborted", result.aborted)
        assertEquals("aborted 不落库", 0, store.saveTranscriptionCalls.size)
        assertEquals("aborted 不标记 COMPLETED", 0, store.recordingCompletedCalls.size)
        assertTrue(lastError is AsrApiException)
        assertEquals("aborted 仍应关闭连接", 1, fakeConnector.closeCalls)
    }

    @Test
    fun `realtime 重复 start 抛异常`() {
        val pipeline = realtimePipeline(FakeRealtimeConnector())
        pipeline.start(1L)

        assertThrows(IllegalStateException::class.java) {
            pipeline.start(1L)
        }
    }

    // =====================================================================================
    // 辅助
    // =====================================================================================

    private fun pipeline(transcriber: FileTranscriber): TranscriptionPipeline = TranscriptionPipeline(
        store = store,
        transcriberFactory = FileTranscriberFactory { transcriber },
        tempDir = tmp.newFolder(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun realtimePipeline(connector: FakeRealtimeConnector): RealtimeTranscriptionPipeline =
        RealtimeTranscriptionPipeline(
            connectorFactory = RealtimeConnectorFactory { callback ->
                connector.callback = callback
                connector
            },
            store = store,
            closeTimeoutMs = 100,
            ioDispatcher = Dispatchers.Unconfined,
        )

    /** 全响度 WAV（RMS ≈ 0.305 > 阈值 0.02），VAD 产出 1 段。 */
    private fun loudWav(seconds: Double): File {
        val sampleCount = (seconds * WavPcm.SAMPLE_RATE).toInt()
        return writeWav(ShortArray(sampleCount) { 10000 })
    }

    /** 全静音 WAV，VAD 不产出段。 */
    private fun silentWav(seconds: Double): File {
        val sampleCount = (seconds * WavPcm.SAMPLE_RATE).toInt()
        return writeWav(ShortArray(sampleCount))
    }

    /** 2s 语音 + 2s 静音 + 2s 语音 → VAD 产出 2 段（[0,3.5] 与 [3.5,6.0]）。 */
    private fun speechSilenceSpeechWav(): File {
        val speech = ShortArray(2 * WavPcm.SAMPLE_RATE) { 10000 }
        val silence = ShortArray(2 * WavPcm.SAMPLE_RATE)
        return writeWav(speech + silence + speech)
    }

    private fun writeWav(samples: ShortArray): File {
        val file = tmp.newFile("wav_${System.nanoTime()}.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, WavFileWriter.DEFAULT_SAMPLE_RATE, 1, 16)
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            raf.write(bytes)
            WavFileWriter.finalize(raf, bytes.size)
        }
        return file
    }

    // ---------- 假实现 ----------

    private class FakeTranscriber(
        var failAll: Boolean = false,
        var failSegmentFiles: Boolean = false,
        var failSegmentIndices: Set<Int> = emptySet(),
        var result: TranscriptionResult = TranscriptionResult(
            text = "测试文本",
            segments = listOf(TranscriptionSegment("测试文本", 0.0, 1.0)),
            durationSeconds = 1.0,
        ),
    ) : FileTranscriber {
        val calls = mutableListOf<File>()

        override suspend fun transcribe(file: File, language: String?): TranscriptionResult {
            calls += file
            if (failAll) throw AsrApiException(500, "mock 全失败")
            val segIdx = file.name.substringAfter("_seg", "").substringBefore(".wav").toIntOrNull()
            if (failSegmentFiles && segIdx != null) throw AsrApiException(500, "mock 段失败")
            if (segIdx != null && segIdx in failSegmentIndices) throw AsrApiException(500, "mock 段 $segIdx 失败")
            return result
        }
    }

    private class FakeRealtimeConnector : RealtimeAsrConnector {
        var callback: DashScopeRealtimeCallback? = null
        var connectCalls = 0
        var sendCalls = 0
        var finishCalls = 0
        var closeCalls = 0

        override fun connect() {
            connectCalls++
        }

        override fun sendPcm16k(pcm16k: ByteArray) {
            sendCalls++
        }

        override fun flush() = Unit

        override fun finish() {
            finishCalls++
            // 模拟服务端 task-finished → onClosed（让管线 stop 的等待立即返回）
            callback?.onClosed()
        }

        override fun close() {
            closeCalls++
        }
    }

    private class FakeTranscriptionStore : TranscriptionStore {
        val saveTranscriptionCalls = mutableListOf<SaveCall>()
        val saveSegmentCalls = mutableListOf<SegmentSaveCall>()
        val segmentFailedCalls = mutableListOf<Pair<Long, Int>>()
        val recordingFailedCalls = mutableListOf<Pair<Long, String?>>()
        val recordingCompletedCalls = mutableListOf<Pair<Long, Double?>>()

        data class SaveCall(
            val recordingId: Long,
            val text: String,
            val chunks: List<TranscriptChunkEntity>,
            val durationSeconds: Double?,
        )

        data class SegmentSaveCall(
            val recordingId: Long,
            val segmentIndex: Int,
            val chunks: List<TranscriptChunkEntity>,
        )

        override suspend fun saveTranscription(
            recordingId: Long,
            transcriptionText: String,
            chunks: List<TranscriptChunkEntity>,
            durationSeconds: Double?,
        ): Int {
            saveTranscriptionCalls += SaveCall(recordingId, transcriptionText, chunks, durationSeconds)
            return chunks.size
        }

        override suspend fun saveSegmentTranscription(
            recordingId: Long,
            segmentIndex: Int,
            chunks: List<TranscriptChunkEntity>,
        ): Int {
            saveSegmentCalls += SegmentSaveCall(recordingId, segmentIndex, chunks)
            return chunks.size
        }

        override suspend fun markSegmentFailed(recordingId: Long, segmentIndex: Int, error: String?) {
            segmentFailedCalls += recordingId to segmentIndex
        }

        override suspend fun markRecordingFailed(recordingId: Long, error: String?) {
            recordingFailedCalls += recordingId to error
        }

        override suspend fun markRecordingCompleted(recordingId: Long, durationSeconds: Double?) {
            recordingCompletedCalls += recordingId to durationSeconds
        }

        override suspend fun saveSummary(recordingId: Long, summary: String) = Unit

        override suspend fun saveTitle(recordingId: Long, title: String) = Unit
    }
}