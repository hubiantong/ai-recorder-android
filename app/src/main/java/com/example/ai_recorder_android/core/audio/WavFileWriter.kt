package com.example.ai_recorder_android.core.audio

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 纯 JVM WAV 写入器（RIFF / PCM_16，标准 44 字节头）。
 *
 * 对齐 PC `core/audio/recorder.py` 的 wave 模块语义：16kHz / 16-bit / mono，
 * 但本类不依赖 Android API，可在 JVM 单测中直接使用（`ByteArrayOutputStream` 或 `File`）。
 *
 * 用法（先写头后回填大小）：
 * ```
 * RandomAccessFile(file, "rw").use { raf ->
 *     WavFileWriter.writeHeader(raf, 16000, 1, 16)
 *     raf.write(pcmBytes)          // 写入 PCM_16 数据
 *     WavFileWriter.finalize(raf, pcmBytes.size)  // seek 回头部回填 RIFF size / dataSize
 * }
 * ```
 *
 * 头部布局（44 字节，全部 little-endian）：
 * | 偏移 | 长度 | 内容 |
 * |------|------|------|
 * | 0    | 4    | "RIFF" |
 * | 4    | 4    | chunkSize = 36 + dataSize（占位 0，finalize 回填） |
 * | 8    | 4    | "WAVE" |
 * | 12   | 4    | "fmt " |
 * | 16   | 4    | fmt chunk size = 16 |
 * | 20   | 2    | audioFormat = 1（PCM） |
 * | 22   | 2    | numChannels |
 * | 24   | 4    | sampleRate |
 * | 28   | 4    | byteRate = sampleRate * channels * bitsPerSample / 8 |
 * | 32   | 2    | blockAlign = channels * bitsPerSample / 8 |
 * | 34   | 2    | bitsPerSample |
 * | 36   | 4    | "data" |
 * | 40   | 4    | dataSize（占位 0，finalize 回填） |
 */
object WavFileWriter {

    const val HEADER_SIZE = 44

    /** 录音链路统一参数（对齐 PC：16kHz / 16-bit / mono）。 */
    const val DEFAULT_SAMPLE_RATE = 16000
    const val DEFAULT_CHANNELS = 1
    const val DEFAULT_BITS_PER_SAMPLE = 16

    /**
     * 写入 44 字节 RIFF 头（RIFF size 与 dataSize 先写占位 0，
     * 数据写完后调用 [finalize] 回填）。
     *
     * @throws IllegalArgumentException bitsPerSample != 16（仅支持 PCM_16）、
     *         sampleRate/channels <= 0
     */
    fun writeHeader(output: OutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        writeHeaderInternal({ output.write(it) }, sampleRate, channels, bitsPerSample)
    }

    /** [writeHeader] 的 [RandomAccessFile] 重载（引擎写段文件用）。 */
    fun writeHeader(output: RandomAccessFile, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        writeHeaderInternal({ output.write(it) }, sampleRate, channels, bitsPerSample)
    }

    private fun writeHeaderInternal(
        writeByte: (Int) -> Unit,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        require(bitsPerSample == 16) { "仅支持 16-bit PCM，收到 $bitsPerSample" }
        require(sampleRate > 0) { "sampleRate 必须 > 0: $sampleRate" }
        require(channels > 0) { "channels 必须 > 0: $channels" }

        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        writeAscii(writeByte, "RIFF")
        writeIntLE(writeByte, 36) // chunkSize 占位（= 36 + dataSize，finalize 回填）
        writeAscii(writeByte, "WAVE")
        writeAscii(writeByte, "fmt ")
        writeIntLE(writeByte, 16) // fmt chunk size
        writeShortLE(writeByte, 1) // audioFormat = PCM
        writeShortLE(writeByte, channels)
        writeIntLE(writeByte, sampleRate)
        writeIntLE(writeByte, byteRate)
        writeShortLE(writeByte, blockAlign)
        writeShortLE(writeByte, bitsPerSample)
        writeAscii(writeByte, "data")
        writeIntLE(writeByte, 0) // dataSize 占位（finalize 回填）
    }

    /**
     * 回填头部大小字段：seek 到偏移 4 写 RIFF chunkSize（= 36 + dataSize），
     * seek 到偏移 40 写 dataSize。调用前文件指针位置无关紧要。
     *
     * @throws IllegalArgumentException dataSize < 0
     */
    fun finalize(output: RandomAccessFile, dataSize: Int) {
        require(dataSize >= 0) { "dataSize 必须 >= 0: $dataSize" }
        output.seek(4)
        writeIntLE({ output.write(it) }, 36 + dataSize)
        output.seek(40)
        writeIntLE({ output.write(it) }, dataSize)
    }

    /** [finalize] 的便捷重载：以 rw 模式打开文件回填后关闭。 */
    fun finalize(file: File, dataSize: Int) {
        RandomAccessFile(file, "rw").use { finalize(it, dataSize) }
    }

    // ---- little-endian 原语 ----

    private fun writeAscii(writeByte: (Int) -> Unit, s: String) {
        for (b in s.toByteArray(Charsets.US_ASCII)) writeByte(b.toInt())
    }

    private fun writeIntLE(writeByte: (Int) -> Unit, v: Int) {
        writeByte(v and 0xFF)
        writeByte((v shr 8) and 0xFF)
        writeByte((v shr 16) and 0xFF)
        writeByte((v shr 24) and 0xFF)
    }

    private fun writeShortLE(writeByte: (Int) -> Unit, v: Int) {
        writeByte(v and 0xFF)
        writeByte((v shr 8) and 0xFF)
    }
}