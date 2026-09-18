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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首启引导页：首次启动选择转写档位（凭证内置，无运行期密钥输入，Metis B1.3），
 * 下方只读展示固定配置（VLM/LLM 硬编码，对齐 PC settings_dialog 的只读区）。
 *
 * 点击"开始使用"→ 落库 tier + onboarding_done=true；已完成的用户再次进入
 * 仅展示只读配置（onboarding_done 检查在屏幕内部完成，导航接线在 MainActivity，
 * 本任务禁止修改）。
 */
@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    val viewModel = rememberSettingsViewModel()
    val config by viewModel.config.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
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
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        if (!config.onboardingDone) {
            ConfigDropdown(
                label = "转写档位",
                selectedLabel = tierLabel(config.asrTier),
                options = TIER_OPTIONS.map { it.id to it.label },
                onSelect = viewModel::setAsrTier,
            )
            TierDescription(config.asrTier)
            Button(
                onClick = {
                    // 确保所选 tier 已持久化，再标记引导完成
                    viewModel.setAsrTier(config.asrTier)
                    viewModel.setOnboardingDone(true)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text("开始使用")
            }
        } else {
            Text(
                text = "配置已完成",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        ReadOnlyConfigCard(tierId = config.asrTier)
    }
}

/** 只读配置展示：当前档位 + 固定 VLM/LLM（对齐 PC settings_dialog 只读区）。 */
@Composable
private fun ReadOnlyConfigCard(tierId: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "当前配置（只读）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ConfigRow(label = "转写档位", value = tierLabel(tierId))
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ConfigRow(label = "图文描述 VLM", value = "阿里云百炼 Qwen-VL-Max（已内置）")
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ConfigRow(label = "文本整理 LLM", value = "DeepSeek deepseek-v4-flash（已内置）")
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}