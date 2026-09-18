package com.example.ai_recorder_android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 录音主页（todo 19）：真实 RecordingScreen，含音量指示条/录音控制/实时转写/拍照入口。
 * 拍照入口（todo 23）已内建相机流程；设置导航待 todo 19 完成 MainActivity 接线。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    RecordingScreen(modifier = modifier)
}