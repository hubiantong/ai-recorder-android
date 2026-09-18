package com.example.ai_recorder_android.data.db

import androidx.room.TypeConverter

/**
 * 录音状态（PC: recordings.status / recording_audio_segments.transcription_status，String(50)）。
 * 存储值 = PC 字符串常量：PENDING / PROCESSING / COMPLETED / FAILED。
 */
enum class RecordingStatus(val value: String) {
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    companion object {
        fun fromValue(value: String?): RecordingStatus? =
            entries.firstOrNull { it.value == value }
    }
}

/**
 * 附件描述生成状态（PC: recording_attachments.description_status，String(20)）。
 * 存储值 = PC 字符串常量：PENDING / PROCESSING / COMPLETED / FAILED。
 */
enum class DescriptionStatus(val value: String) {
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    companion object {
        fun fromValue(value: String?): DescriptionStatus? =
            entries.firstOrNull { it.value == value }
    }
}

/**
 * 录音音源（PC: recordings.audio_source，String(20)）。
 * 存储值 = PC 字符串常量：mic / system / mixed（会议模式新增）。
 */
enum class AudioSource(val value: String) {
    MIC("mic"),
    SYSTEM("system"),
    MIXED("mixed");

    companion object {
        fun fromValue(value: String?): AudioSource? =
            entries.firstOrNull { it.value == value }
    }
}

/**
 * 附件来源（PC: recording_attachments.source，String(20)）。
 * 存储值 = PC 字符串常量：camera / screenshot / file（会议模式新增）。
 */
enum class AttachmentSource(val value: String) {
    CAMERA("camera"),
    SCREENSHOT("screenshot"),
    FILE("file");

    companion object {
        fun fromValue(value: String?): AttachmentSource? =
            entries.firstOrNull { it.value == value }
    }
}

/**
 * 附件类型（PC: recording_attachments.attachment_type，String(20)）。
 * 存储值 = PC 字符串常量：image / video。
 */
enum class AttachmentType(val value: String) {
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun fromValue(value: String?): AttachmentType? =
            entries.firstOrNull { it.value == value }
    }
}

/**
 * Room TypeConverters：状态枚举 ↔ String（SQLite TEXT 列）。
 *
 * 存储值 = PC 字符串常量（见各枚举的 [value]），保证 Android 库与 PC SQLite 库字段值一致。
 * 实体字段用枚举类型（类型安全），经本类转换器落库为 TEXT。
 */
class Converters {

    @TypeConverter
    fun recordingStatusToString(status: RecordingStatus): String = status.value

    @TypeConverter
    fun stringToRecordingStatus(value: String): RecordingStatus =
        RecordingStatus.fromValue(value) ?: RecordingStatus.PENDING

    @TypeConverter
    fun descriptionStatusToString(status: DescriptionStatus): String = status.value

    @TypeConverter
    fun stringToDescriptionStatus(value: String): DescriptionStatus =
        DescriptionStatus.fromValue(value) ?: DescriptionStatus.PENDING

    @TypeConverter
    fun audioSourceToString(source: AudioSource): String = source.value

    @TypeConverter
    fun stringToAudioSource(value: String): AudioSource? = AudioSource.fromValue(value)

    @TypeConverter
    fun attachmentSourceToString(source: AttachmentSource): String = source.value

    @TypeConverter
    fun stringToAttachmentSource(value: String): AttachmentSource? = AttachmentSource.fromValue(value)

    @TypeConverter
    fun attachmentTypeToString(type: AttachmentType): String = type.value

    @TypeConverter
    fun stringToAttachmentType(value: String): AttachmentType? = AttachmentType.fromValue(value)
}