package com.example.ai_recorder_android.ui.screens

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.cloud.asr.SiliconFlowAsrConnector
import com.example.ai_recorder_android.cloud.asr.WavDuration
import com.example.ai_recorder_android.core.Result
import com.example.ai_recorder_android.core.runCatchingResult
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import com.example.ai_recorder_android.data.repo.RecordingRepository
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.ui.components.GradientPrimaryButton
import com.example.ai_recorder_android.ui.components.SectionCard
import com.example.ai_recorder_android.ui.components.formatDuration
import com.example.ai_recorder_android.ui.theme.Spacing
import com.example.ai_recorder_android.ui.theme.tabularNums
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 已选中的音频文件（复制到应用缓存目录后的本地副本 + 展示元数据）。
 */
private data class SelectedAudio(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val durationSeconds: Double?,
)

/**
 * 文件上传转写页（Metis S2.2，对齐 PC `pc_api/gui/pages/file_upload_page.py`）：
 *
 * 1. 「选择音频文件」→ 系统文件选择器（ACTION_OPEN_DOCUMENT，仅 audio*，不收视频）；
 * 2. 选中后复制到应用缓存目录（contentResolver.openInputStream → outputStream.copyTo），
 *    展示文件信息（名称 / 大小 / 时长），并做 25MB 预检（复用 todo 12 连接器上限）；
 * 3. 「开始转写」→ 创建 Recording（标题 `上传: <文件名>`，对齐 PC create_recording）→
 *    原文件落库（store_audio）→ free 档 SiliconFlow 转写 → saveTranscription 落库；
 * 4. 转写中显示进度条，完成后展示转写文本；
 * 5. 错误统一走 [Result] 包装 + Snackbar 展示（Metis M5.14）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileUploadScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 依赖就地构造（MainActivity 不注入，保持 NavHost 调用签名不变）
    val db = remember { AppDatabase.getInstance(context) }
    val repository = remember {
        RecordingRepository(
            recordingDao = db.recordingDao(),
            attachmentDao = db.attachmentDao(),
            storage = StorageService.fromContext(context),
        )
    }
    val connector = remember { SiliconFlowAsrConnector() }

    var selected by remember { mutableStateOf<SelectedAudio?>(null) }
    var isTranscribing by remember { mutableStateOf(false) }
    var transcribed by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 错误消息 → Snackbar（Metis M5.14 统一错误展示）
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            errorMessage = null
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = runCatchingResult {
                    withContext(Dispatchers.IO) { copyToTempDir(context, uri) }
                }) {
                    is Result.Success -> {
                        val file = result.data
                        // 25MB 预检（todo 12 上限）：超限拒绝并提示
                        val oversizeMessage = withContext(Dispatchers.IO) {
                            if (file.length() > SiliconFlowAsrConnector.MAX_FILE_SIZE_BYTES) {
                                file.delete()
                                "文件超过 25MB 上限（${formatFileSize(file.length())}），请选择更小的音频文件"
                            } else {
                                selected?.file?.delete() // 清理上一个临时副本
                                null
                            }
                        }
                        if (oversizeMessage != null) {
                            errorMessage = oversizeMessage
                        } else {
                            selected = withContext(Dispatchers.IO) {
                                SelectedAudio(
                                    file = file,
                                    displayName = queryDisplayName(context, uri) ?: file.name,
                                    sizeBytes = file.length(),
                                    durationSeconds = queryDurationSeconds(context, file),
                                )
                            }
                            transcribed = false
                            resultText = null
                        }
                    }
                    is Result.Error -> errorMessage = result.message
                }
            }
        }
    }

    // 品牌顶栏：常态与页面背景同色（沉浸），滚动后浮出 surfaceContainer 层级（对齐 DetailScreen）
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("文件转写") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.page),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // 头部说明卡片（对齐 PC 头部卡片）
            SectionCard {
                Text(
                    text = "音频文件转写",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "上传本地音频文件（WAV / MP3 / M4A / OGG 等，≤25MB），" +
                        "选择后自动转写并保存为录音记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 选择文件
            FilledTonalButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                enabled = !isTranscribing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (selected == null) "选择音频文件" else "重新选择音频文件")
            }

            // 文件信息
            selected?.let { audio ->
                SectionCard {
                    Text(
                        text = audio.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        FileInfoItem(label = "大小", value = formatFileSize(audio.sizeBytes))
                        FileInfoItem(
                            label = "时长",
                            value = audio.durationSeconds?.let { formatDuration(it) } ?: "未知",
                        )
                    }
                }
            }

            // 主 CTA：开始转写（对齐 PC：转写完成后禁用，直到重新选文件）
            GradientPrimaryButton(
                text = "开始转写",
                onClick = {
                    val audio = selected ?: return@GradientPrimaryButton
                    scope.launch {
                        isTranscribing = true
                        resultText = null
                        when (val result = runCatchingResult {
                            transcribeAndSave(repository, connector, db, audio)
                        }) {
                            is Result.Success -> {
                                resultText = result.data
                                transcribed = true
                            }
                            is Result.Error -> errorMessage = result.message
                        }
                        isTranscribing = false
                    }
                },
                enabled = selected != null && !isTranscribing && !transcribed,
            )

            // 转写进度
            if (isTranscribing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在转写，请稍候…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 转写结果
            resultText?.let { text ->
                SectionCard {
                    Text(
                        text = "转写结果",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = text.ifBlank { "（未识别到语音内容）" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** 文件信息行（标签 + 值，数值用等宽数字排版）。 */
@Composable
private fun FileInfoItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.tabularNums(),
        )
    }
}

// ---------- 转写 + 落库 ----------

/**
 * 转写并落库（对齐 PC `_on_start_transcribe`）：
 * 1. `create_recording(title="上传: <文件名>")` → PENDING 行；
 * 2. `store_audio`：原文件字节存入 StorageService 并更新 audioPath；
 * 3. free 档 SiliconFlow 转写（连接器内部再做 25MB 预检）；
 * 4. `save_transcription`：文本 + 分段 chunks 落库（置 COMPLETED）。
 *
 * 返回转写文本（供 UI 展示）。
 */
private suspend fun transcribeAndSave(
    repository: RecordingRepository,
    connector: SiliconFlowAsrConnector,
    db: AppDatabase,
    audio: SelectedAudio,
): String = withContext(Dispatchers.IO) {
    val rec = repository.create(
        title = "上传: ${audio.displayName}",
        audioSource = null,
        meetingMode = false,
    )
    repository.store(rec.id, audio.displayName, audio.file.readBytes())

    val response = connector.transcribe(audio.file)
    val now = System.currentTimeMillis()
    val chunks = response.segments
        .filter { it.text.isNotBlank() }
        .map {
            TranscriptChunkEntity(
                recordingId = rec.id,
                chunkIndex = 0, // DAO saveTranscription 会按 0..n-1 重排
                content = it.text,
                startTime = it.start,
                endTime = it.end,
                createdAt = now,
            )
        }
    db.recordingDao().saveTranscription(rec.id, response.text, chunks, response.duration)
    response.text
}

// ---------- 文件复制 / 元数据 ----------

/** 将 content Uri 复制到应用缓存目录 `cacheDir/uploads/`，返回本地副本。 */
private fun copyToTempDir(context: Context, uri: Uri): File {
    val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
    val name = queryDisplayName(context, uri) ?: "upload_${System.currentTimeMillis()}"
    val ext = name.substringAfterLast('.', "").ifBlank { "wav" }
    val tempFile = File(dir, "upload_${System.currentTimeMillis()}.$ext")
    context.contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("无法读取所选文件")
    return tempFile
}

/** 查询 content Uri 的显示文件名（OpenableColumns.DISPLAY_NAME）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

/**
 * 查询音频时长（秒）：
 * - WAV：复用 [WavDuration]（纯 JVM 解析头，todo 15 已有）；
 * - 其他格式（MP3/M4A/OGG 等）：MediaMetadataRetriever。
 * 解析失败返回 null（UI 显示「未知」，不阻塞转写）。
 */
private fun queryDurationSeconds(context: Context, file: File): Double? {
    if (file.extension.equals("wav", ignoreCase = true)) {
        return try {
            WavDuration.parseSeconds(file)
        } catch (_: Exception) {
            null
        }
    }
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        retriever.release()
        durationMs?.div(1000.0)
    } catch (_: Exception) {
        null
    }
}

// ---------- 格式化 ----------

/** 文件大小格式化：B / KB / MB。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
