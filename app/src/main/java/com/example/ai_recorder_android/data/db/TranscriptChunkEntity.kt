package com.example.ai_recorder_android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 转写片段实体（表 transcript_chunks），字段对齐 PC `core/models/recording.py` 的 TranscriptChunk。
 *
 * recording_id 外键 CASCADE：删除录音时级联删除其全部 chunks。
 * 外键列 recording_id 加索引（Room 要求）。
 */
@Entity(
    tableName = "transcript_chunks",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recording_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("recording_id"),
        Index(value = ["recording_id", "chunk_index"], unique = true),
    ],
)
data class TranscriptChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
    @ColumnInfo(name = "start_time") val startTime: Double? = null,
    @ColumnInfo(name = "end_time") val endTime: Double? = null,
    @ColumnInfo(name = "speaker_name") val speakerName: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)