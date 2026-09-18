package com.example.ai_recorder_android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用数据库（version 2）。
 *
 * 迁移策略：录音数据不可丢，禁用破坏式迁移，全部走显式 [Migration]。
 * v1 → v2：为 recording_audio_segments / transcript_chunks 新增复合唯一索引
 * （recording_id + segment_index / chunk_index），防并发下重复或乱序段。
 * 索引名遵循 Room 约定 `index_<table>_<col...>`，须与 @Entity(indices) 生成名一致，
 * 否则 Room 迁移后 schema 校验失败。
 * schema 导出：exportSchema=true + build.gradle.kts `ksp { arg("room.schemaLocation", ...) }`
 * → `app/schemas/com.example.ai_recorder_android.data.db.AppDatabase/2.json`。
 */
@Database(
    entities = [
        RecordingEntity::class,
        TranscriptChunkEntity::class,
        RecordingAudioSegmentEntity::class,
        RecordingAttachmentEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun segmentDao(): SegmentDao

    companion object {
        private const val DB_NAME = "ai_recorder.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_recording_audio_segments_recording_id_segment_index` " +
                        "ON `recording_audio_segments` (`recording_id`, `segment_index`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_transcript_chunks_recording_id_chunk_index` " +
                        "ON `transcript_chunks` (`recording_id`, `chunk_index`)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /** 单例工厂：显式迁移链（v1→v2 建唯一索引）。 */
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}