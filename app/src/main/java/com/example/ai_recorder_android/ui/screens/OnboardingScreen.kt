package com.example.ai_recorder_android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ai_recorder_android.ui.components.GradientPrimaryButton
import com.example.ai_recorder_android.ui.components.SectionCard
import com.example.ai_recorder_android.ui.theme.Spacing

/**
 * 首启引导页：首次启动选择转写档位（凭证内置，无运行期密钥输入，Metis B1.3），
 * 下方只读展示固定配置（VLM/LLM 硬编码，对齐 PC settings_dialog 的只读区）。
 *
 * 点击"开始使用"→ 落库 tier + onboarding_done=true → [onComplete]（导航到主页）；
 * 已完成的用户再次进入仅展示只读配置 + "完成"按钮（保证可退出）。
 * onboarding_done 的启动门控在 MainActivity（首启 startDestination 指向本页）。
 *
 * @param onComplete 引导完成（新完成/再次进入确认）后的出口：MainActivity 导航到 HOME
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = rememberSettingsViewModel()
    val config by viewModel.config.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.page),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "欢迎使用 AI 录音",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "选择转写模型档位即可开始使用。凭证已内置，无需手动配置。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl),
        )

        if (!config.onboardingDone) {
            ConfigDropdown(
                label = "转写档位",
                selectedLabel = tierLabel(config.asrTier),
                options = TIER_OPTIONS.map { it.id to it.label },
                onSelect = viewModel::setAsrTier,
            )
            TierDescription(config.asrTier)
            GradientPrimaryButton(
                text = "开始使用",
                onClick = {
                    // 确保所选 tier 已持久化，再标记引导完成
                    viewModel.setAsrTier(config.asrTier)
                    viewModel.setOnboardingDone(true)
                    onComplete()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            )
        } else {
            Text(
                text = "配置已完成",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = Spacing.lg),
            )
            GradientPrimaryButton(
                text = "完成",
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))
        ReadOnlyConfigCard(tierId = config.asrTier)
    }
}

/** 只读配置展示：当前档位 + 固定 VLM/LLM（对齐 PC settings_dialog 只读区）。 */
@Composable
private fun ReadOnlyConfigCard(tierId: String, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text(
            text = "当前配置（只读）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        ConfigRow(label = "转写档位", value = tierLabel(tierId))
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
        ConfigRow(label = "图文描述 VLM", value = "阿里云百炼 Qwen-VL-Max（已内置）")
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
        ConfigRow(label = "文本整理 LLM", value = "DeepSeek deepseek-v4-flash（已内置）")
    }
}

@Composable
private fun ConfigRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.lg),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
