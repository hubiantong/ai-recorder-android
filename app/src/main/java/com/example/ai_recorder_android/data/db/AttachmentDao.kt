package com.example.ai_recorder_android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * 附件 DAO（表 recording_attachments），语义对齐 PC `recording_service.py` 附件相关方法。
 */
@Dao
interface AttachmentDao {

    @Insert
    suspend fun insert(attachment: RecordingAttachmentEntity): Long

    @Update
    suspend fun update(attachment: RecordingAttachmentEntity)

    /** 按录音查附件，timestamp 升序（对齐 PC `list_attachments`）。 */
    @Query("SELECT * FROM recording_attachments WHERE recording_id = :recordingId ORDER BY timestamp ASC")
    suspend fun getByRecordingId(recordingId: Long): List<RecordingAttachmentEntity>

    @Query("DELETE FROM recording_attachments WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 更新附件融合 chunk 关联（VLM 融合笔记定位用；置 null 解除关联）。 */
    @Query("UPDATE recording_attachments SET fused_chunk_id = :fusedChunkId WHERE id = :attachmentId")
    suspend fun updateFusedChunkId(attachmentId: Long, fusedChunkId: Long?)
}