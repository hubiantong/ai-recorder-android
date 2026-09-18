package com.example.ai_recorder_android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 录音音频段实体（表 recording_audio_segments），字段对齐 PC `core/models/recording.py` 的 RecordingAudioSegment。
 *
 * VAD 切分出的单段音频文件 + 段级转写状态；TranscriptChunk 按段内句粒度再细分。
 * recording_id 外键 CASCADE + 索引（Room 要求）。
 */
@Entity(
    tableName = "recording_audio_segments",
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
        Index(value = ["recording_id", "segment_index"], unique = true),
    ],
)
data class RecordingAudioSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recording_id") val recordingId: Long,
    @ColumnInfo(name = "segment_index") val segmentIndex: Int,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    @ColumnInfo(name = "start_time") val startTime: Double? = null,
    @ColumnInfo(name = "end_time") val endTime: Double? = null,
    val duration: Double? = null,
    @ColumnInfo(name = "transcription_status") val transcriptionStatus: RecordingStatus = RecordingStatus.PENDING,
    @ColumnInfo(name = "transcription_error") val transcriptionError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
)