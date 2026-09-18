package com.example.ai_recorder_android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 录音照片附件实体（表 recording_attachments），字段对齐 PC `core/models/attachment.py` 的 RecordingAttachment。
 *
 * - recording_id 外键 CASCADE：删除录音时级联删除附件。
 * - fused_chunk_id 可空外键 → transcript_chunks.id：chunk 被删除（save_transcription 替换旧 chunks）时
 *   置 NULL（SET_NULL）而非阻塞删除——PC 无级联语义，此为 Android 侧安全增强。
 * - 两个外键列均加索引（Room 要求）。
 */
@Entity(
    tableName = "recording_attachments",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recording_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TranscriptChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["fused_chunk_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("recording_id"), Index("fused_chunk_id")],
)
data class RecordingAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    @ColumnInfo(name = "attachment_type") val attachmentType: AttachmentType = AttachmentType.IMAGE,
    @ColumnInfo(name = "storage_locator") val storageLocator: String,
    @ColumnInfo(name = "thumbnail_locator") val thumbnailLocator: String? = null,
    val timestamp: Double,
    val description: String? = null,
    @ColumnInfo(name = "description_status") val descriptionStatus: DescriptionStatus = DescriptionStatus.PENDING,
    val source: AttachmentSource = AttachmentSource.CAMERA,
    @ColumnInfo(name = "fused_chunk_id") val fusedChunkId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)