package com.example.ai_recorder_android.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai_recorder_android.core.audio.WavFileWriter
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.AttachmentSource
import com.example.ai_recorder_android.data.db.AudioSource
import com.example.ai_recorder_android.data.db.RecordingAudioSegmentEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.storage.StorageService
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RecordingRepository 单测（in-memory Room + 临时目录 StorageService）：
 * create 默认值/标题、start/stop 状态落库、store 落盘+audioPath、
 * addAttachment 落盘+插行、finalizeSegmented 合并段文件+清理、
 * delete 级联删行+删文件、缺失录音的 no-op / fail-fast。
 */
class RecordingRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var storage: StorageService
    private lateinit var repo: RecordingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        storage = StorageService(tmp.newFolder("storage"))
        repo = RecordingRepository(db.recordingDao(), db.attachmentDao(), storage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- create ----------

    @Test
    fun create_insertsPendingRecordingWithAutoTitle() = runBlocking {
        val rec = repo.create(title = null, audioSource = AudioSource.MIC, meetingMode = false)

        assertTrue("插入应返回正 id", rec.id > 0)
        assertEquals(RecordingStatus.PENDING, rec.status)
        assertEquals(AudioSource.MIC, rec.audioSource)
        assertFalse(rec.meetingMode)
        assertTrue("createdAt 应为当前时间", rec.createdAt > 0)
        assertTrue("null 标题应自动生成", rec.title!!.startsWith("录音 "))
        assertEquals("audioPath 创建时为空串，store() 后写入", "", rec.audioPath)
        assertEquals("落库实体应与返回一致", rec, db.recordingDao().getRecordingById(rec.id))
    }

    @Test
    fun create_withTitleAndMeetingMode() = runBlocking {
        val rec = repo.create(title = "产品会议", audioSource = AudioSource.MIXED, meetingMode = true)

        assertEquals("产品会议", rec.title)
        assertEquals(AudioSource.MIXED, rec.audioSource)
        assertTrue(rec.meetingMode)
    }

    // ---------- start / stop ----------

    @Test
    fun start_setsStatusProcessing() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        repo.start(rec.id)
        assertEquals(RecordingStatus.PROCESSING, db.recordingDao().getRecordingById(rec.id)?.status)
    }

    @Test
    fun stop_setsStatusCompleted() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        repo.stop(rec.id)
        assertEquals(RecordingStatus.COMPLETED, db.recordingDao().getRecordingById(rec.id)?.status)
    }

    @Test
    fun start_missingRecording_returnsNull() = runBlocking {
        assertNull(repo.start(999L))
    }

    @Test
    fun stop_missingRecording_returnsNull() = runBlocking {
        assertNull(repo.stop(999L))
    }

    @Test
    fun pauseResume_existingRecording_noThrow() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        repo.pause(rec.id)
        repo.resume(rec.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pause_missingRecording_throws() = runBlocking {
        repo.pause(999L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resume_missingRecording_throws() = runBlocking {
        repo.resume(999L)
    }

    // ---------- store ----------

    @Test
    fun store_savesFileAndUpdatesAudioPath() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        val data = byteArrayOf(1, 2, 3, 4)

        val locator = repo.store(rec.id, "meeting.wav", data)

        assertNotNull(locator)
        assertTrue(locator!!.startsWith("recordings/rec_"))
        assertTrue(locator.endsWith(".wav"))
        assertTrue("文件应已落盘", data.contentEquals(storage.readFile(locator)!!))
        assertEquals("audioPath 应更新为 locator", locator, db.recordingDao().getRecordingById(rec.id)?.audioPath)
    }

    @Test
    fun store_missingRecording_returnsNullWithoutWriting() = runBlocking {
        assertNull(repo.store(999L, "x.wav", byteArrayOf(1)))
    }

    // ---------- addAttachment ----------

    @Test
    fun addAttachment_storesFileAndInsertsRow() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        val data = byteArrayOf(7, 8, 9)

        val att = repo.addAttachment(
            recordingId = rec.id,
            fileName = "photo.jpg",
            data = data,
            timestamp = 3.5,
            source = AttachmentSource.CAMERA,
        )

        assertNotNull(att)
        assertTrue(att!!.id > 0)
        assertTrue(att.storageLocator.startsWith("attachments/att_"))
        assertTrue(att.storageLocator.endsWith(".jpg"))
        assertEquals(3.5, att.timestamp, 1e-9)
        assertEquals(AttachmentSource.CAMERA, att.source)
        assertTrue("附件文件应已落盘", data.contentEquals(storage.readFile(att.storageLocator)!!))
        assertEquals("DB 应插入附件行", 1, db.recordingDao().getAttachmentsForRecording(rec.id).size)
    }

    @Test
    fun addAttachment_missingRecording_returnsNull() = runBlocking {
        assertNull(repo.addAttachment(999L, "p.jpg", byteArrayOf(1), timestamp = 0.0))
    }

    // ---------- finalizeSegmented ----------

    @Test
    fun finalizeSegmented_mergesSegmentFilesAndCleansUp() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        // 模拟引擎产物：两段合法 WAV（44 字节头 + PCM），经 DAO 落段行
        val pcm0 = ByteArray(1600) { 1 } // 0.1s @16kHz
        val pcm1 = ByteArray(3200) { 2 } // 0.2s @16kHz
        val seg0Locator = storage.saveSegment(rec.id, 0, wavBytes(pcm0))
        val seg1Locator = storage.saveSegment(rec.id, 1, wavBytes(pcm1))
        db.segmentDao().insert(
            RecordingAudioSegmentEntity(
                recordingId = rec.id,
                segmentIndex = 0,
                audioPath = seg0Locator,
                startTime = 0.0,
                endTime = 0.1,
                duration = 0.1,
                createdAt = 1_752_000_000_000L,
            ),
        )
        db.segmentDao().insert(
            RecordingAudioSegmentEntity(
                recordingId = rec.id,
                segmentIndex = 1,
                audioPath = seg1Locator,
                startTime = 0.1,
                endTime = 0.3,
                duration = 0.2,
                createdAt = 1_752_000_000_000L,
            ),
        )

        val merged = repo.finalizeSegmented(rec.id, totalDuration = 0.3)

        assertNotNull(merged)
        assertTrue(merged!!.startsWith("recordings/rec_"))
        val mergedBytes = storage.readFile(merged)!!
        // 合并文件 = 44 字节头 + 两段 PCM 拼接
        assertEquals(WavFileWriter.HEADER_SIZE + pcm0.size + pcm1.size, mergedBytes.size)
        val payload = mergedBytes.copyOfRange(WavFileWriter.HEADER_SIZE, mergedBytes.size)
        assertTrue("PCM 载荷应为两段拼接", payload.contentEquals(pcm0 + pcm1))
        assertEquals("audioPath 应更新为合并文件", merged, db.recordingDao().getRecordingById(rec.id)?.audioPath)
        assertFalse("段文件应已清理", storage.exists(seg0Locator))
        assertFalse("段文件应已清理", storage.exists(seg1Locator))
    }

    @Test
    fun finalizeSegmented_noSegments_returnsNull() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        assertNull(repo.finalizeSegmented(rec.id, totalDuration = 0.0))
    }

    @Test
    fun finalizeSegmented_missingRecording_returnsNull() = runBlocking {
        assertNull(repo.finalizeSegmented(999L, totalDuration = 0.0))
    }

    // ---------- delete ----------

    @Test
    fun delete_removesRowAndCascadesFiles() = runBlocking {
        val rec = repo.create(title = null, audioSource = null, meetingMode = false)
        val recLocator = repo.store(rec.id, "a.wav", byteArrayOf(1))!!
        val segLocator = storage.saveSegment(rec.id, 0, byteArrayOf(2))
        val att = repo.addAttachment(rec.id, "p.jpg", byteArrayOf(3), timestamp = 1.0)!!

        repo.delete(rec.id)

        assertNull("DB 行应删除", db.recordingDao().getRecordingById(rec.id))
        assertTrue("chunks/segments/attachments 应级联删除", db.recordingDao().getAttachmentsForRecording(rec.id).isEmpty())
        assertFalse("录音文件应删除", storage.exists(recLocator))
        assertFalse("段文件应删除", storage.exists(segLocator))
        assertFalse("附件文件应删除", storage.exists(att.storageLocator))
    }

    @Test
    fun delete_missingRecording_isNoop() = runBlocking {
        repo.delete(999L) // 不抛异常
    }

    // ---------- 工具 ----------

    /** 用 WavFileWriter 构造合法 WAV 字节（44 字节头 + PCM，大小字段回填）。 */
    private fun wavBytes(pcm: ByteArray): ByteArray {
        val file = tmp.newFile("seg.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, WavFileWriter.DEFAULT_SAMPLE_RATE, WavFileWriter.DEFAULT_CHANNELS, WavFileWriter.DEFAULT_BITS_PER_SAMPLE)
            raf.write(pcm)
            WavFileWriter.finalize(raf, pcm.size)
        }
        return file.readBytes()
    }
}