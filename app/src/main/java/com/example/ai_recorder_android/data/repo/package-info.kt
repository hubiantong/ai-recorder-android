/**
 * 数据层 - 仓库：业务数据访问门面。
 * - RecordingStore 接口 + RecordingRepository 实现：录音 CRUD、音频落盘、
 *   分段与附件管理（语义对齐 PC `pc_api/recording_service.py`）；
 * - UserConfigRepository：DataStore Preferences 用户配置（含文件损坏兜底）。
 */
package com.example.ai_recorder_android.data.repo
