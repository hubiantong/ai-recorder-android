package com.example.ai_recorder_android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.example.ai_recorder_android.data.db.DescriptionStatus
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.service.PhotoAttachmentManager
import com.example.ai_recorder_android.ui.components.GradientPrimaryButton
import com.example.ai_recorder_android.ui.theme.Spacing
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 拍照附件保存状态。 */
sealed interface PhotoUiState {
    data object Idle : PhotoUiState
    data object Saving : PhotoUiState
    data class Saved(val attachment: RecordingAttachmentEntity) : PhotoUiState
    data class Failed(val message: String) : PhotoUiState
}

/** VLM 描述生成状态。 */
sealed interface DescriptionUiState {
    data object Idle : DescriptionUiState
    data object Describing : DescriptionUiState
    data class Done(val description: String) : DescriptionUiState
    data class Failed(val message: String) : DescriptionUiState
}

/**
 * 拍照流程控制器：封装 CAMERA 权限请求 → TakePicture 启动系统相机 →
 * 保存附件 + 缩略图 + VLM 描述 的完整流程，供任意按钮触发（[launch]），
 * 状态经 [photoState] / [descriptionState] 供 UI 展示。
 */
class PhotoCaptureController(
    val launch: () -> Unit,
    val photoState: State<PhotoUiState>,
    val descriptionState: State<DescriptionUiState>,
)

/** 装配 [PhotoAttachmentManager]（AppDatabase 单例 + StorageService + coordinator + VLM）。 */
@Composable
fun rememberPhotoAttachmentManager(): PhotoAttachmentManager {
    val context = LocalContext.current.applicationContext
    return remember(context) { PhotoAttachmentManager.fromContext(context) }
}

/**
 * 创建拍照流程控制器（todo 23）：系统相机 Intent（`MediaStore.ACTION_IMAGE_CAPTURE`，
 * 经 [ActivityResultContracts.TakePicture]）。相机取消/失败不产生附件、不崩溃（QA failure 场景）。
 */
@Composable
fun rememberPhotoCaptureController(manager: PhotoAttachmentManager): PhotoCaptureController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // 显式 MutableState（不用 by 委托）：需把 State 对象本身暴露给 PhotoCaptureController
    val photoState = remember { mutableStateOf<PhotoUiState>(PhotoUiState.Idle) }
    val descriptionState = remember { mutableStateOf<DescriptionUiState>(DescriptionUiState.Idle) }
    var outputFile by remember { mutableStateOf<File?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = outputFile
        if (!success || file == null) {
            // 相机取消/失败：无附件不崩
            photoState.value = PhotoUiState.Idle
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            photoState.value = PhotoUiState.Saving
            descriptionState.value = DescriptionUiState.Idle
            try {
                // 拍照墙钟时刻：相机返回即最近可观测的拍摄时刻（对齐 PC photo_timestamp 语义）
                val wallClockMs = System.currentTimeMillis()
                val attachment = withContext(Dispatchers.IO) { manager.savePhoto(file, wallClockMs) }
                if (attachment == null) {
                    photoState.value = PhotoUiState.Failed("保存附件失败：录音不存在")
                    return@launch
                }
                photoState.value = PhotoUiState.Saved(attachment)
                descriptionState.value = DescriptionUiState.Describing
                val updated = manager.describeAttachment(attachment)
                descriptionState.value = when (updated.descriptionStatus) {
                    DescriptionStatus.COMPLETED -> DescriptionUiState.Done(updated.description.orEmpty())
                    else -> DescriptionUiState.Failed("描述生成失败")
                }
            } catch (e: Exception) {
                photoState.value = PhotoUiState.Failed(e.message ?: "保存照片失败")
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            val file = manager.createCameraOutputFile(context.cacheDir)
            outputFile = file
            takePictureLauncher.launch(manager.cameraOutputUri(context, file))
        }
    }

    fun launchCamera() {
        val file = manager.createCameraOutputFile(context.cacheDir)
        outputFile = file
        takePictureLauncher.launch(manager.cameraOutputUri(context, file))
    }

    return PhotoCaptureController(
        launch = {
            if (hasCameraPermission) {
                launchCamera()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        photoState = photoState,
        descriptionState = descriptionState,
    )
}

/**
 * 拍照附件屏幕（todo 23）：独立入口，含拍照按钮 + 结果展示。
 * 录音主页的拍照按钮见 [RecordingScreen]（同一控制器流程）。
 */
@Composable
fun PhotoCaptureScreen(modifier: Modifier = Modifier) {
    val manager = rememberPhotoAttachmentManager()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.page),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "拍照附件", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "拍照后自动保存为录音附件并生成 AI 描述",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        PhotoCaptureSection(manager = manager, modifier = Modifier.padding(top = Spacing.xl))
    }
}

/** 拍照按钮 + 结果展示（独立屏幕用；录音主页直接复用控制器）。 */
@Composable
fun PhotoCaptureSection(
    manager: PhotoAttachmentManager,
    modifier: Modifier = Modifier,
) {
    val controller = rememberPhotoCaptureController(manager)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GradientPrimaryButton(
            text = "拍照",
            onClick = controller.launch,
            enabled = controller.photoState.value !is PhotoUiState.Saving,
        )
        PhotoCaptureResult(controller = controller, manager = manager)
    }
}

/** 拍照结果展示：缩略图 + 保存状态 + VLM 描述状态。 */
@Composable
fun PhotoCaptureResult(
    controller: PhotoCaptureController,
    manager: PhotoAttachmentManager,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when (val state = controller.photoState.value) {
            PhotoUiState.Idle -> Unit

            PhotoUiState.Saving -> {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                Text(
                    text = "保存照片中…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            is PhotoUiState.Saved -> {
                val thumbFile = manager.thumbnailFile(state.attachment)
                if (thumbFile != null) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = "照片缩略图",
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .size(160.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                }
                Text(
                    text = "已保存（时间轴 ${formatTimestamp(state.attachment.timestamp)}s）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                when (val desc = controller.descriptionState.value) {
                    DescriptionUiState.Idle -> Unit

                    DescriptionUiState.Describing -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "AI 描述生成中…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    is DescriptionUiState.Done -> Text(
                        text = desc.description,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    is DescriptionUiState.Failed -> Text(
                        text = desc.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            is PhotoUiState.Failed -> Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun formatTimestamp(seconds: Double): String =
    String.format(Locale.US, "%.1f", seconds)