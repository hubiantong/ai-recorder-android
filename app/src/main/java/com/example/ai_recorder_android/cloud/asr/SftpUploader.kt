package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.BuildConfig
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * SFTP 音频托管上传器（对齐 PC `audio_hosting.py`）。
 *
 * 把本地音频经 JSch SFTP 上传到公网服务器（8.130.23.56），返回火山服务端可下载的
 * HTTP 公网 URL。私钥从 BuildConfig.AUDIO_HOST_PRIVATE_KEY 注入（secrets.properties），
 * 不硬编码。JSch 为阻塞 API，upload() 在 Dispatchers.IO 执行。
 *
 * 决策记录（Metis A3.1）：保留 SFTP + JSch 方案——移动网络可达性（8.130.23.56:22
 * 需公网可达）与私钥随 APK 分发风险已知，作为 F3 真机验证项；私钥缺失时抛
 * [SftpUploadException] 而非崩溃。
 */
data class SftpConfig(
    val host: String,
    val port: Int,
    val user: String,
    val privateKey: String,
    val remoteDir: String,
    val publicBase: String,
)

class SftpUploader(private val config: SftpConfig) {

    /** 上传本地文件，返回公网 URL（形如 http://8.130.23.56/airecorder/recordings/<uuid>.wav）。 */
    suspend fun upload(localFile: File): String = withContext(Dispatchers.IO) {
        require(localFile.isFile) { "本地文件不存在: ${localFile.path}" }
        if (config.host.isBlank()) {
            throw SftpUploadException("AUDIO_HOST_HOST 未配置（pro_lite 档需要服务器托管音频）")
        }
        if (config.privateKey.isBlank()) {
            throw SftpUploadException("AUDIO_HOST_PRIVATE_KEY 未配置（请在 app/secrets.properties 中填写 SSH 私钥）")
        }

        val remoteName = remoteFileName(localFile.name)
        val remotePath = joinPath(config.remoteDir, remoteName)

        val jsch = JSch()
        jsch.addIdentity(
            "audio-host",
            normalizedKey(config.privateKey).toByteArray(Charsets.UTF_8),
            null,
            null,
        )
        val session = jsch.getSession(config.user, config.host, config.port)
        // 个人工具权衡：服务器地址固定（8.130.23.56），跳过主机密钥校验以简化部署；
        // 已知 MITM 风险可接受；若需加固可改为固定指纹（StrictHostKeyChecking=yes + known_hosts）。
        session.setConfig("StrictHostKeyChecking", HOST_KEY_CHECKING)
        session.setConfig("PreferredAuthentications", "publickey")
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            val channel = session.openChannel("sftp") as ChannelSftp
            try {
                channel.connect(CONNECT_TIMEOUT_MS)
                ensureRemoteDir(channel, config.remoteDir)
                channel.put(localFile.absolutePath, remotePath)
            } finally {
                channel.disconnect()
            }
        } catch (e: Exception) {
            throw SftpUploadException("SFTP 上传失败: ${e.message}", e)
        } finally {
            session.disconnect()
        }
        publicUrl(config.publicBase, remoteName)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000

        /** 主机密钥校验策略：个人工具固定服务器，跳过校验（MITM 风险已知可接受）。 */
        private const val HOST_KEY_CHECKING = "no"

        /** 生产入口：从 BuildConfig 读取服务器配置（secrets.properties 注入）。 */
        fun fromBuildConfig(): SftpUploader = SftpUploader(
            SftpConfig(
                host = BuildConfig.AUDIO_HOST_HOST,
                port = BuildConfig.AUDIO_HOST_PORT.toIntOrNull() ?: 22,
                user = BuildConfig.AUDIO_HOST_USER,
                privateKey = BuildConfig.AUDIO_HOST_PRIVATE_KEY,
                remoteDir = BuildConfig.AUDIO_HOST_REMOTE_DIR,
                publicBase = BuildConfig.AUDIO_HOST_PUBLIC_BASE,
            ),
        )

        /** properties 中私钥常以字面 `\n` 存储，还原为真实换行（已是真实换行则原样通过）。 */
        internal fun normalizedKey(pem: String): String = pem.replace("\\n", "\n")

        /** uuid 文件名 + 原扩展名（无扩展名默认 .wav），对齐 PC `audio_hosting.py`。 */
        internal fun remoteFileName(
            localName: String,
            uuidHex: String = UUID.randomUUID().toString().replace("-", ""),
        ): String {
            val ext = localName.substringAfterLast('.', "").let { if (it.isBlank()) ".wav" else ".$it" }
            return "$uuidHex$ext"
        }

        /** 拼接公网 URL，容忍 base 尾部斜杠。 */
        internal fun publicUrl(publicBase: String, remoteName: String): String =
            "${publicBase.trimEnd('/')}/$remoteName"

        internal fun joinPath(dir: String, name: String): String = "${dir.trimEnd('/')}/$name"

        /** 递归创建远程目录（若不存在），对齐 PC `_ensure_remote_dir`。 */
        internal fun ensureRemoteDir(channel: ChannelSftp, remoteDir: String) {
            var cur = ""
            for (part in remoteDir.split("/").filter { it.isNotBlank() }) {
                cur = "$cur/$part"
                try {
                    channel.stat(cur)
                } catch (e: SftpException) {
                    channel.mkdir(cur)
                }
            }
        }
    }
}

/** SFTP 上传失败（网络不可达 / 认证失败 / 目录不可写等），映射为用户可见错误。 */
class SftpUploadException(message: String, cause: Throwable? = null) : Exception(message, cause)