package com.example.ai_recorder_android.data.repo

import com.example.ai_recorder_android.core.audio.WavFileWriter
import com.example.ai_recorder_android.data.db.AttachmentDao
import com.example.ai_recorder_android.data.db.AttachmentSource
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.AudioSource
import com.example.ai_recorder_android.data.db.DescriptionStatus
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.db.RecordingAudioSegmentEntity
import com.example.ai_recorder_android.data.db.RecordingDao
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.storage.StorageService
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 录音仓储层：封装 [RecordingDao] + [AttachmentDao] + [StorageService]，
 * 提供录音生命周期与文件落盘的数据层原语（对齐 PC `pc_api/recording_service.py` 方法名）。
 *
 * **职责边界**：本类只做数据层薄委托（DB 行 + 文件），**业务语义不在本类**：
 * - 状态机编排（何时 start/stop、暂停时长累计、附件时间戳对齐、时长计算）→ todo 16 RecordingService；
 * - VAD 分段 / 转写管线 → todo 16/17；
 * - 音频引擎控制（AudioRecorderEngine，todo 8）→ todo 16 编排。
 *
 * 构造注入 [AttachmentDao] 的原因：`add_attachment` 需要插入附件行，
 * 而 [RecordingDao] 无附件插入方法（附件 CRUD 在 AttachmentDao，todo 5）。
 */
class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val attachmentDao: AttachmentDao,
    private val storage: StorageService,
) : RecordingStore {

    // ---------- 创建 / 生命周期 ----------

    /**
     * 创建一条 PENDING 录音记录（对齐 PC `create_recording`）。
     * [title] 为 null 时自动生成 `录音 yyyy-MM-dd HH:mm:ss`（对齐 PC 默认标题）；
     * [audioPath] 创建时为空串，由 [store] / [finalizeSegmented] 写入实际 locator
     * （PC 中该字段可空，Android 实体非空故用空串占位）。
     */
    override suspend fun create(
        title: String?,
        audioSource: AudioSource?,
        meetingMode: Boolean,
    ): RecordingEntity {
        val now = System.currentTimeMillis()
        val entity = RecordingEntity(
            title = title ?: defaultTitle(now),
            audioPath = "",
            status = RecordingStatus.PENDING,
            audioSource = audioSource,
            meetingMode = meetingMode,
            createdAt = now,
        )
        val id = recordingDao.insertRecording(entity)
        return entity.copy(id = id)
    }

    /**
     * 开始录音：数据层将状态落库为 PROCESSING。
     * 引擎启动/输出文件编排在 todo 16（本类不持有音频引擎）。
     */
    override suspend fun start(recordingId: Long): RecordingEntity? {
        val rec = recordingDao.getRecordingById(recordingId) ?: return null
        recordingDao.updateRecording(rec.copy(status = RecordingStatus.PROCESSING))
        return rec
    }

    /**
     * 停止录音：数据层将状态落库为 COMPLETED，并写入音频时长与完成时间。
     * 时间戳对齐/先 [store] 再 stop 的编排在 todo 16。
     */
    override suspend fun stop(recordingId: Long, durationSeconds: Double): RecordingEntity? {
        val rec = recordingDao.getRecordingById(recordingId) ?: return null
        recordingDao.updateRecording(
            rec.copy(
                status = RecordingStatus.COMPLETED,
                audioDurationSeconds = durationSeconds,
                completedAt = System.currentTimeMillis(),
            ),
        )
        return rec
    }

    /**
     * 暂停录音：暂停状态存于音频引擎（todo 8 AudioRecorderEngine），无 DB 字段；
     * 此处仅校验录音存在（fail-fast），引擎暂停与暂停时长累计由 todo 16 编排。
     */
    suspend fun pause(recordingId: Long) {
        require(recordingDao.getRecordingById(recordingId) != null) { "Recording $recordingId not found" }
    }

    /** 恢复录音：同 [pause]，引擎恢复由 todo 16 编排。 */
    suspend fun resume(recordingId: Long) {
        require(recordingDao.getRecordingById(recordingId) != null) { "Recording $recordingId not found" }
    }

    // ---------- 文件落盘 ----------

    /**
     * 将录音文件存入 StorageService 并更新 Recording.audioPath（对齐 PC `store_audio`）。
     * 返回相对路径 locator（`recordings/rec_{uuid}.{ext}`）；录音不存在返回 null 且不落盘。
     */
    override suspend fun store(recordingId: Long, fileName: String, data: ByteArray): String? {
        val rec = recordingDao.getRecordingById(recordingId) ?: return null
        val locator = storage.saveRecording(fileName, data)
        recordingDao.updateRecording(rec.copy(audioPath = locator))
        return locator
    }

    /**
     * 删除录音：DB 行级联删除（chunks/segments/attachments）+ 尽力清理 storage 文件
     * （对齐 PC `delete_recording`：先收集 locator 再删行，文件删除失败不影响 DB 一致性）。
     * 录音不存在时静默返回（对齐 PC 跳过语义）。
     */
    override suspend fun delete(recordingId: Long) {
        val rec = recordingDao.getRecordingById(recordingId) ?: return
        val attachmentLocators = recordingDao.getAttachmentsForRecording(recordingId)
            .flatMap { listOfNotNull(it.storageLocator, it.thumbnailLocator) }
        recordingDao.deleteById(recordingId)
        storage.deleteRecordingFiles(recordingId, rec.audioPath, attachmentLocators)
    }

    /**
     * 分段录音收尾：合并全部段文件为整段 WAV 并清理段文件（对齐 PC `finalize_segmented_recording`）。
     *
     * 1. 读取各段文件（`segments/seg_{rid}_{idx}.wav`），剥离 44 字节 WAV 头后拼接 PCM 载荷；
     * 2. 以 16kHz/16-bit/mono 写整段 WAV（[WavFileWriter] 头 + 拼接 PCM）→ `recordings/rec_{uuid}.wav`；
     * 3. 更新 Recording.audioPath 为新 locator；
     * 4. 删除全部段文件。
     *
     * 返回合并后的 locator；录音不存在/无段/无有效音频数据时返回 null。
     * 状态置 COMPLETED、duration 写入等业务语义留给 todo 16。
     */
    override suspend fun finalizeSegmented(recordingId: Long, totalDuration: Double): String? {
        val rec = recordingDao.getRecordingById(recordingId) ?: return null
        val segments = recordingDao.getSegmentsForRecording(recordingId)
        if (segments.isEmpty()) return null

        val pcm = ByteArrayOutputStream()
        for (segment in segments) {
            val bytes = storage.readFile(segment.audioPath) ?: continue
            val payload = if (bytes.size > WavFileWriter.HEADER_SIZE) {
                bytes.copyOfRange(WavFileWriter.HEADER_SIZE, bytes.size)
            } else {
                bytes
            }
            pcm.write(payload)
        }
        val payload = pcm.toByteArray()
        if (payload.isEmpty()) return null

        val locator = storage.saveRecording("merged.wav", buildWav(payload))
        recordingDao.updateRecording(rec.copy(audioPath = locator))
        segments.forEach { storage.deleteFile(it.audioPath) }
        return locator
    }

    // ---------- 音频段 ----------

    /**
     * 落库一段音频：文件存入 StorageService（`segments/seg_{rid}_{idx}.wav`）+ 插入段行
     * （对齐 PC `add_audio_segment`）。录音不存在返回 null 且不落盘。
     * 段时间轴（startTime/endTime/duration）由编排层（todo 16）按 PCM 载荷计算。
     */
    override suspend fun addSegment(
        recordingId: Long,
        segmentIndex: Int,
        data: ByteArray,
        startTime: Double,
        endTime: Double,
        duration: Double,
    ): Long? {
        if (recordingDao.getRecordingById(recordingId) == null) return null
        val locator = storage.saveSegment(recordingId, segmentIndex, data)
        val entity = RecordingAudioSegmentEntity(
            recordingId = recordingId,
            segmentIndex = segmentIndex,
            audioPath = locator,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            transcriptionStatus = RecordingStatus.PENDING,
            createdAt = System.currentTimeMillis(),
        )
        return recordingDao.insertSegment(entity)
    }

    // ---------- 附件 ----------

    /**
     * 添加附件：文件存入 StorageService（`attachments/att_{uuid}.{ext}`）+ 插入附件行
     * （对齐 PC `add_attachment`）。录音不存在返回 null 且不落盘。
     * 时间戳对齐（扣除暂停）等业务语义留给 todo 16。
     */
    override suspend fun addAttachment(
        recordingId: Long,
        fileName: String,
        data: ByteArray,
        timestamp: Double,
        source: AttachmentSource,
        attachmentType: AttachmentType,
    ): RecordingAttachmentEntity? {
        if (recordingDao.getRecordingById(recordingId) == null) return null
        val locator = storage.saveAttachment(fileName, data)
        val entity = RecordingAttachmentEntity(
            recordingId = recordingId,
            attachmentType = attachmentType,
            storageLocator = locator,
            timestamp = timestamp,
            descriptionStatus = DescriptionStatus.PENDING,
            source = source,
            createdAt = System.currentTimeMillis(),
        )
        val id = attachmentDao.insert(entity)
        return entity.copy(id = id)
    }

    // ---------- 内部 ----------

    /** 拼接 PCM 载荷为完整 WAV（44 字节头 + 数据，回填 RIFF/data 大小字段）。 */
    private fun buildWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(WavFileWriter.HEADER_SIZE + pcm.size)
        WavFileWriter.writeHeader(
            out,
            WavFileWriter.DEFAULT_SAMPLE_RATE,
            WavFileWriter.DEFAULT_CHANNELS,
            WavFileWriter.DEFAULT_BITS_PER_SAMPLE,
        )
        out.write(pcm)
        val bytes = out.toByteArray()
        writeIntLE(bytes, 4, 36 + pcm.size) // RIFF chunkSize
        writeIntLE(bytes, 40, pcm.size) // dataSize
        return bytes
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun defaultTitle(now: Long): String =
        "录音 " + Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}