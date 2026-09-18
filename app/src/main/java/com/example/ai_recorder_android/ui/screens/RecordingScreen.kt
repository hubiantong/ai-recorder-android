package com.example.ai_recorder_android.ui.screens

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai_recorder_android.App
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.service.RecordingCoordinator
import com.example.ai_recorder_android.service.RecordingService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 录音界面 UI 状态机（todo 19）。
 *
 * 流转对齐 todo 16 [com.example.ai_recorder_android.service.RecordingCoordinator]：
 * `IDLE → RECORDING ⇄ PAUSED → STOPPED`（Coordinator 的 COMPLETED 在 UI 层命名为 STOPPED，
 * 表示一次会话结束、可再次开始）。
 */
enum class RecordingUiState { IDLE, RECORDING, PAUSED, STOPPED }

/** 实时转写气泡条目（id 供 LazyColumn key 使用）。 */
data class PartialTranscript(val id: Long, val text: String)

/**
 * 录音主页 ViewModel（todo 19，代码库首个 ViewModel）。
 *
 * **职责边界**：只持有 UI 状态（状态机/音量/转写气泡/档位），**不实现录音逻辑**——
 * 实际录音由共享 [RecordingCoordinator]（App 单例）+ [RecordingService] 编排，
 * 本类已接线：录音按钮 → RecordingService intent → 共享 RecordingCoordinator，
 * 音量电平经 [coordinator.levelListener] 回流（[onLevel]）。
 *
 * 构造注入 [Context] + [RecordingCoordinator] + [UserConfigRepository]
 * （无 DI 框架，生产装配见 [factory]）。
 */
class RecordingViewModel(
    private val context: Context,
    private val coordinator: RecordingCoordinator,
    private val userConfigRepository: UserConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingUiState.IDLE)
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    /** RMS 音量电平 0.0-1.0（Metis S2.3 降级版：音量条而非像素波形）。 */
    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    /** 实时转写 partial 气泡列表（realtime 档流式输出，todo 17 管线接线）。 */
    private val _partials = MutableStateFlow<List<PartialTranscript>>(emptyList())
    val partials: StateFlow<List<PartialTranscript>> = _partials.asStateFlow()

    /** 当前 ASR 档位（free/realtime/pro_lite/pro_pro），来自 UserConfig（DataStore）。 */
    private val _tier = MutableStateFlow("free")
    val tier: StateFlow<String> = _tier.asStateFlow()

    private var nextPartialId = 0L

    // 依赖清单无 lifecycle-viewmodel-ktx（viewModelScope 不可用），自建 scope 随 ViewModel 销毁
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // 音量电平回流：共享 coordinator 引擎线程回调 → UI 音量条
        coordinator.levelListener = { onLevel(it) }
        scope.launch {
            userConfigRepository.userConfig.collect { config -> _tier.value = config.asrTier }
        }
    }

    // ---------- 录音控制（已接线：按钮 → RecordingService intent → 共享 RecordingCoordinator） ----------

    /** 开始录音：IDLE/STOPPED → RECORDING。 */
    fun onStartClick() {
        val current = _uiState.value
        if (current != RecordingUiState.IDLE && current != RecordingUiState.STOPPED) return
        _partials.value = emptyList()
        _rmsLevel.value = 0f
        _uiState.value = RecordingUiState.RECORDING
        val outputDir = File(context.filesDir, "recordings").apply { mkdirs() }
        context.startForegroundService(RecordingService.startIntent(context, outputDir))
    }

    /** 暂停：RECORDING → PAUSED。 */
    fun onPauseClick() {
        if (_uiState.value != RecordingUiState.RECORDING) return
        _uiState.value = RecordingUiState.PAUSED
        context.startService(RecordingService.pauseIntent(context))
    }

    /** 继续：PAUSED → RECORDING。 */
    fun onResumeClick() {
        if (_uiState.value != RecordingUiState.PAUSED) return
        _uiState.value = RecordingUiState.RECORDING
        context.startService(RecordingService.resumeIntent(context))
    }

    /** 停止：RECORDING/PAUSED → STOPPED。 */
    fun onStopClick() {
        val current = _uiState.value
        if (current != RecordingUiState.RECORDING && current != RecordingUiState.PAUSED) return
        _uiState.value = RecordingUiState.STOPPED
        context.startService(RecordingService.stopIntent(context))
    }

    // ---------- 外部事件入口（onLevel 已接线；onPartialTranscript 待 todo 17） ----------

    /** 引擎 RMS 电平回调（0.0-1.0，越界钳制；经 Coordinator.levelListener 回流）。 */
    fun onLevel(rms: Double) {
        _rmsLevel.value = rms.toFloat().coerceIn(0f, 1f)
    }

    /** 实时转写 partial 追加（todo 17 TranscriptionPipeline → 本方法）。 */
    fun onPartialTranscript(text: String) {
        if (text.isBlank()) return
        _partials.value = _partials.value + PartialTranscript(nextPartialId++, text)
    }

    override fun onCleared() {
        coordinator.levelListener = null
        scope.cancel()
        super.onCleared()
    }

    companion object {
        /** 生产工厂：Context 装配共享 RecordingCoordinator（App 单例）+ UserConfigRepository。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                RecordingViewModel(
                    context = app,
                    coordinator = (app as App).recordingCoordinator,
                    userConfigRepository = UserConfigRepository.fromContext(app),
                )
            }
        }
    }
}

/**
 * 录音主页（todo 19）：音量指示条 + 录音控制 + 实时转写气泡 + 拍照入口 + 档位/设置入口。
 *
 * @param onPhotoClick 拍照入口附加回调（todo 23 相机流程已内建，此参数保留供导航等扩展）
 * @param onSettingsClick 设置入口（跳转 Routes.SETTINGS）
 */
@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel = viewModel(factory = RecordingViewModel.factory(LocalContext.current)),
    onPhotoClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val rmsLevel by viewModel.rmsLevel.collectAsState()
    val partials by viewModel.partials.collectAsState()
    val tier by viewModel.tier.collectAsState()

    // 录音权限请求：已授权时系统直接回调 granted，无需手动预检
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onStartClick()
        } else {
            Toast.makeText(
                context,
                "录音需要麦克风权限，若已拒绝请在系统设置中开启后重试",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // 拍照附件流程（todo 23）：相机 Intent → 保存附件 + 缩略图 + VLM 描述
    val photoManager = rememberPhotoAttachmentManager()
    val photoController = rememberPhotoCaptureController(photoManager)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 顶栏：档位（点击进设置）+ 设置齿轮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TierChip(tier = tier, onClick = onSettingsClick)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onSettingsClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "设置",
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 音量指示条（RMS 驱动，非像素波形）
        VolumeIndicator(rmsLevel = rmsLevel)

        Spacer(modifier = Modifier.height(16.dp))

        // 实时转写气泡区（占剩余空间）
        PartialTranscriptList(
            partials = partials,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 录音控制：开始/停止 + 暂停/继续（按状态机启停）
        RecordingControls(
            uiState = uiState,
            onStartClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onStopClick = viewModel::onStopClick,
            onPauseClick = viewModel::onPauseClick,
            onResumeClick = viewModel::onResumeClick,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 拍照入口（todo 23 相机流程；仅录音中可用——对齐 Coordinator.addAttachment 需活动会话）
        FilledTonalButton(
            onClick = {
                onPhotoClick()
                photoController.launch()
            },
            enabled = uiState == RecordingUiState.RECORDING || uiState == RecordingUiState.PAUSED,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("拍照")
        }
        PhotoCaptureResult(controller = photoController, manager = photoManager)
    }
}

/** 档位胶囊：展示当前 ASR tier，点击进入设置（tier 选择入口）。 */
@Composable
private fun TierChip(tier: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = "档位 · $tier",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 音量指示条：LinearProgressIndicator 由 RMS 电平（0.0-1.0）驱动。 */
@Composable
private fun VolumeIndicator(rmsLevel: Float) {
    Column {
        Text(
            text = "音量",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { rmsLevel },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )
    }
}

/** 实时转写气泡列表：圆角卡片样式；空态显示提示文案。 */
@Composable
private fun PartialTranscriptList(
    partials: List<PartialTranscript>,
    modifier: Modifier = Modifier,
) {
    if (partials.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "实时转写将显示在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(partials, key = { it.id }) { partial ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = partial.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 录音控制按钮组：按状态机启停（IDLE/STOPPED 可开始，RECORDING 可暂停/停止，PAUSED 可继续/停止）。 */
@Composable
private fun RecordingControls(
    uiState: RecordingUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
) {
    val canStart = uiState == RecordingUiState.IDLE || uiState == RecordingUiState.STOPPED
    val canStop = uiState == RecordingUiState.RECORDING || uiState == RecordingUiState.PAUSED
    val canPause = uiState == RecordingUiState.RECORDING
    val canResume = uiState == RecordingUiState.PAUSED

    Row(modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = onStartClick,
            enabled = canStart,
            modifier = Modifier.weight(1f),
        ) {
            Text("开始录音")
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onStopClick,
            enabled = canStop,
            modifier = Modifier.weight(1f),
        ) {
            Text("停止")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = onPauseClick,
            enabled = canPause,
            modifier = Modifier.weight(1f),
        ) {
            Text("暂停")
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onResumeClick,
            enabled = canResume,
            modifier = Modifier.weight(1f),
        ) {
            Text("继续")
        }
    }
}