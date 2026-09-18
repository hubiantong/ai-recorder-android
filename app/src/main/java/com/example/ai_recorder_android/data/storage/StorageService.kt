package com.example.ai_recorder_android.data.storage

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 本地文件存储服务（对齐 PC `core/storage/service.py` 的 StorageService 语义）。
 *
 * 根目录为应用内部目录（生产传 `context.filesDir`，测试传临时目录），
 * 内部按用途分三个子目录：
 * - `recordings/`：整段录音 WAV，文件名 `rec_{uuid}.wav`
 * - `segments/`：VAD 分段 WAV，文件名 `seg_{rid}_{idx}.wav`
 * - `attachments/`：照片/截图附件，文件名 `att_{uuid}.{ext}`
 *
 * DB 中只存**相对路径 locator**（如 `recordings/rec_xxx.wav`），不存绝对路径；
 * 读取时以 root 为基准 resolve，并做路径穿越防护（normalize 后必须仍在 root 内）。
 */
class StorageService(private val rootDir: File) {

    init {
        require(rootDir.isDirectory || rootDir.mkdirs()) { "无法创建存储根目录: $rootDir" }
        File(rootDir, RECORDINGS_DIR).mkdirs()
        File(rootDir, SEGMENTS_DIR).mkdirs()
        File(rootDir, ATTACHMENTS_DIR).mkdirs()
    }

    /**
     * 保存整段录音，返回相对路径 locator（`recordings/rec_{uuid}.{ext}`）。
     * fileName 仅用于提取扩展名（默认 wav），存储名始终为唯一 `rec_{uuid}` 前缀。
     */
    fun saveRecording(fileName: String, data: ByteArray): String {
        val locator = "${RECORDINGS_DIR}/rec_${UUID.randomUUID()}.${extensionOf(fileName)}"
        write(locator, data)
        return locator
    }

    /** 保存 VAD 分段音频，返回相对路径 locator（`segments/seg_{rid}_{idx}.wav`）。 */
    fun saveSegment(recordingId: Long, index: Int, data: ByteArray): String {
        require(index >= 0) { "segment index 必须 >= 0: $index" }
        val locator = "${SEGMENTS_DIR}/seg_${recordingId}_${index}.wav"
        write(locator, data)
        return locator
    }

    /** 保存附件（照片/截图），返回相对路径 locator（`attachments/att_{uuid}.{ext}`）。 */
    fun saveAttachment(fileName: String, data: ByteArray): String {
        val locator = "${ATTACHMENTS_DIR}/att_${UUID.randomUUID()}.${extensionOf(fileName)}"
        write(locator, data)
        return locator
    }

    /** 读取文件内容；locator 不存在返回 null。 */
    fun readFile(locator: String): ByteArray? {
        val file = resolvePath(locator)
        return if (file.isFile) file.readBytes() else null
    }

    /** 删除文件；不存在返回 false 且不抛异常。 */
    fun deleteFile(locator: String): Boolean {
        val file = resolvePath(locator)
        return file.isFile && file.delete()
    }

    /**
     * 级联删除某录音的全部关联文件：
     * - 录音本体（recordingLocator，来自 Recording.audioPath）
     * - 所有分段 `segments/seg_{rid}_*.wav`（按文件名前缀匹配，rid 编码在文件名中）
     * - 附件本体与缩略图（attachmentLocators，来自 RecordingAttachment.storageLocator/thumbnailLocator）
     *
     * 录音本体与附件 locator 无法从 recordingId 反推（文件名是 uuid），
     * 需由调用方（todo 16 RecordingService）从 DB 实体传入；分段可仅凭 recordingId 删除。
     */
    fun deleteRecordingFiles(
        recordingId: Long,
        recordingLocator: String? = null,
        attachmentLocators: List<String> = emptyList(),
    ) {
        recordingLocator?.let { deleteFile(it) }
        File(rootDir, SEGMENTS_DIR).listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("seg_${recordingId}_")) {
                file.delete()
            }
        }
        attachmentLocators.forEach { deleteFile(it) }
    }

    /** 文件是否存在。 */
    fun exists(locator: String): Boolean = resolvePath(locator).isFile

    /**
     * 将相对路径 locator 解析为 root 下的绝对 File。
     * 防路径穿越：拒绝绝对路径（`/` 开头或盘符），normalize（canonical）后
     * 必须仍在 root 内，否则抛 [IllegalArgumentException]。
     */
    fun resolvePath(locator: String): File {
        require(locator.isNotBlank()) { "locator 不能为空" }
        val normalized = locator.replace('\\', '/')
        if (normalized.startsWith("/") || DRIVE_LETTER_PREFIX.matches(normalized)) {
            throw IllegalArgumentException("locator 不能是绝对路径: $locator")
        }
        val root = rootDir.canonicalFile
        val candidate = File(root, normalized.trim('/')).canonicalFile
        if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) {
            throw IllegalArgumentException("locator 越出存储根目录: $locator")
        }
        return candidate
    }

    private fun write(locator: String, data: ByteArray) {
        val file = resolvePath(locator)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }

    private fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val ext = if (dot in 0 until fileName.length - 1) fileName.substring(dot + 1) else ""
        return ext.filter { it.isLetterOrDigit() }.ifEmpty { "wav" }
    }

    companion object {
        const val RECORDINGS_DIR = "recordings"
        const val SEGMENTS_DIR = "segments"
        const val ATTACHMENTS_DIR = "attachments"

        private val DRIVE_LETTER_PREFIX = Regex("^[A-Za-z]:/.*")

        /** 生产入口：以应用内部 filesDir 为根。 */
        fun fromContext(context: Context): StorageService = StorageService(context.filesDir)
    }
}