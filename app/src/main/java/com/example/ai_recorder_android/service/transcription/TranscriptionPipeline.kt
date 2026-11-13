package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.asr.DashScopeFlashAsrConnector
import com.example.ai_recorder_android.cloud.asr.DashScopeRealtimeCallback
import com.example.ai_recorder_android.cloud.asr.DashScopeRealtimeConnector
import com.example.ai_recorder_android.cloud.asr.SiliconFlowAsrConnector
import com.example.ai_recorder_android.cloud.asr.VolcDoubaoFileConnector
import com.example.ai_recorder_android.core.audio.Resampler
import com.example.ai_recorder_android.core.audio.Segment
import com.example.ai_recorder_android.core.audio.VadSegmenter
import com.example.ai_recorder_android.core.audio.WavFileWriter
import com.example.ai_recorder_android.data.db.RecordingDao
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.db.SegmentDao
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// =====================================================================================
// 管线模式与选择（对齐 PC `BaseTierPage._is_vad_segmented` + `file_transcription_worker.py`）
// =====================================================================================

/** 转写管线模式。 */
enum class PipelineMode { WHOLE_FILE, VAD_SEGMENTED, REALTIME }

/**
 * 按 tier + VAD 开关选择管线（纯函数，可单测）：
 * - `realtime` 档恒走实时流式；
 * - `free/pro_lite/pro_pro` 且 VAD 开启 → VAD 分段；
 * - 其余（VAD 关闭或未知 tier）→ 整文件。
 */
fun selectPipeline(tier: String, vadEnabled: Boolean): PipelineMode = when {
    tier == "realtime" -> PipelineMode.REALTIME
    tier in listOf("free", "pro_lite", "pro_pro") && vadEnabled -> PipelineMode.VAD_SEGMENTED
    else -> PipelineMode.WHOLE_FILE
}

/** realtime 档文件回退使用的档位（同为 DashScope 文件识别，流式结果缺失时兜底）。 */
const val REALTIME_FALLBACK_TIER = "pro_pro"

/**
 * 文件转写实际使用的 tier：`realtime` 无文件接口，回退 [REALTIME_FALLBACK_TIER]。
 * 用于实时流转写失败/进程被杀后，由 WorkManager 用已持久化的 WAV 补齐转写。
 */
fun resolveFileTier(tier: String): String =
    if (tier == "realtime") REALTIME_FALLBACK_TIER else tier

/**
 * 文件管线模式：`realtime` 档作为文件回退时按 VAD 开关选择分段/整文件，
 * 避免长录音触发 fun-asr-flash 的 5 分钟上限；其余档位委托 [selectPipeline]。
 */
fun resolveFilePipeline(tier: String, vadEnabled: Boolean): PipelineMode = when {
    tier == "realtime" -> if (vadEnabled) PipelineMode.VAD_SEGMENTED else PipelineMode.WHOLE_FILE
    else -> selectPipeline(tier, vadEnabled)
}

// =====================================================================================
// VAD 参数（默认值对齐 PC `core/user_config.py` / VadSegmenter 默认值）
// =====================================================================================

/** VAD 分段参数（对齐 PC UserConfig.vad_* 四参数）。 */
data class VadParams(
    val silenceThreshold: Double = 0.02,
    val silenceDuration: Double = 1.5,
    val minSegment: Double = 1.0,
    val maxSegment: Double = 270.0,
)

// =====================================================================================
// 归一化转写结果（对齐 PC TranscriptionResponse 语义，时间戳单位秒）
// =====================================================================================

/** 归一化分段（text + 相对录音起始的秒时间戳）。 */
data class TranscriptionSegment(
    val text: String,
    val startTime: Double? = null,
    val endTime: Double? = null,
)

/** 归一化转写结果（连接器差异在此抹平）。 */
data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptionSegment> = emptyList(),
    val durationSeconds: Double? = null,
)

/** 管线执行结果。 */
data class TranscriptionOutcome(
    val mode: PipelineMode,
    val text: String,
    val chunkCount: Int,
    /** 是否发生了回退（VAD 无段 / 全部段失败 → 整文件）。 */
    val fallbackUsed: Boolean = false,
)

// =====================================================================================
// 文件转写连接器抽象（纯 JVM 可注入假实现）
// =====================================================================================

/** 文件转写连接器抽象：抹平三个连接器的返回类型差异。 */
interface FileTranscriber {
    suspend fun transcribe(file: File, language: String? = null): TranscriptionResult
}

/** 按 tier 创建 [FileTranscriber] 的工厂。 */
fun interface FileTranscriberFactory {
    fun create(tier: String): FileTranscriber
}

/** free 档：硅基流动 SiliconFlow（OpenAI Whisper 兼容端点）。 */
class SiliconFlowTranscriber(
    private val connector: SiliconFlowAsrConnector,
) : FileTranscriber {
    override suspend fun transcribe(file: File, language: String?): TranscriptionResult {
        val resp = connector.transcribe(file, language)
        val segments = resp.segments.map { TranscriptionSegment(it.text, it.start, it.end) }
        return TranscriptionResult(resp.text, segments, resp.duration)
    }
}

/** pro_lite 档：火山引擎豆包录音文件识别 2.0（submit + query 轮询）。 */
class VolcDoubaoTranscriber(
    private val connector: VolcDoubaoFileConnector,
) : FileTranscriber {
    override suspend fun transcribe(file: File, language: String?): TranscriptionResult {
        val resp = connector.transcribe(file, language)
        val segments = resp.segments?.map {
            TranscriptionSegment(it.text, it.startMs?.div(1000.0), it.endMs?.div(1000.0))
        } ?: emptyList()
        return TranscriptionResult(resp.text, segments, resp.durationMs?.div(1000.0))
    }
}

/** pro_pro 档：阿里云百炼 fun-asr-flash（同步多模态生成端点）。 */
class DashScopeFlashTranscriber(
    private val connector: DashScopeFlashAsrConnector,
) : FileTranscriber {
    override suspend fun transcribe(file: File, language: String?): TranscriptionResult {
        val resp = connector.transcribe(file, language = language)
        val segments = resp.segments?.map {
            TranscriptionSegment(it.text, it.startTime, it.endTime)
        } ?: emptyList()
        return TranscriptionResult(resp.text, segments, resp.duration)
    }
}

/**
 * 生产默认工厂：按 tier 惰性构造对应连接器（仅选中档才实例化，
 * 避免未配置的档在构造期抛错——如 DashScopeFlash 的 init 校验）。
 */
class DefaultFileTranscriberFactory(
    private val siliconFlow: () -> SiliconFlowAsrConnector = { SiliconFlowAsrConnector() },
    private val volc: () -> VolcDoubaoFileConnector = { VolcDoubaoFileConnector.fromBuildConfig() },
    private val dashScopeFlash: () -> DashScopeFlashAsrConnector = { DashScopeFlashAsrConnector() },
) : FileTranscriberFactory {
    override fun create(tier: String): FileTranscriber = when (tier) {
        "free" -> SiliconFlowTranscriber(siliconFlow())
        "pro_lite" -> VolcDoubaoTranscriber(volc())
        "pro_pro" -> DashScopeFlashTranscriber(dashScopeFlash())
        else -> throw IllegalArgumentException("未知 ASR tier: $tier")
    }
}

// =====================================================================================
// 实时连接器抽象（DashScopeRealtimeConnector 为具体类，经适配器接入管线）
// =====================================================================================

/** 实时流式连接器抽象（纯 JVM 可注入假实现）。 */
interface RealtimeAsrConnector {
    fun connect()
    fun sendPcm16k(pcm16k: ByteArray)
    fun flush()
    fun finish()
    fun close()
}

/** 创建实时连接器的工厂（回调在构造时注入）。 */
fun interface RealtimeConnectorFactory {
    fun create(callback: DashScopeRealtimeCallback): RealtimeAsrConnector
}

/** 把 [DashScopeRealtimeConnector] 适配为 [RealtimeAsrConnector]。 */
class DashScopeRealtimeAdapter(
    private val delegate: DashScopeRealtimeConnector,
) : RealtimeAsrConnector {
    override fun connect() = delegate.connect()
    override fun sendPcm16k(pcm16k: ByteArray) = delegate.sendPcm16k(pcm16k)
    override fun flush() = delegate.flush()
    override fun finish() = delegate.finish()
    override fun close() = delegate.close()
}

// =====================================================================================
// 转写落库抽象（纯 JVM 可注入假实现）
// =====================================================================================

/**
 * 转写落库契约（对齐 PC `recording_service.py` 的 save_transcription /
 * save_segment_transcription / set_segment_status / set_recording_status）。
 */
interface TranscriptionStore {

    /** 整文件转写落库（对齐 PC `save_transcription`）：清旧 chunks + 重排 chunk_index + 置 COMPLETED。 */
    suspend fun saveTranscription(
        recordingId: Long,
        transcriptionText: String,
        chunks: List<TranscriptChunkEntity>,
        durationSeconds: Double? = null,
    ): Int

    /** 单段转写落库（对齐 PC `save_segment_transcription`）：全局 chunk_index 续编 + 时间戳加段偏移。 */
    suspend fun saveSegmentTranscription(
        recordingId: Long,
        segmentIndex: Int,
        chunks: List<TranscriptChunkEntity>,
    ): Int

    /** 段转写失败标记（对齐 PC `set_segment_status(seg_id, "FAILED", error)`）。 */
    suspend fun markSegmentFailed(recordingId: Long, segmentIndex: Int, error: String?)

    /** 录音转写失败标记（对齐 PC `set_recording_status(rid, "FAILED", error)`）。 */
    suspend fun markRecordingFailed(recordingId: Long, error: String?)

    /** 录音转写完成标记（对齐 PC `finalize_segmented_recording` 的状态部分）。 */
    suspend fun markRecordingCompleted(recordingId: Long, durationSeconds: Double?)

    /** 保存融合笔记到 Recording.summary（todo 18 NoteFusionWorker 用）。录音不存在静默跳过。 */
    suspend fun saveSummary(recordingId: Long, summary: String)

    /** 保存自动标题到 Recording.title（todo 18 AutoTitleWorker 用）。录音不存在静默跳过。 */
    suspend fun saveTitle(recordingId: Long, title: String)
}

/** Room 实现：委托 [RecordingDao] + [SegmentDao]。 */
class RoomTranscriptionStore(
    private val recordingDao: RecordingDao,
    private val segmentDao: SegmentDao,
) : TranscriptionStore {

    override suspend fun saveTranscription(
        recordingId: Long,
        transcriptionText: String,
        chunks: List<TranscriptChunkEntity>,
        durationSeconds: Double?,
    ): Int = recordingDao.saveTranscription(recordingId, transcriptionText, chunks, durationSeconds)

    override suspend fun saveSegmentTranscription(
        recordingId: Long,
        segmentIndex: Int,
        chunks: List<TranscriptChunkEntity>,
    ): Int = recordingDao.saveSegmentTranscription(recordingId, segmentIndex, chunks)

    override suspend fun markSegmentFailed(recordingId: Long, segmentIndex: Int, error: String?) {
        val seg = recordingDao.getSegmentByIndex(recordingId, segmentIndex) ?: return
        segmentDao.updateTranscriptionStatus(seg.id, RecordingStatus.FAILED, error)
    }

    override suspend fun markRecordingFailed(recordingId: Long, error: String?) {
        val rec = recordingDao.getRecordingById(recordingId) ?: return
        recordingDao.updateRecording(rec.copy(status = RecordingStatus.FAILED, errorMessage = error))
    }

    override suspend fun markRecordingCompleted(recordingId: Long, durationSeconds: Double?) {
        val rec = recordingDao.getRecordingById(recordingId) ?: return
        recordingDao.updateRecording(
            rec.copy(
                status = RecordingStatus.COMPLETED,
                audioDurationSeconds = durationSeconds,
                completedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun saveSummary(recordingId: Long, summary: String) {
        val rec = recordingDao.getRecordingById(recordingId) ?: return
        recordingDao.updateRecording(rec.copy(summary = summary))
    }

    override suspend fun saveTitle(recordingId: Long, title: String) {
        val rec = recordingDao.getRecordingById(recordingId) ?: return
        recordingDao.updateRecording(rec.copy(title = title))
    }
}

// =====================================================================================
// WAV 读取 / 写入（纯 JVM，对齐 PC `_read_and_resample` + `_write_wav`）
// =====================================================================================

/**
 * WAV PCM 工具：读取任意采样率/声道数的 WAV 并归一化为 16kHz mono int16；
 * 写入 16kHz mono 16-bit WAV（段文件用）。
 */
object WavPcm {

    /** 全链路目标采样率（对齐 WavFileWriter.DEFAULT_SAMPLE_RATE）。 */
    const val SAMPLE_RATE = 16000

    /**
     * 读取 WAV → 16kHz mono int16 样本。
     * 多声道取均值、非 16kHz 线性插值重采样（对齐 PC `_read_and_resample`）。
     *
     * @throws IllegalArgumentException 非 RIFF/WAVE、fmt 缺失、非 16-bit、缺 data 块
     */
    fun readSamples(file: File): ShortArray {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) throw IllegalArgumentException("文件过短，不是有效 WAV")
            val riff = ByteArray(4)
            raf.readFully(riff)
            require(riff.contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) { "不是 RIFF/WAV 文件" }
            raf.skipBytes(4) // RIFF chunk size
            val wave = ByteArray(4)
            raf.readFully(wave)
            require(wave.contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) { "不是 WAVE 文件" }

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = -1L
            while (raf.filePointer < raf.length()) {
                val id = ByteArray(4)
                raf.readFully(id)
                val size = readLeUInt32(raf)
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        raf.skipBytes(2) // audioFormat
                        channels = readLeUInt16(raf)
                        sampleRate = readLeUInt32(raf).toInt()
                        raf.skipBytes(6) // byteRate(4) + blockAlign(2)
                        bitsPerSample = readLeUInt16(raf)
                        skipChunk(raf, size, consumed = 16)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        break
                    }
                    else -> skipChunk(raf, size, consumed = 0)
                }
            }
            require(sampleRate > 0 && channels > 0) { "WAV fmt 块缺失或无效" }
            require(bitsPerSample == 16) { "仅支持 16-bit PCM，实际 $bitsPerSample" }
            require(dataOffset >= 0) { "WAV 缺少 data 块" }

            // 流式 WAV 的 data size 可能为 0 / 0xFFFFFFFF，回退用文件长度推算
            val effectiveSize = if (dataSize == 0L || dataSize == 0xFFFFFFFFL) {
                raf.length() - dataOffset
            } else {
                dataSize
            }
            val sampleCount = (effectiveSize / 2).toInt()
            val raw = ByteArray(sampleCount * 2)
            raf.seek(dataOffset)
            raf.readFully(raw)
            val interleaved = ShortArray(sampleCount)
            for (i in interleaved.indices) {
                val lo = raw[i * 2].toInt() and 0xFF
                val hi = raw[i * 2 + 1].toInt() and 0xFF
                interleaved[i] = ((hi shl 8) or lo).toShort()
            }
            return toMono16k(interleaved, channels, sampleRate)
        }
    }

    /** 写 16kHz mono 16-bit WAV（44 字节头 + PCM，回填大小字段）。 */
    fun writeWav(file: File, samples: ShortArray) {
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, SAMPLE_RATE, 1, 16)
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            raf.write(bytes)
            WavFileWriter.finalize(raf, bytes.size)
        }
    }

    /** 多声道取均值 → mono；非 16kHz 重采样 → 16kHz。 */
    private fun toMono16k(interleaved: ShortArray, channels: Int, sampleRate: Int): ShortArray {
        val mono = if (channels > 1) {
            ShortArray(interleaved.size / channels) { i ->
                var sum = 0L
                for (c in 0 until channels) sum += interleaved[i * channels + c]
                (sum / channels).toShort()
            }
        } else {
            interleaved
        }
        return if (sampleRate == SAMPLE_RATE) mono else Resampler.resample(mono, sampleRate, SAMPLE_RATE)
    }

    private fun skipChunk(raf: RandomAccessFile, chunkSize: Long, consumed: Long) {
        val remaining = chunkSize - consumed
        if (remaining > 0) raf.skipBytes(remaining.toInt())
        if (chunkSize % 2 == 1L) raf.skipBytes(1) // RIFF 块按 2 字节对齐
    }

    private fun readLeUInt32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun readLeUInt16(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
}

// =====================================================================================
// 文件转写管线（整文件 + VAD 分段，对齐 PC `file_transcription_worker.py`）
// =====================================================================================

/**
 * 文件转写管线：按 [selectPipeline] 分发到整文件 / VAD 分段两种实现。
 *
 * **整文件管线**：读 WAV → 按 tier 选连接器 → transcribe → saveTranscription 落库（置 COMPLETED）。
 *
 * **VAD 分段管线**：读 WAV → VadSegmenter 分块喂帧（1024 帧块，末块补零）→ 逐段转写 →
 * saveSegmentTranscription（全局 chunk_index 续编）→ 段失败标记 FAILED 继续；
 * VAD 无段或全部段失败 → 回退整文件转写。
 *
 * **取消语义**：协程取消（[CancellationException]）原样传播，不标记 FAILED、不吞异常。
 * 其余异常经 [transcribe] 包装为 [TranscriptionPipelineException] 并标记录音 FAILED。
 */
class TranscriptionPipeline(
    private val store: TranscriptionStore,
    private val transcriberFactory: FileTranscriberFactory,
    private val vadParams: VadParams = VadParams(),
    private val tempDir: File = File(System.getProperty("java.io.tmpdir")),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 按 tier + VAD 开关分发管线。
     *
     * `realtime` 档走到这里说明流式结果不可用（连接失败 / 进程被杀），
     * 使用已持久化的 WAV 回退文件转写（[REALTIME_FALLBACK_TIER]）。
     *
     * @throws TranscriptionPipelineException 转写失败（已标记录音 FAILED）
     * @throws CancellationException 协程取消（原样传播，不标记 FAILED）
     */
    suspend fun transcribe(
        recordingId: Long,
        wavFile: File,
        tier: String,
        vadEnabled: Boolean,
        language: String? = null,
    ): TranscriptionOutcome {
        val fileTier = resolveFileTier(tier)
        return try {
            when (resolveFilePipeline(tier, vadEnabled)) {
                PipelineMode.REALTIME ->
                    throw IllegalArgumentException("realtime 档请使用 RealtimeTranscriptionPipeline 进行流式转写")
                PipelineMode.VAD_SEGMENTED ->
                    transcribeWithVad(recordingId, wavFile, fileTier, language)
                PipelineMode.WHOLE_FILE ->
                    transcribeWholeFile(recordingId, wavFile, fileTier, language)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.markRecordingFailed(recordingId, e.message ?: "转写失败")
            throw TranscriptionPipelineException(e.message ?: "转写失败", e)
        }
    }

    /** 整文件管线：读 WAV → 转写 → saveTranscription 落库（置 COMPLETED）。 */
    suspend fun transcribeWholeFile(
        recordingId: Long,
        wavFile: File,
        tier: String,
        language: String? = null,
    ): TranscriptionOutcome {
        val samples = withContext(ioDispatcher) { WavPcm.readSamples(wavFile) }
        if (samples.isEmpty()) throw TranscriptionPipelineException("音频文件为空或无法读取")
        val duration = samples.size / WavPcm.SAMPLE_RATE.toDouble()

        val transcriber = transcriberFactory.create(resolveFileTier(tier))
        val result = transcriber.transcribe(wavFile, language)
        val chunks = toChunks(recordingId, result)
        val count = store.saveTranscription(recordingId, result.text, chunks, duration)
        return TranscriptionOutcome(PipelineMode.WHOLE_FILE, result.text, count)
    }

    /**
     * VAD 分段管线：VAD 切割 → 逐段转写 → saveSegmentTranscription。
     * VAD 无段或全部段失败 → 回退整文件转写（[TranscriptionOutcome.fallbackUsed]=true）。
     */
    suspend fun transcribeWithVad(
        recordingId: Long,
        wavFile: File,
        tier: String,
        language: String? = null,
    ): TranscriptionOutcome {
        val samples = withContext(ioDispatcher) { WavPcm.readSamples(wavFile) }
        if (samples.isEmpty()) throw TranscriptionPipelineException("音频文件为空或无法读取")
        val duration = samples.size / WavPcm.SAMPLE_RATE.toDouble()

        val segments = runVad(samples)
        if (segments.isEmpty()) {
            // VAD 未检测到有效语音段 → 回退整文件转写（对齐 PC）
            return transcribeWholeFile(recordingId, wavFile, tier, language).copy(fallbackUsed = true)
        }

        val transcriber = transcriberFactory.create(resolveFileTier(tier))
        var successCount = 0
        var totalChunks = 0
        val textBuilder = StringBuilder()

        segments.forEachIndexed { idx, seg ->
            val startSample = (seg.startTime * WavPcm.SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val endSample = (seg.endTime * WavPcm.SAMPLE_RATE).toInt().coerceIn(startSample, samples.size)
            val segSamples = samples.copyOfRange(startSample, endSample)
            if (segSamples.isEmpty()) return@forEachIndexed

            val segFile = File(tempDir, "rec_${recordingId}_seg$idx.wav")
            try {
                withContext(ioDispatcher) { WavPcm.writeWav(segFile, segSamples) }
                val result = transcriber.transcribe(segFile, language)
                val chunks = toChunks(recordingId, result)
                val added = store.saveSegmentTranscription(recordingId, idx, chunks)
                successCount++
                totalChunks += added
                textBuilder.append(result.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 段失败标记 FAILED 继续（对齐 PC：set_segment_status FAILED 后继续下一段）
                store.markSegmentFailed(recordingId, idx, e.message ?: "段转写失败")
            } finally {
                segFile.delete()
            }
        }

        if (successCount == 0) {
            // 全部段失败 → 回退整文件转写
            return transcribeWholeFile(recordingId, wavFile, tier, language).copy(fallbackUsed = true)
        }

        store.markRecordingCompleted(recordingId, duration)
        return TranscriptionOutcome(
            mode = PipelineMode.VAD_SEGMENTED,
            text = textBuilder.toString(),
            chunkCount = totalChunks,
        )
    }

    /** 对音频数据运行 VAD，返回段边界列表（1024 帧块喂帧，末块补零，对齐 PC `_run_vad`）。 */
    private fun runVad(samples: ShortArray): List<Segment> {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            silenceThreshold = vadParams.silenceThreshold,
            silenceDuration = vadParams.silenceDuration,
            minSegment = vadParams.minSegment,
            maxSegment = vadParams.maxSegment,
            sampleRate = WavPcm.SAMPLE_RATE,
            onSegmentFinalize = { _, seg -> segments += seg },
        )
        vad.start()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + CHUNK_FRAMES, samples.size)
            var chunk = samples.copyOfRange(offset, end)
            if (chunk.size < CHUNK_FRAMES) chunk = chunk.copyOf(CHUNK_FRAMES) // 末块补零
            vad.consumeFrame(chunk)
            offset += CHUNK_FRAMES
        }
        vad.stop()?.let { segments += it }
        return segments
    }

    /** 归一化结果 → TranscriptChunkEntity 列表（chunk_index 由 DAO 重排，此处传 0）。 */
    private fun toChunks(recordingId: Long, result: TranscriptionResult): List<TranscriptChunkEntity> {
        val now = System.currentTimeMillis()
        val segments = result.segments.filter { it.text.isNotBlank() }
        if (segments.isNotEmpty()) {
            return segments.map {
                TranscriptChunkEntity(
                    recordingId = recordingId,
                    chunkIndex = 0,
                    content = it.text,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    createdAt = now,
                )
            }
        }
        return if (result.text.isNotBlank()) {
            listOf(
                TranscriptChunkEntity(
                    recordingId = recordingId,
                    chunkIndex = 0,
                    content = result.text,
                    createdAt = now,
                ),
            )
        } else {
            emptyList()
        }
    }

    companion object {
        /** VAD 喂帧块大小（对齐 PC `_CHUNK_FRAMES = 1024`）。 */
        const val CHUNK_FRAMES = 1024
    }
}

// =====================================================================================
// 实时流式管线（对齐 PC `realtime_transcription_worker.py`）
// =====================================================================================

/** 实时管线停止结果。 */
data class RealtimeStopResult(
    /** SDK 报错（onError）时 true：不落库，由调用方决定回退文件转写。 */
    val aborted: Boolean,
    val text: String,
    val sentenceCount: Int,
)

/** 实时管线回调监听（WebSocket 线程触发，调用方自行切线程）。 */
class RealtimeListeners(
    val onPartial: ((String) -> Unit)? = null,
    val onSentence: ((String, Int?, Int?) -> Unit)? = null,
    val onError: ((Throwable) -> Unit)? = null,
)

/**
 * 实时流式转写管线：连接 → 订阅音频帧（[feedPcm16k]）→ onPartial/onSentence 回调 →
 * 停止后落库（对齐 PC `realtime_transcription_worker.py`）。
 *
 * 线程模型：回调在 OkHttp WebSocket 线程触发，句子列表用锁保护；
 * [stop] 在 finish 后等待服务端 task-finished（onClosed）或超时，让最终句回调到达
 * （对齐 PC `session.stop()` 的 join 语义），随后读句子列表无并发。
 */
class RealtimeTranscriptionPipeline(
    private val connectorFactory: RealtimeConnectorFactory,
    private val store: TranscriptionStore,
    private val closeTimeoutMs: Long = 5000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private data class RealtimeSentence(
        val text: String,
        val beginTimeMs: Int?,
        val endTimeMs: Int?,
    )

    private val sentences = mutableListOf<RealtimeSentence>()
    private val closedLatch = CountDownLatch(1)
    private var connector: RealtimeAsrConnector? = null

    /** 是否发生过 SDK 报错（WS 线程写，投递线程读，volatile 保证可见）。 */
    @Volatile
    private var aborted = false

    /** 是否已 start（投递线程读，volatile 保证可见）。 */
    @Volatile
    private var started = false
    private var recordingId = 0L

    /** 连接并开始接收音频帧。连接失败（如 key 缺失）同步抛异常，可重试。 */
    fun start(recordingId: Long, listeners: RealtimeListeners = RealtimeListeners()) {
        check(!started) { "RealtimeTranscriptionPipeline 已启动" }
        this.recordingId = recordingId
        val conn = connectorFactory.create(object : DashScopeRealtimeCallback {
            override fun onPartial(text: String) {
                listeners.onPartial?.invoke(text)
            }

            override fun onSentence(text: String, beginTimeMs: Int?, endTimeMs: Int?) {
                synchronized(sentences) {
                    sentences += RealtimeSentence(text, beginTimeMs, endTimeMs)
                }
                listeners.onSentence?.invoke(text, beginTimeMs, endTimeMs)
            }

            override fun onError(error: Throwable) {
                aborted = true
                listeners.onError?.invoke(error)
            }

            override fun onClosed() {
                closedLatch.countDown()
            }
        })
        connector = conn
        conn.connect()
        started = true
    }

    /**
     * 投递 16kHz int16 mono PCM 帧（录音引擎输出）。
     * 未启动/已收尾/SDK 报错后静默忽略——本方法可能由录音引擎线程调用，
     * 不得抛异常（否则会打断音频读循环）。
     */
    fun feedPcm16k(pcm16k: ByteArray) {
        if (!started || aborted) return
        connector?.sendPcm16k(pcm16k)
    }

    /**
     * 停止并落库：finish → 等待最终句 → 拼装 chunks → saveTranscription（无句则仅置 COMPLETED）。
     * SDK 报错（aborted）时不落库，返回 [RealtimeStopResult.aborted]=true 供调用方回退文件转写。
     */
    suspend fun stop(): RealtimeStopResult = withContext(ioDispatcher) {
        val conn = connector
        if (conn == null) {
            return@withContext RealtimeStopResult(aborted = true, text = "", sentenceCount = 0)
        }
        try {
            conn.finish()
            if (!aborted) {
                // 等待服务端 task-finished（onClosed）或超时，让最终句回调到达
                closedLatch.await(closeTimeoutMs, TimeUnit.MILLISECONDS)
            }
            val collected = synchronized(sentences) { sentences.toList() }
            if (aborted) {
                return@withContext RealtimeStopResult(aborted = true, text = "", sentenceCount = collected.size)
            }
            val nonBlank = collected.filter { it.text.isNotBlank() }
            val chunks = nonBlank.map {
                TranscriptChunkEntity(
                    recordingId = recordingId,
                    chunkIndex = 0,
                    content = it.text,
                    startTime = it.beginTimeMs?.div(1000.0),
                    endTime = it.endTimeMs?.div(1000.0),
                    createdAt = System.currentTimeMillis(),
                )
            }
            val fullText = nonBlank.joinToString("") { it.text }
            if (chunks.isNotEmpty()) {
                store.saveTranscription(recordingId, fullText, chunks)
            } else {
                // 无最终句：标记完成但无转写内容（对齐 PC）
                store.markRecordingCompleted(recordingId, null)
            }
            RealtimeStopResult(aborted = false, text = fullText, sentenceCount = nonBlank.size)
        } finally {
            conn.close()
        }
    }
}

/** 转写管线失败（已标记录音 FAILED；取消异常不包装）。 */
class TranscriptionPipelineException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)