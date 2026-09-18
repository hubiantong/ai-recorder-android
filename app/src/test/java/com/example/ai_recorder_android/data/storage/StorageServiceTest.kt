package com.example.ai_recorder_android.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * StorageService 纯 JVM 单测：临时目录注入根目录，不依赖 Android 环境。
 * 覆盖：写入→读取→删除、删除不存在返回 false 不抛异常、级联删除、路径穿越防护。
 */
class StorageServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newService(): StorageService = StorageService(tmp.newFolder("storage"))

    // ---- 写入 / 读取 ----

    @Test
    fun saveRecording_writesFileAndReturnsRelativeLocator() {
        val service = newService()
        val data = byteArrayOf(1, 2, 3, 4)
        val locator = service.saveRecording("meeting.wav", data)

        assertTrue(locator.startsWith("recordings/rec_"))
        assertTrue(locator.endsWith(".wav"))
        assertFalse(locator.contains("\\"))
        assertFalse(File(locator).isAbsolute)

        val file = service.resolvePath(locator)
        assertTrue(file.isFile)
        assertTrue(file.path.startsWith(tmp.root.canonicalPath))
        assertTrue(data.contentEquals(service.readFile(locator)!!))
    }

    @Test
    fun saveRecording_generatesUniqueNames() {
        val service = newService()
        val a = service.saveRecording("a.wav", byteArrayOf(1))
        val b = service.saveRecording("b.wav", byteArrayOf(2))
        assertNotEquals(a, b)
    }

    @Test
    fun saveSegment_usesRecordingIdAndIndexInName() {
        val service = newService()
        val locator = service.saveSegment(42L, 0, byteArrayOf(9, 9))
        assertEquals("segments/seg_42_0.wav", locator)
        assertTrue(service.readFile(locator)!!.contentEquals(byteArrayOf(9, 9)))
    }

    @Test
    fun saveAttachment_usesUuidAndKeepsExtension() {
        val service = newService()
        val locator = service.saveAttachment("photo.jpg", byteArrayOf(7))
        assertTrue(locator.startsWith("attachments/att_"))
        assertTrue(locator.endsWith(".jpg"))
        assertTrue(service.readFile(locator)!!.contentEquals(byteArrayOf(7)))
    }

    @Test
    fun readFile_missingLocator_returnsNull() {
        val service = newService()
        assertNull(service.readFile("recordings/rec_missing.wav"))
    }

    // ---- 删除 ----

    @Test
    fun deleteFile_existing_returnsTrueAndRemoves() {
        val service = newService()
        val locator = service.saveRecording("x.wav", byteArrayOf(1))
        assertTrue(service.deleteFile(locator))
        assertFalse(service.exists(locator))
        assertNull(service.readFile(locator))
    }

    @Test
    fun deleteFile_missing_returnsFalseWithoutThrowing() {
        val service = newService()
        assertFalse(service.deleteFile("recordings/rec_never.wav"))
        assertFalse(service.deleteFile("segments/seg_1_0.wav"))
        assertFalse(service.deleteFile("attachments/att_never.jpg"))
    }

    // ---- 级联删除 ----

    @Test
    fun deleteRecordingFiles_cascadesRecordingSegmentsAndAttachments() {
        val service = newService()
        val recLocator = service.saveRecording("r.wav", byteArrayOf(1))
        val seg0 = service.saveSegment(7L, 0, byteArrayOf(2))
        val seg1 = service.saveSegment(7L, 1, byteArrayOf(3))
        val attLocator = service.saveAttachment("p.jpg", byteArrayOf(4))
        val thumbLocator = service.saveAttachment("p_thumb.jpg", byteArrayOf(5))

        service.deleteRecordingFiles(7L, recLocator, listOf(attLocator, thumbLocator))

        assertFalse(service.exists(recLocator))
        assertFalse(service.exists(seg0))
        assertFalse(service.exists(seg1))
        assertFalse(service.exists(attLocator))
        assertFalse(service.exists(thumbLocator))
    }

    @Test
    fun deleteRecordingFiles_keepsOtherRecordingsSegments() {
        val service = newService()
        val otherSeg = service.saveSegment(99L, 0, byteArrayOf(1))
        service.saveSegment(7L, 0, byteArrayOf(2))

        service.deleteRecordingFiles(7L)

        assertTrue(service.exists(otherSeg))
    }

    @Test
    fun deleteRecordingFiles_withoutLocators_stillDeletesSegments() {
        val service = newService()
        val seg = service.saveSegment(3L, 2, byteArrayOf(1))
        service.deleteRecordingFiles(3L)
        assertFalse(service.exists(seg))
    }

    // ---- 路径穿越防护 ----

    @Test(expected = IllegalArgumentException::class)
    fun resolvePath_rejectsTraversal() {
        val service = newService()
        service.resolvePath("../evil.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolvePath_rejectsAbsolutePath() {
        val service = newService()
        service.resolvePath(File(tmp.root, "outside.wav").absolutePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolvePath_rejectsRootedPath() {
        val service = newService()
        service.resolvePath("/etc/passwd")
    }

    @Test
    fun resolvePath_normalizesBackslashes() {
        val service = newService()
        val locator = service.saveRecording("x.wav", byteArrayOf(1))
        val viaSlash = service.resolvePath(locator)
        val viaBackslash = service.resolvePath(locator.replace('/', '\\'))
        assertEquals(viaSlash, viaBackslash)
    }
}