package com.example.ai_recorder_android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai_recorder_android.data.repo.UserConfig
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.ui.components.SectionCard
import com.example.ai_recorder_android.ui.components.SectionTitle
import com.example.ai_recorder_android.ui.theme.Spacing
import com.example.ai_recorder_android.ui.theme.tabularNums
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------- 档位 / 语言元数据（对齐 PC core/transcription/tiers.py 与 settings_dialog.py） ----------

/** 单档转写模型定义（对齐 PC TierDef）。 */
internal data class TierOption(
    val id: String,
    val label: String,
    val description: String,
)

/** 四档 tier，顺序对齐 PC TIER_ORDER。 */
internal val TIER_OPTIONS = listOf(
    TierOption(
        id = "free",
        label = "免费版",
        description = "硅基流动 FunAudioLLM/SenseVoiceSmall · OpenAI 兼容端点 · 单文件 ≤1 小时 / 50MB",
    ),
    TierOption(
        id = "realtime",
        label = "实时版",
        description = "阿里云百炼 paraformer-realtime-8k-v2 · 实时 WebSocket · 8kHz",
    ),
    TierOption(
        id = "pro_lite",
        label = "专业版 Lite",
        description = "火山引擎 豆包录音文件识别模型 2.0 · 文件识别（submit+query）",
    ),
    TierOption(
        id = "pro_pro",
        label = "专业版 Pro",
        description = "阿里云百炼 fun-asr-flash · 录音文件识别 REST（submit+query）",
    ),
)

/** 转写语言选项（"" = 自动检测，对齐 PC settings_dialog 的 lang_map）。 */
internal val LANGUAGE_OPTIONS = listOf(
    "zh" to "中文",
    "en" to "英文",
    "" to "自动检测",
)

/** 会议音源选项（对齐 PC settings_dialog）。 */
internal val AUDIO_SOURCE_OPTIONS = listOf(
    "mixed" to "系统声音 + 麦克风（混音，推荐）",
    "system" to "仅系统声音（对方发言）",
    "mic" to "仅麦克风（本地发言）",
)

/** 截屏方式选项（对齐 PC settings_dialog）。 */
internal val SCREENSHOT_MODE_OPTIONS = listOf(
    "full" to "全屏截屏（默认）",
    "region" to "框选截屏（拖拽选择区域）",
)

/** 主题模式选项（system = 跟随系统）。 */
internal val THEME_OPTIONS = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

internal fun tierLabel(id: String): String =
    TIER_OPTIONS.firstOrNull { it.id == id }?.label ?: TIER_OPTIONS.first().label

internal fun languageLabel(value: String): String =
    LANGUAGE_OPTIONS.firstOrNull { it.first == value }?.second ?: "自动检测"

internal fun audioSourceLabel(value: String): String =
    AUDIO_SOURCE_OPTIONS.firstOrNull { it.first == value }?.second ?: AUDIO_SOURCE_OPTIONS.first().second

internal fun screenshotModeLabel(value: String): String =
    SCREENSHOT_MODE_OPTIONS.firstOrNull { it.first == value }?.second ?: SCREENSHOT_MODE_OPTIONS.first().second

internal fun themeLabel(value: String): String =
    THEME_OPTIONS.firstOrNull { it.first == value }?.second ?: THEME_OPTIONS.first().second

// ---------- ViewModel ----------

/**
 * 设置页 / 首启引导共享的 ViewModel：注入 [UserConfigRepository]，所有变更经 DataStore
 * 持久化后由 [config] 流回 UI（tier 变更 → 落库 → UI 自动刷新）。
 *
 * 生命周期 2.6.1 无 viewModelScope（2.8 才并入 lifecycle-viewmodel），故自建 scope，
 * onCleared 取消。工厂用 viewModelFactory + APPLICATION_KEY（仓库从 Application 构建）。
 */
class SettingsViewModel(private val repository: UserConfigRepository) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 用户配置流：DataStore 每次变更发射最新值。 */
    val config: StateFlow<UserConfig> = repository.userConfig
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), UserConfig())

    fun setAsrTier(tier: String) {
        scope.launch { repository.setAsrTier(tier) }
    }

    fun setLanguage(language: String) {
        scope.launch { repository.setLanguage(language) }
    }

    /**
     * 设置 VAD 三参数（minSegment 由调用方透传当前值，UI 不直接编辑）。
     * 值域钳制：threshold 0.0-1.0 / silenceDuration 0.5-5.0 / maxSegment 10.0-600.0。
     */
    fun setVadParams(threshold: Double, silenceDuration: Double, minSegment: Double, maxSegment: Double) {
        val t = threshold.coerceIn(0.0, 1.0)
        val s = silenceDuration.coerceIn(0.5, 5.0)
        val m = maxSegment.coerceIn(10.0, 600.0)
        scope.launch { repository.setVadParams(t, s, minSegment, m) }
    }

    fun setMeetingMode(enabled: Boolean) {
        scope.launch { repository.setMeetingMode(enabled) }
    }

    fun setMeetingAudioSource(source: String) {
        scope.launch { repository.setMeetingAudioSource(source) }
    }

    fun setMeetingScreenshotMode(mode: String) {
        scope.launch { repository.setMeetingScreenshotMode(mode) }
    }

    fun setMeetingAutoFloat(autoFloat: Boolean) {
        scope.launch { repository.setMeetingAutoFloat(autoFloat) }
    }

    /** 系统音频 VAD 阈值，钳制 0.001-0.1（对齐 PC spin 范围）。 */
    fun setMeetingVadThreshold(threshold: Double) {
        scope.launch { repository.setMeetingVadSilenceThreshold(threshold.coerceIn(0.001, 0.1)) }
    }

    /** 主题模式：system（跟随系统）/ light / dark。 */
    fun setTheme(theme: String) {
        scope.launch { repository.setTheme(theme) }
    }

    /** Android 12+ 动态配色（壁纸取色）；默认关闭保持品牌色。 */
    fun setDynamicColor(enabled: Boolean) {
        scope.launch { repository.setDynamicColor(enabled) }
    }

    fun setOnboardingDone(done: Boolean) {
        scope.launch { repository.setOnboardingDone(done) }
    }

    override fun onCleared() {
        scope.cancel()
    }

    companion object {
        /** 生产工厂：从 Application 构建 UserConfigRepository（DataStore 单例）。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                SettingsViewModel(UserConfigRepository.fromContext(app))
            }
        }
    }
}

/**
 * 无 lifecycle-viewmodel-compose 依赖（禁止改 build.gradle.kts），手动经
 * ViewModelProvider 取实例：Activity 即 ViewModelStoreOwner，remember 保证
 * 重组复用同一实例，配置变更后 ViewModelStore 仍返回原实例。
 */
@Composable
internal fun rememberSettingsViewModel(): SettingsViewModel {
    val context = LocalContext.current
    return remember {
        ViewModelProvider(
            context as ViewModelStoreOwner,
            SettingsViewModel.Factory,
        )[SettingsViewModel::class.java]
    }
}

// ---------- 设置页 ----------

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel = rememberSettingsViewModel()
    val config by viewModel.config.collectAsState()

    // 卡片分组布局：区块标题贴近卡片（sm），区块之间留呼吸感（lg）
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page)
            .padding(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = Spacing.lg),
        )

        // ---- 转写模型 ----
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionTitle("转写模型")
            SectionCard {
                ConfigDropdown(
                    label = "档位",
                    selectedLabel = tierLabel(config.asrTier),
                    options = TIER_OPTIONS.map { it.id to it.label },
                    onSelect = viewModel::setAsrTier,
                )
                TierDescription(config.asrTier)
                ConfigDropdown(
                    label = "转写语言",
                    selectedLabel = languageLabel(config.language),
                    options = LANGUAGE_OPTIONS,
                    onSelect = viewModel::setLanguage,
                )
            }
        }

        // ---- VAD 自动分段 ----
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionTitle("VAD 自动分段")
            SectionCard {
                VadSliderRow(
                    label = "静音灵敏度",
                    value = config.vadSilenceThreshold.toFloat(),
                    valueRange = 0f..1f,
                    steps = 100,
                    format = { "%.2f".format(it) },
                    onValueChange = {
                        viewModel.setVadParams(
                            it.toDouble(),
                            config.vadSilenceDuration,
                            config.vadMinSegment,
                            config.vadMaxSegment,
                        )
                    },
                )
                VadSliderRow(
                    label = "静音触发时长（秒）",
                    value = config.vadSilenceDuration.toFloat(),
                    valueRange = 0.5f..5f,
                    steps = 45,
                    format = { "%.1f".format(it) },
                    onValueChange = {
                        viewModel.setVadParams(
                            config.vadSilenceThreshold,
                            it.toDouble(),
                            config.vadMinSegment,
                            config.vadMaxSegment,
                        )
                    },
                )
                VadSliderRow(
                    label = "最大分段时长（秒）",
                    value = config.vadMaxSegment.toFloat(),
                    valueRange = 10f..600f,
                    steps = 590,
                    format = { "%.0f".format(it) },
                    onValueChange = {
                        viewModel.setVadParams(
                            config.vadSilenceThreshold,
                            config.vadSilenceDuration,
                            config.vadMinSegment,
                            it.toDouble(),
                        )
                    },
                )
                Text(
                    text = "说话停顿达阈值即截断当前段并独立转写。实时版走 WebSocket 流式，无需此设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }

        // ---- 会议模式 ----
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionTitle("会议模式")
            SectionCard {
                SwitchRow(
                    label = "启用会议模式（系统音频 + 截屏 + 悬浮窗）",
                    checked = config.meetingMode,
                    onCheckedChange = viewModel::setMeetingMode,
                )
                ConfigDropdown(
                    label = "默认音源",
                    selectedLabel = audioSourceLabel(config.meetingAudioSource),
                    options = AUDIO_SOURCE_OPTIONS,
                    onSelect = viewModel::setMeetingAudioSource,
                )
                ConfigDropdown(
                    label = "截屏方式",
                    selectedLabel = screenshotModeLabel(config.meetingScreenshotMode),
                    options = SCREENSHOT_MODE_OPTIONS,
                    onSelect = viewModel::setMeetingScreenshotMode,
                )
                SwitchRow(
                    label = "开始录音时自动收起为悬浮窗",
                    checked = config.meetingAutoFloat,
                    onCheckedChange = viewModel::setMeetingAutoFloat,
                )
                VadSliderRow(
                    label = "系统音频 VAD 阈值",
                    value = config.meetingVadSilenceThreshold.toFloat(),
                    valueRange = 0.001f..0.1f,
                    steps = 99,
                    format = { "%.3f".format(it) },
                    onValueChange = { viewModel.setMeetingVadThreshold(it.toDouble()) },
                )
                Text(
                    text = "会议模式用于视频会议场景：录制系统音频（对方发言）+ 麦克风（本地发言），支持截屏作为附件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }

        // ---- 外观 ----
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SectionTitle("外观")
            SectionCard {
                ConfigDropdown(
                    label = "主题模式",
                    selectedLabel = themeLabel(config.theme),
                    options = THEME_OPTIONS,
                    onSelect = viewModel::setTheme,
                )
                SwitchRow(
                    label = "动态配色（Android 12+ 壁纸取色，覆盖品牌色）",
                    checked = config.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
                Text(
                    text = "关闭时使用应用固定的深色科技风品牌配色；开启后颜色随系统壁纸变化。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

// ---------- 共享组件 ----------

@Composable
internal fun TierDescription(tierId: String, modifier: Modifier = Modifier) {
    val tier = TIER_OPTIONS.firstOrNull { it.id == tierId } ?: TIER_OPTIONS.first()
    Text(
        text = tier.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
    )
}

/** 只读下拉框（ExposedDropdownMenuBox）：选中项显示在输入框内，点击展开选项列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 滑块 + 数值显示行：拖动实时回调，数值格式化展示在右侧。 */
@Composable
internal fun VadSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodyMedium.tabularNums(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = Spacing.md),
        )
    }
}