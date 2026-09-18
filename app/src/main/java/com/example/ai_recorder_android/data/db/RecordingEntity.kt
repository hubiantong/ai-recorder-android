package com.example.ai_recorder_android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 录音主实体（表 recordings），字段对齐 PC `core/models/recording.py` 的 Recording。
 *
 * 类型映射：DateTime→Long(epoch 毫秒)、Float→Double(REAL)、String→TEXT、Boolean→INTEGER。
 * 状态/音源字段用枚举类型，经 [Converters] 落库为 PC 字符串常量（TEXT）。
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    val transcription: String? = null,
    val summary: String? = null,
    val status: RecordingStatus = RecordingStatus.PENDING,
    @ColumnInfo(name = "audio_duration_seconds") val audioDurationSeconds: Double? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "original_filename") val originalFilename: String? = null,
    @ColumnInfo(name = "audio_source") val audioSource: AudioSource? = null,
    @ColumnInfo(name = "meeting_mode") val meetingMode: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)