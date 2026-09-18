/**
 * 数据层 - 本地文件存储：StorageService 基于 filesDir 管理录音/分段/附件文件，
 * 提供保存、读取、按 locator（如 `recordings/rec_{uuid}.{ext}`）解析路径、
 * 删除与级联清理。SFTP 上传不在本包，见 cloud.asr.SftpUploader。
 */
package com.example.ai_recorder_android.data.storage
