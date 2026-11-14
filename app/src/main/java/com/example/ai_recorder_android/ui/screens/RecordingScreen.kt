package com.example.ai_recorder_android.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.ai_recorder_android.core.audio.RecordingSessionConfig
import com.example.ai_recorder_android.data.repo.UserConfig
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.ui.components.BrandVolumeBar
import com.example.ai_recorder_android.ui.components.PulsingDot
import com.example.ai_recorder_android.ui.theme.RecordingRed
import com.example.ai_recorder_android.service.RecordingCoordinator
import com.example.ai_recorder_android.service.RecordingPhase
import com.example.ai_recorder_android.service.RecordingService
import com.example.ai_recorder_android.service.RecordingSessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 实时转写气泡条目（id 供 LazyColumn key 使用）。 */
data class PartialTranscript(val id: Long, val text: String)

/**
 * 录音主页 ViewModel（todo 19，代码库首个 ViewModel）。
 *
 * **职责边界**：只持有 UI 展示状态（音量/转写气泡/档位），**不实现录音逻辑**——
 * 实际录音由共享 [RecordingCoordinator]（App 单例）+ [RecordingService] 编排。
 *
 * **状态来源**：录音会话状态直接取自 [RecordingCoordinator.sessionState]（[sessionState]），
 * 点击只发送服务命令、不做乐观更新；按钮可用性/文案完全由 Coordinator 的真实阶段驱动，
 * 启动失败（[RecordingSessionState.errorMessage]）也会回流到 UI。
 *
 * 构造注入 [Context] + [RecordingCoordinator] + [UserConfigRepository]
 * （无 DI 框架，生产装配见 [factory]）。
 */
class RecordingViewModel(
    private val context: Context,
    private val coordinator: RecordingCoordinator,
    private val userConfigRepository: UserConfigRepository,
) : ViewModel() {

    /** 录音会话真实状态（唯一来源：Coordinator，UI 不自行推断）。 */
    val sessionState: StateFlow<RecordingSessionState> = coordinator.sessionState

    /** RMS 音量电平 0.0-1.0（Metis S2.3 降级版：音量条而非像素波形）。 */
    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    /** 实时转写 partial 气泡列表（realtime 档流式输出，todo 17 管线接线）。 */
    private val _partials = MutableStateFlow<List<PartialTranscript>>(emptyList())
    val partials: StateFlow<List<PartialTranscript>> = _partials.asStateFlow()

    /** 当前用户配置（DataStore）；未加载完成前为 null。 */
    private val _userConfig = MutableStateFlow<UserConfig?>(null)
    val userConfig: StateFlow<UserConfig?> = _userConfig.asStateFlow()

    /** 当前 ASR 档位（free/realtime/pro_lite/pro_pro），来自 UserConfig（DataStore）。 */
    private val _tier = MutableStateFlow("free")
    val tier: StateFlow<String> = _tier.asStateFlow()

    private var nextPartialId = 0L

    // 依赖清单无 lifecycle-viewmodel-ktx（viewModelScope 不可用），自建 scope 随 ViewModel 销毁
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // 音量电平回流：共享 coordinator 引擎线程回调 → UI 音量条
        coordinator.levelListener = { onLevel(it) }
        // 实时转写回流：RecordingService（realtime 档）→ 主线程 → 字幕气泡
        coordinator.transcriptListener = { partial, text ->
            if (partial) onPartialTranscript(text) else onSentenceTranscript(text)
        }
        scope.launch {
            userConfigRepository.userConfig.collect { config ->
                _userConfig.value = config
                _tier.value = config.asrTier
            }
        }
    }

    // ---------- 录音控制（只发命令；状态由 Coordinator 回推） ----------

    /**
     * 开始录音：仅在 Coordinator 处于空闲/已完成/失败态时发送命令，不做乐观更新。
     *
     * 会话配置从 UserConfig 组装（[RecordingSessionConfig]）：
     * - 会议模式开启时按「默认音源」选择采集器（system/mixed 需 [resultCode]/[resultData]
     *   的 MediaProjection 授权结果，由 UI 授权弹窗提供）；
     * - 会议模式关闭时始终为麦克风录音（音源设置属于会议模式分组，不外溢到普通录音）。
     *
     * @param resultCode 系统音频授权结果码（普通麦克风录音忽略）
     * @param resultData 系统音频授权 Intent（普通麦克风录音忽略）
     */
    fun onStartClick(resultCode: Int = 0, resultData: Intent? = null) {
        scope.launch {
            val phase = sessionState.value.phase
            if (phase != RecordingPhase.IDLE && phase != RecordingPhase.COMPLETED && phase != RecordingPhase.ERROR) return@launch
            val config = userConfigRepository.userConfig.first()
            _partials.value = emptyList()
            _rmsLevel.value = 0f
            val outputDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val audioSource = if (config.meetingMode) {
                RecordingSessionConfig.sanitizeSource(config.meetingAudioSource)
            } else {
                RecordingSessionConfig.SOURCE_MIC
            }
            val sessionConfig = RecordingSessionConfig(
                meetingMode = config.meetingMode,
                audioSource = audioSource,
                projectionResultCode = resultCode,
                projectionResultData = resultData,
            )
            context.startForegroundService(RecordingService.startIntent(context, outputDir, sessionConfig))
        }
    }

    /** 暂停：仅在 Coordinator 真实处于 RECORDING 时发送。 */
    fun onPauseClick() {
        if (sessionState.value.phase != RecordingPhase.RECORDING) return
        context.startService(RecordingService.pauseIntent(context))
    }

    /** 继续：仅在 Coordinator 真实处于 PAUSED 时发送。 */
    fun onResumeClick() {
        if (sessionState.value.phase != RecordingPhase.PAUSED) return
        context.startService(RecordingService.resumeIntent(context))
    }

    /** 停止：仅在 Coordinator 真实存在活动会话时发送。 */
    fun onStopClick() {
        if (!sessionState.value.isActive) return
        // 会话结束，摘掉「正在识别」气泡（最终句由回调另行追加）
        _partials.value = _partials.value.filterNot { it.id == LIVE_PARTIAL_ID }
        context.startService(RecordingService.stopIntent(context))
    }

    // ---------- 外部事件入口（onLevel 已接线；转写回流已接线 realtime 档） ----------

    /** 引擎 RMS 电平回调（0.0-1.0，越界钳制；经 Coordinator.levelListener 回流）。 */
    fun onLevel(rms: Double) {
        _rmsLevel.value = rms.toFloat().coerceIn(0f, 1f)
    }

    /**
     * 实时转写中间结果（realtime 档流式 partial）：替换「正在识别」气泡（固定 id）。
     */
    fun onPartialTranscript(text: String) {
        if (text.isBlank()) return
        val list = _partials.value.toMutableList()
        val live = PartialTranscript(LIVE_PARTIAL_ID, text)
        val index = list.indexOfFirst { it.id == LIVE_PARTIAL_ID }
        if (index >= 0) list[index] = live else list.add(live)
        _partials.value = list
    }

    /** 实时转写最终句：移除「正在识别」气泡并追加定稿句。 */
    fun onSentenceTranscript(text: String) {
        _partials.value = _partials.value
            .filterNot { it.id == LIVE_PARTIAL_ID && text.isNotBlank() }
            .let { if (text.isBlank()) it else it + PartialTranscript(nextPartialId++, text) }
    }

    override fun onCleared() {
        coordinator.levelListener = null
        coordinator.transcriptListener = null
        scope.cancel()
        super.onCleared()
    }

    companion object {
        /** 「正在识别」气泡的固定 id（负数避免与定稿句 id 冲突）。 */
        private const val LIVE_PARTIAL_ID = -1L

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
 * 录音主页：音量指示条 + 录音控制 + 实时转写气泡 + 拍照/文件转写入口 + 档位/设置入口。
 *
 * @param onFileUploadClick 文件转写入口（跳转 Routes.FILE_UPLOAD）
 * @param onSettingsClick 设置入口（跳转 Routes.SETTINGS）
 */
@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel = viewModel(factory = RecordingViewModel.factory(LocalContext.current)),
    onFileUploadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by viewModel.sessionState.collectAsState()
    val rmsLevel by viewModel.rmsLevel.collectAsState()
    val partials by viewModel.partials.collectAsState()
    val tier by viewModel.tier.collectAsState()

    // 录音权限请求：已授权时系统直接回调 granted，无需手动预检
    val context = LocalContext.current
    val userConfig by viewModel.userConfig.collectAsState()

    // 启动/停止失败（Coordinator 真实状态回推）→ 用户可见提示；失败态可直接重试
    LaunchedEffect(session.errorMessage) {
        session.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // 系统音频授权（会议模式 system/mixed 音源）：授权结果透传给服务，
    // 服务先以含 MEDIA_PROJECTION 的前台类型启动，再由引擎 getMediaProjection
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onStartClick(result.resultCode, result.data)
        } else {
            Toast.makeText(context, "需要屏幕录制权限才能采集系统音频", Toast.LENGTH_LONG).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "录音需要麦克风权限，若已拒绝请在系统设置中开启后重试",
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        // 会议模式 + system/mixed 音源：先弹 MediaProjection 授权，再开始录音
        val config = userConfig ?: UserConfig()
        val source = if (config.meetingMode) {
            RecordingSessionConfig.sanitizeSource(config.meetingAudioSource)
        } else {
            RecordingSessionConfig.SOURCE_MIC
        }
        if (source != RecordingSessionConfig.SOURCE_MIC) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            viewModel.onStartClick()
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

        // 录音控制：开始/停止 + 暂停/继续（按 Coordinator 真实状态启停）
        RecordingControls(
            session = session,
            onStartClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onStopClick = viewModel::onStopClick,
            onPauseClick = viewModel::onPauseClick,
            onResumeClick = viewModel::onResumeClick,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 拍照（相机流程内建，仅录音中可用——对齐 Coordinator.addAttachment 需活动会话）
        // 与文件转写（恒可用）并列的快捷入口行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { photoController.launch() },
                enabled = session.isActive,
                modifier = Modifier.weight(1f),
            ) {
                Text("拍照")
            }
            FilledTonalButton(
                onClick = onFileUploadClick,
                modifier = Modifier.weight(1f),
            ) {
                Text("文件转写")
            }
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

/** 音量指示条：品牌渐变电平条（RMS 0.0-1.0 驱动，平滑过渡）。 */
@Composable
private fun VolumeIndicator(rmsLevel: Float) {
    Column {
        Text(
            text = "音量",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        BrandVolumeBar(level = rmsLevel, modifier = Modifier.fillMaxWidth())
    }
}

/** 实时转写气泡列表：品牌圆角卡片 + 进出场动画；空态显示提示文案。 */
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
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(180),
                    fadeOutSpec = tween(180),
                ),
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

/**
 * 录音控制按钮组：按 Coordinator 真实阶段启停。
 *
 * 按钮层级（品牌规范）：开始录音=主色实心、停止=错误容器色、暂停/继续=描边次级。
 * STARTING/STOPPING 过渡期全部禁用（避免快速连点产生乱序命令）；
 * IDLE/COMPLETED/ERROR 可开始（失败可直接重试）；录音中显示脉冲红点指示。
 */
@Composable
private fun RecordingControls(
    session: RecordingSessionState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
) {
    val canStart = !session.isActive && !session.isBusy
    val canStop = session.isActive
    val canPause = session.phase == RecordingPhase.RECORDING
    val canResume = session.phase == RecordingPhase.PAUSED

    AnimatedVisibility(visible = session.phase == RecordingPhase.RECORDING) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            PulsingDot()
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "录音中",
                style = MaterialTheme.typography.labelMedium,
                color = RecordingRed,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onStartClick,
            enabled = canStart,
            modifier = Modifier.weight(1f),
        ) {
            Text("开始录音")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onStopClick,
            enabled = canStop,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text("停止")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onPauseClick,
            enabled = canPause,
            modifier = Modifier.weight(1f),
        ) {
            Text("暂停")
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onResumeClick,
            enabled = canResume,
            modifier = Modifier.weight(1f),
        ) {
            Text("继续")
        }
    }
}