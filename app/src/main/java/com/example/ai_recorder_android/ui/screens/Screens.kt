package com.example.ai_recorder_android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 录音主页：真实 RecordingScreen，含音量指示条/录音控制/实时转写/拍照与文件转写入口。
 * 导航回调必传（无空默认值），由 MainActivity NavHost 接线。
 */
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onFileUploadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecordingScreen(
        onSettingsClick = onSettingsClick,
        onFileUploadClick = onFileUploadClick,
        modifier = modifier,
    )
}
