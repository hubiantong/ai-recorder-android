package com.example.ai_recorder_android.service

import com.example.ai_recorder_android.core.audio.RecordingAudioEngine
import com.example.ai_recorder_android.core.audio.RecordingAudioEngineFactory
import com.example.ai_recorder_android.core.audio.RecordingEngineEvents
import com.example.ai_recorder_android.core.audio.WavFileWriter
import com.example.ai_recorder_android.data.db.AttachmentSource
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.AudioSource
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.repo.RecordingStore
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 录音编排层状态机（todo 16）：对齐 PC `core/audio/recorder.py` + `pc_api/recording_service.py`。
 *
 * 状态流转：`IDLE → RECORDING ⇄ PAUSED → COMPLETED`；[stop] 后允许重新 [startRecording]（PC 语义）。
 */
enum class CoordinatorState { IDLE, RECORDING, PAUSED, COMPLETED }

/**
 * 时钟抽象（注入单调/墙钟，测试可控）。
 * 单调时钟用于暂停时长累计（对齐引擎 SystemClock.elapsedRealtime 语义），
 * 墙钟用于照片时间戳对齐（对齐 PC `time.time()`）。
 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        /** 单调时钟（不受系统时间调整影响）。 */
        fun monotonic(): Clock = Clock { System.nanoTime() / 1_000_000 }

        /** 墙钟。 */
        fun wall(): Clock = Clock { System.currentTimeMillis() }
    }
}

/**
 * 录音编排层：状态机 + 暂停时长累计 + 照片时间戳对齐（对齐 PC `pc_api/recording_service.py`）。
 *
 * **职责**：
 * - 状态机编排：create→PROCESSING→引擎 start；stop 时 finalize 段文件、段落库、
 *   单段 WAV 落库为 recordings/rec_xxx.wav、状态置 COMPLETED；
 * - 暂停时长累计：用注入的单调时钟（对齐引擎 totalPausedMs 语义），供时间轴对齐扣除；
 * - 附件时间戳对齐：`photo_time - recording_start_time - 暂停`（Metis P6.8）。
 *
 * **线程模型**（Metis P6.1）：
 * - 会话变更方法（start/pause/resume/stop/addAttachment/delete）经 [Mutex] 串行化；
 * - 引擎事件（onSegmentClosed/onLevel/onError）来自引擎线程或调用线程，段列表用
 *   [segmentLock] 保护（引擎线程写、stop 线程消费）；
 * - 文件 IO 与阻塞的引擎 start/stop 在 [ioDispatcher] 上执行。
 *
 * **与 todo 10 前台服务的关系**：本类与应用层 [RecordingCoordinator] 职责分离——
 * 前台服务负责通知/生命周期（todo 10），本类负责业务编排；todo 19 接线二者
 * （服务事件 → 本类方法调用），本类不持有 Android 依赖（纯 JVM 可测）。
 *
 * **待办**：VAD 多段合并（[RecordingStore.finalizeSegmented]）与段转写为 todo 17；
 * 本次 stop 仅处理单段场景（多段时仅落段行，不落整段 WAV）。
 */
class RecordingCoordinator(
    private val repository: RecordingStore,
    private val engineFactory: RecordingAudioEngineFactory,
    private val monotonicClock: Clock = Clock.monotonic(),
    private val wallClock: Clock = Clock.wall(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RecordingEngineEvents {

    /** 引擎已关闭的段（回调线程写入，stop 消费）。 */
    private data class ClosedSegment(val index: Int, val file: File, val durationMs: Long)

    private val segmentLock = Any()
    private val closedSegments = mutableListOf<ClosedSegment>()
    private var lastSegmentFile: File? = null
    private var lastSegmentIndex = 0

    private val mutex = Mutex()

    @Volatile
    private var state = CoordinatorState.IDLE

    @Volatile
    private var activeEngine: RecordingAudioEngine? = null

    @Volatile
    private var activeRecordingId: Long? = null

    /** 录音开始墙钟时刻（照片时间戳对齐基准）。 */
    @Volatile
    private var recordingStartWallMs = 0L

    /** 录音开始单调时刻（暂停累计/时长计算基准）。 */
    @Volatile
    private var recordingStartMs = 0L

    /** 已累计的暂停时长（毫秒，单调时钟）。 */
    @Volatile
    private var totalPausedMs = 0L

    /** 本次暂停开始时刻；0 表示未在暂停。 */
    @Volatile
    private var pauseStartMs = 0L

    // ---------- 对外状态 ----------

    val currentState: CoordinatorState get() = state
    val currentRecordingId: Long? get() = activeRecordingId

    /** 累计暂停时长（毫秒）。 */
    val pausedDurationMs: Long get() = totalPausedMs

    /** 音量电平监听（todo 19 接 UI 波形）；引擎线程回调。 */
    var levelListener: ((Double) -> Unit)? = null

    /** 录音中 16kHz PCM 帧监听（引擎线程回调，实时转写投递用；调用方保证非阻塞）。 */
    var pcmListener: ((ByteArray) -> Unit)? = null

    /**
     * 实时转写文本监听：`partial=true` 为中间结果（替换式更新）、false 为最终句。
     * 调用方（RecordingService）保证已切主线程。
     */
    var transcriptListener: ((partial: Boolean, text: String) -> Unit)? = null

    /** 引擎错误监听（todo 19 接 UI/兜底）；引擎线程回调。 */
    var errorListener: ((Throwable) -> Unit)? = null

    /**
     * 录音已过时长（毫秒，扣除暂停；当前暂停部分也扣除）。未录音返回 0。
     */
    fun elapsedMs(): Long {
        val startMs = recordingStartMs
        if (startMs == 0L) return 0L
        val paused = pausedIncludingInProgress()
        return (monotonicClock.nowMs() - startMs - paused).coerceAtLeast(0L)
    }

    // ---------- 生命周期 ----------

    /**
     * 开始录音（对齐 PC `create_recording` + `start_recording`）：
     * 1. 创建 PENDING 行 → 状态落库 PROCESSING；
     * 2. 经 [RecordingAudioEngineFactory] 创建引擎并 start（输出段文件到 [outputDir]）；
     * 3. 引擎 start 失败时回滚删除 DB 行并重抛。
     *
     * @throws IllegalStateException 已在录音中
     */
    suspend fun startRecording(
        outputDir: File,
        title: String? = null,
        audioSource: AudioSource? = null,
        meetingMode: Boolean = false,
    ): RecordingEntity = mutex.withLock {
        check(state == CoordinatorState.IDLE || state == CoordinatorState.COMPLETED) {
            "已在录音中（state=$state）"
        }

        val rec = repository.create(title, audioSource, meetingMode)
        repository.start(rec.id)

        val engine = engineFactory.create(rec.id, outputDir, this)
        try {
            withContext(ioDispatcher) { engine.start() }
        } catch (e: Exception) {
            // 回滚：引擎启动失败时删除已创建的 DB 行（PC 语义：失败不残留脏记录）
            repository.delete(rec.id)
            throw e
        }

        synchronized(segmentLock) {
            closedSegments.clear()
            lastSegmentFile = null
        }
        activeEngine = engine
        activeRecordingId = rec.id
        recordingStartWallMs = wallClock.nowMs()
        recordingStartMs = monotonicClock.nowMs()
        totalPausedMs = 0L
        pauseStartMs = 0L
        state = CoordinatorState.RECORDING
        rec
    }

    /** 暂停：状态 RECORDING→PAUSED。仅在录音中生效，重复调用忽略（对齐 PC `pause_recording`）。 */
    suspend fun pause() = mutex.withLock {
        if (state != CoordinatorState.RECORDING) return@withLock
        val engine = activeEngine ?: return@withLock
        engine.pause()
        pauseStartMs = monotonicClock.nowMs()
        state = CoordinatorState.PAUSED
    }

    /** 恢复：状态 PAUSED→RECORDING，累计本次暂停时长。仅在暂停中生效（对齐 PC `resume_recording`）。 */
    suspend fun resume() = mutex.withLock {
        if (state != CoordinatorState.PAUSED) return@withLock
        val engine = activeEngine ?: return@withLock
        engine.resume()
        if (pauseStartMs != 0L) {
            totalPausedMs += monotonicClock.nowMs() - pauseStartMs
            pauseStartMs = 0L
        }
        state = CoordinatorState.RECORDING
    }

    /**
     * 停止录音（对齐 PC `stop_recording`）：
     * 1. 收尾进行中的暂停；
     * 2. 引擎 stop（finalize 段文件，同步送达最后一段 onSegmentClosed）；
     * 3. 段文件按引擎回调顺序落库 [RecordingStore.addSegment]（时间轴按 PCM 载荷时长累加，
     *    暂停期间不写 PCM 故自然扣除）；单段场景另存为整段 WAV（recordings/rec_*.wav）；
     * 4. 状态落库 COMPLETED，返回录音时长（秒）。
     *
     * @throws IllegalStateException 无活动录音
     */
    suspend fun stop(): Double = mutex.withLock {
        check(state == CoordinatorState.RECORDING || state == CoordinatorState.PAUSED) {
            "无活动录音（state=$state）"
        }
        // 收尾进行中的暂停
        if (state == CoordinatorState.PAUSED && pauseStartMs != 0L) {
            totalPausedMs += monotonicClock.nowMs() - pauseStartMs
            pauseStartMs = 0L
        }
        val engine = checkNotNull(activeEngine) { "引擎缺失" }
        val recordingId = checkNotNull(activeRecordingId) { "录音 id 缺失" }

        withContext(ioDispatcher) { engine.stop() }
        val segments = drainClosedSegments()

        // 段落库：按 PCM 载荷算时长（暂停不写 PCM → 音频时间轴自然扣除暂停）
        val loaded = segments.map { seg ->
            seg to withContext(ioDispatcher) { seg.file.readBytes() }
        }
        var cursor = 0.0
        for ((seg, bytes) in loaded) {
            val payloadSize = maxOf(0, bytes.size - WavFileWriter.HEADER_SIZE)
            val duration = payloadSize / BYTES_PER_SECOND.toDouble()
            repository.addSegment(recordingId, seg.index, bytes, cursor, cursor + duration, duration)
            cursor += duration
        }
        // 单段场景：段文件即整段 WAV，落库为 recordings/rec_*.wav（多段合并为 todo 17）
        if (loaded.size == 1) {
            repository.store(recordingId, "recording.wav", loaded[0].second)
        }
        repository.stop(recordingId, cursor)

        // 复位会话（时长已随 stop 落库 audio_duration_seconds）
        activeEngine = null
        activeRecordingId = null
        recordingStartWallMs = 0L
        recordingStartMs = 0L
        totalPausedMs = 0L
        pauseStartMs = 0L
        state = CoordinatorState.COMPLETED
        cursor
    }

    // ---------- 附件 ----------

    /**
     * 添加附件：计算对齐后的录音时间轴秒数并落库（对齐 PC `add_attachment`）。
     *
     * @param photoWallClockMs 拍照墙钟时刻（毫秒）
     * @throws IllegalStateException 无活动录音
     */
    suspend fun addAttachment(
        photoWallClockMs: Long,
        fileName: String,
        data: ByteArray,
        source: AttachmentSource = AttachmentSource.CAMERA,
        attachmentType: AttachmentType = AttachmentType.IMAGE,
    ): RecordingAttachmentEntity? = mutex.withLock {
        check(state == CoordinatorState.RECORDING || state == CoordinatorState.PAUSED) {
            "无活动录音（state=$state）"
        }
        val recordingId = checkNotNull(activeRecordingId) { "录音 id 缺失" }
        repository.addAttachment(
            recordingId = recordingId,
            fileName = fileName,
            data = data,
            timestamp = alignedTimestampSeconds(photoWallClockMs),
            source = source,
            attachmentType = attachmentType,
        )
    }

    /**
     * 拍照时刻相对录音开始的秒数（用于附件时间轴，Metis P6.8）。
     *
     * 输入 [photoWallClockMs] 是墙钟时刻（仅作日历语义），但偏移计算走单调路径：
     * 先用「当前墙钟差 − 当前单调差」估计录音开始以来的墙钟漂移（NTP 校时/用户改钟），
     * 把照片的墙钟差校正回单调时间轴，再扣除单调计量的暂停时长。时钟未调整时漂移为 0，
     * 结果与纯墙钟差一致。下限 0；未录音返回 0.0（对齐 PC `photo_timestamp` 的 0.0 兜底语义）。
     */
    fun alignedTimestampSeconds(photoWallClockMs: Long): Double {
        if (state != CoordinatorState.RECORDING && state != CoordinatorState.PAUSED) return 0.0
        if (recordingStartWallMs == 0L || recordingStartMs == 0L) return 0.0
        val paused = pausedIncludingInProgress()
        val wallDriftMs =
            (wallClock.nowMs() - recordingStartWallMs) - (monotonicClock.nowMs() - recordingStartMs)
        val photoMonoDeltaMs = photoWallClockMs - recordingStartWallMs - wallDriftMs
        return ((photoMonoDeltaMs - paused).coerceAtLeast(0L)) / 1000.0
    }

    // ---------- 会话管理 ----------

    /**
     * 删除录音（对齐 PC `delete_recording`）：DB 级联删除 + 尽力清理 storage 文件。
     * 正在录音（RECORDING/PAUSED）的会话禁止删除。
     */
    suspend fun delete(recordingId: Long) = mutex.withLock {
        val active = activeRecordingId
        if ((state == CoordinatorState.RECORDING || state == CoordinatorState.PAUSED) && recordingId == active) {
            throw IllegalStateException("不能删除正在录音的会话（recordingId=$recordingId）")
        }
        repository.delete(recordingId)
        if (recordingId == active) {
            activeRecordingId = null
            activeEngine = null
            state = CoordinatorState.IDLE
        }
    }

    /** 分段录音收尾（对齐 PC `finalize_segmented_recording`）：委托数据层合并段文件并置 COMPLETED。 */
    suspend fun finalizeSegmented(recordingId: Long, totalDuration: Double): String? =
        repository.finalizeSegmented(recordingId, totalDuration)

    // ---------- 引擎事件（引擎线程或调用线程） ----------

    override fun onLevel(rms: Double) {
        levelListener?.invoke(rms)
    }

    override fun onPcmFrame(pcm: ByteArray) {
        pcmListener?.invoke(pcm)
    }

    override fun onSegmentClosed(index: Int, file: File, durationMs: Long) {
        synchronized(segmentLock) {
            closedSegments += ClosedSegment(index, file, durationMs)
            lastSegmentFile = file
            lastSegmentIndex = index
        }
    }

    override fun onError(error: Throwable) {
        errorListener?.invoke(error)
    }

    // ---------- 内部 ----------

    private fun pausedIncludingInProgress(): Long {
        val inProgress = if (pauseStartMs != 0L) monotonicClock.nowMs() - pauseStartMs else 0L
        return (totalPausedMs + inProgress).coerceAtLeast(0L)
    }

    /** 取出所有已关闭段（按段序号升序）。 */
    private fun drainClosedSegments(): List<ClosedSegment> {
        val drained = synchronized(segmentLock) {
            val list = closedSegments.toList()
            closedSegments.clear()
            list
        }.sortedBy { it.index }
        if (drained.isNotEmpty()) return drained
        // 兜底：读循环线程因 join 超时迟到时用最近一段
        return synchronized(segmentLock) {
            val last = lastSegmentFile
            if (last != null) listOf(ClosedSegment(lastSegmentIndex, last, 0L)) else emptyList()
        }
    }

    companion object {
        /** 每秒字节数 = 16kHz × 16bit/8（对齐 WavFileWriter：16kHz/16-bit/mono）。 */
        const val BYTES_PER_SECOND = WavFileWriter.DEFAULT_SAMPLE_RATE * WavFileWriter.DEFAULT_BITS_PER_SAMPLE / 8
    }
}
