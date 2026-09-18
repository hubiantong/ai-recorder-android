/**
 * 后台服务层：
 * - 前台录音服务 RecordingService + 常驻通知 RecordingNotification；
 * - 录音会话状态机 RecordingCoordinator（开始/暂停/停止、暂停时长累计）；
 * - 拍照附件 PhotoAttachmentManager（截屏/拍照 → VLM 描述 → 落库）；
 * - transcription 子包：转写流水线（整文件 / VAD 分段 / 实时三模式）
 *   及纪要融合 NoteFusionWorker、自动标题 AutoTitleWorker。
 */
package com.example.ai_recorder_android.service
