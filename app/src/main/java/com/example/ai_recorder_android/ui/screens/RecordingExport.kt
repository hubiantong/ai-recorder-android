package com.example.ai_recorder_android.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.ai_recorder_android.data.db.RecordingEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导出临时目录（cacheDir 下，FileProvider 映射见 file_paths.xml）。 */
private const val EXPORT_DIR = "export"

/** 导出副本保留时长：仅清理过期文件，避免删掉其他分享流程仍在读取的副本。 */
private const val EXPORT_CACHE_TTL_MS = 60 * 60 * 1000L

/** 文件名非法字符（路径分隔符/系统保留字符/空白统一压成下划线）。 */
private val INVALID_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\s]+")

/**
 * 导出录音音频/图片：复制为「标题_时间」命名的可读副本（cacheDir/export），
 * 再唤起系统分享面板——用户可「保存到文件」（落到其他设备可访问的目录/云盘）、
 * 蓝牙、附近分享等，实现跨设备使用。
 *
 * 原始文件为内部 uuid 命名，故先复制重命名；隔离副本仅做过期清理（保留期内的文件
 * 可能仍被分享接收端读取）。
 * 非主线程调用安全（文件复制切 IO）。
 *
 * @param includeAudio 是否导出录音音频
 * @param imageFiles 已解析存在的图片附件文件（导出全部）
 * @return true 已唤起分享；false 无可导出文件（源文件缺失）
 */
suspend fun exportRecordingMedia(
    context: Context,
    recording: RecordingEntity,
    audioFile: File?,
    imageFiles: List<File>,
    includeAudio: Boolean,
    chooserTitle: String,
): Boolean {
    val files = withContext(Dispatchers.IO) {
        prepareExportFiles(context, recording, audioFile, imageFiles, includeAudio)
    }
    if (files.isEmpty()) return false

    val authority = "${context.packageName}.fileprovider"
    val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(files.first())
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = commonMimeType(files)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData 逐条授予读权限：部分接收端（文件管理器/IM）只读取 clipData 中的 URI
    intent.clipData = ClipData.newRawUri("导出文件", uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
    return true
}

/** 复制带可读文件名的导出副本；无有效源文件返回空列表。 */
private fun prepareExportFiles(
    context: Context,
    recording: RecordingEntity,
    audioFile: File?,
    imageFiles: List<File>,
    includeAudio: Boolean,
): List<File> {
    val dir = File(context.cacheDir, EXPORT_DIR)
    dir.mkdirs()
    // 清理过期副本，避免缓存目录无限累积（保留期内不删，防止打断接收端读取）
    val cutoff = System.currentTimeMillis() - EXPORT_CACHE_TTL_MS
    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }

    val baseName = exportBaseName(recording)
    val exported = mutableListOf<File>()
    if (includeAudio && audioFile != null && audioFile.isFile) {
        val ext = audioFile.extension.ifBlank { "wav" }
        copyToExport(audioFile, File(dir, "$baseName.$ext"))?.let { exported += it }
    }
    imageFiles.forEachIndexed { index, image ->
        if (!image.isFile) return@forEachIndexed
        val ext = image.extension.ifBlank { "jpg" }
        copyToExport(image, File(dir, "${baseName}_${index + 1}.$ext"))?.let { exported += it }
    }
    return exported
}

/** 导出文件名前缀：`标题_yyyyMMdd_HHmmss`（标题空则回退 录音_{id}）。 */
private fun exportBaseName(recording: RecordingEntity): String {
    val timestamp = Instant.ofEpochMilli(recording.createdAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    val title = recording.title
        ?.replace(INVALID_NAME_CHARS, "_")
        ?.trim('_')
        ?.take(40)
        ?.ifBlank { "录音_${recording.id}" }
        ?: "录音_${recording.id}"
    return "${title}_$timestamp"
}

private fun copyToExport(source: File, target: File): File? = try {
    source.copyTo(target, overwrite = true)
} catch (_: Exception) {
    null
}

/** 单文件 MIME（未识别回退通配类型，保证分享面板可用）。 */
private fun mimeTypeOf(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    else -> "*/*"
}

/** 多文件公共 MIME：同类聚合（图片统一 image 通配），音图混合走通配类型。 */
private fun commonMimeType(files: List<File>): String {
    val types = files.map { mimeTypeOf(it) }.distinct()
    return when {
        types.size == 1 -> types.first()
        types.all { it.startsWith("image/") } -> "image/*"
        else -> "*/*"
    }
}
