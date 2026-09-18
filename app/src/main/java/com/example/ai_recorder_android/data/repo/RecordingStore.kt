package com.example.ai_recorder_android.data.repo

import com.example.ai_recorder_android.data.db.AttachmentSource
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.AudioSource
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.db.RecordingEntity

/**
 * 录音数据层契约（todo 16 编排层依赖，纯 JVM 单测可注入假实现）。
 *
 * 方法签名与 [RecordingRepository] 一一对应，语义对齐 PC `pc_api/recording_service.py`：
 * - [create] ↔ `create_recording`；[start]/[stop] ↔ `start_recording`/`stop_recording` 的落库部分
 * - [store] ↔ `store_audio`；[delete] ↔ `delete_recording`
 * - [addSegment] ↔ `add_audio_segment`；[addAttachment] ↔ `add_attachment`
 * - [finalizeSegmented] ↔ `finalize_segmented_recording`
 *
 * 业务编排（状态机、暂停时长累计、附件时间戳对齐）在 todo 16 编排层，不在本接口。
 */
interface RecordingStore {

    /** 创建一条 PENDING 录音记录；[title] 为 null 时自动生成默认标题。 */
    suspend fun create(
        title: String? = null,
        audioSource: AudioSource? = null,
        meetingMode: Boolean = false,
    ): RecordingEntity

    /** 开始录音：数据层将状态落库为 PROCESSING。录音不存在返回 null。 */
    suspend fun start(recordingId: Long): RecordingEntity?

    /**
     * 停止录音：数据层将状态落库为 COMPLETED，并写入 [durationSeconds]（音频时长）、
     * completed_at。录音不存在返回 null。
     */
    suspend fun stop(recordingId: Long, durationSeconds: Double): RecordingEntity?

    /** 将录音文件存入 StorageService 并更新 Recording.audioPath，返回 locator；不存在返回 null。 */
    suspend fun store(recordingId: Long, fileName: String, data: ByteArray): String?

    /** 删除录音：DB 行级联删除 + 尽力清理 storage 文件；不存在时静默返回。 */
    suspend fun delete(recordingId: Long)

    /** 分段录音收尾：合并全部段文件为整段 WAV 并清理段文件，返回合并后 locator；无段返回 null。 */
    suspend fun finalizeSegmented(recordingId: Long, totalDuration: Double): String?

    /**
     * 落库一段音频（对齐 PC `add_audio_segment`）：文件存入 StorageService
     * （`segments/seg_{rid}_{idx}.wav`）+ 插入段行（transcription_status=PENDING）。
     * 录音不存在返回 null 且不落盘；成功返回段 id。
     */
    suspend fun addSegment(
        recordingId: Long,
        segmentIndex: Int,
        data: ByteArray,
        startTime: Double,
        endTime: Double,
        duration: Double,
    ): Long?

    /**
     * 添加附件（对齐 PC `add_attachment`）：文件存入 StorageService + 插入附件行。
     * [timestamp] 为录音时间轴秒数（编排层已做暂停扣除对齐）。录音不存在返回 null 且不落盘。
     */
    suspend fun addAttachment(
        recordingId: Long,
        fileName: String,
        data: ByteArray,
        timestamp: Double,
        source: AttachmentSource = AttachmentSource.CAMERA,
        attachmentType: AttachmentType = AttachmentType.IMAGE,
    ): RecordingAttachmentEntity?
}
