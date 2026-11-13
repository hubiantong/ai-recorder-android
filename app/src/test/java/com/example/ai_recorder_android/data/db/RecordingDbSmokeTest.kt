package com.example.ai_recorder_android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room 建库冒烟单测（in-memory）：建库 → 插入 Recording + 关联 chunk → 断言读回。
 *
 * ⚠️ 依赖说明：本测试使用 [ApplicationProvider]（androidx.test:core），
 * 需在 build.gradle.kts 补 `testImplementation(libs.androidx.test.core)`——
 * todo 4 禁止改 build.gradle.kts，该依赖请在 todo 5 配置 schema 导出时一并补加。
 */
@RunWith(RobolectricTestRunner::class)
class RecordingDbSmokeTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.recordingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertRecordingAndChunk_readBackWithRelations() = runBlocking {
        val now = 1_752_000_000_000L

        val recordingId = dao.insertRecording(
            RecordingEntity(
                title = "冒烟测试录音",
                audioPath = "recordings/rec_smoke.wav",
                status = RecordingStatus.PENDING,
                audioSource = AudioSource.MIC,
                meetingMode = false,
                createdAt = now,
            ),
        )
        assertTrue("插入 Recording 应返回正 id", recordingId > 0)

        val chunkId = dao.insertChunk(
            TranscriptChunkEntity(
                recordingId = recordingId,
                chunkIndex = 0,
                content = "你好，这是冒烟测试。",
                startTime = 0.0,
                endTime = 1.5,
                createdAt = now,
            ),
        )
        assertTrue("插入 chunk 应返回正 id", chunkId > 0)

        val withRelations = dao.getRecordingWithRelations(recordingId)
        assertNotNull("应能读回 RecordingWithRelations", withRelations)
        assertEquals("冒烟测试录音", withRelations!!.recording.title)
        assertEquals(RecordingStatus.PENDING, withRelations.recording.status)
        assertEquals(AudioSource.MIC, withRelations.recording.audioSource)
        assertEquals(1, withRelations.chunks.size)
        assertEquals("你好，这是冒烟测试。", withRelations.chunks[0].content)
        assertEquals(0, withRelations.audioSegments.size)
        assertEquals(0, withRelations.attachments.size)

        val chunks = dao.getChunksForRecording(recordingId)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
    }
}