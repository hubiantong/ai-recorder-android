package com.example.ai_recorder_android.core.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile

/**
 * WavFileWriter 纯 JVM 单测（不依赖 Android 环境）。
 * 覆盖：写 1s 静音（16000 样本 int16）→ 读回头部 44 字节断言 RIFF/WAVE/fmt/data
 * 标记与 dataSize 回填正确；非 16bit 输入拒绝。
 */
class WavFileWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sampleRate = 16000
    private val channels = 1
    private val bitsPerSample = 16

    /** 1s 静音 = 16000 样本 × 2 字节 = 32000 字节。 */
    private val oneSecondSilenceBytes = sampleRate * 2

    @Test
    fun writeHeaderAndFinalize_producesValid44ByteHeader() {
        val file = tmp.newFile("one_second_silence.wav")

        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, sampleRate, channels, bitsPerSample)
            raf.write(ByteArray(oneSecondSilenceBytes)) // 全零 = 静音
            WavFileWriter.finalize(raf, oneSecondSilenceBytes)
        }

        val bytes = file.readBytes()
        assertEquals(44L + oneSecondSilenceBytes, file.length())
        val header = bytes.copyOfRange(0, WavFileWriter.HEADER_SIZE)

        // RIFF 标记与 chunkSize（= 36 + dataSize）
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + oneSecondSilenceBytes, readIntLE(header, 4))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))

        // fmt 块
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(16, readIntLE(header, 16)) // fmt chunk size
        assertEquals(1, readShortLE(header, 20)) // audioFormat = PCM
        assertEquals(channels, readShortLE(header, 22))
        assertEquals(sampleRate, readIntLE(header, 24))
        assertEquals(sampleRate * channels * bitsPerSample / 8, readIntLE(header, 28)) // byteRate
        assertEquals(channels * bitsPerSample / 8, readShortLE(header, 32)) // blockAlign
        assertEquals(bitsPerSample, readShortLE(header, 34))

        // data 块
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(oneSecondSilenceBytes, readIntLE(header, 40)) // dataSize 回填
    }

    @Test
    fun writeHeader_toByteArrayOutputStream_matchesFileOutput() {
        // 验证 OutputStream 重载（ByteArrayOutputStream 路径）与 RandomAccessFile 路径产出一致
        val baos = ByteArrayOutputStream()
        WavFileWriter.writeHeader(baos, sampleRate, channels, bitsPerSample)
        baos.write(ByteArray(oneSecondSilenceBytes))

        val file = tmp.newFile("via_file.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, sampleRate, channels, bitsPerSample)
            raf.write(ByteArray(oneSecondSilenceBytes))
            WavFileWriter.finalize(raf, oneSecondSilenceBytes)
        }

        val expected = file.readBytes()
        val actual = baos.toByteArray()
        assertEquals(expected.size, actual.size)
        // 未 finalize 的流：RIFF size(@4) 与 dataSize(@40) 仍是占位 0，跳过这两段比较
        for (i in 0 until WavFileWriter.HEADER_SIZE) {
            if (i in 4..7 || i in 40..43) continue
            assertEquals("字节 $i 不一致", expected[i].toInt(), actual[i].toInt())
        }
    }

    @Test
    fun finalize_zeroDataSize_writesValidEmptyWav() {
        val file = tmp.newFile("empty.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, sampleRate, channels, bitsPerSample)
            WavFileWriter.finalize(raf, 0)
        }
        val header = file.readBytes().copyOfRange(0, WavFileWriter.HEADER_SIZE)
        assertEquals(36, readIntLE(header, 4))
        assertEquals(0, readIntLE(header, 40))
        assertEquals(44L, file.length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeHeader_rejectsNon16Bit() {
        val file = tmp.newFile("bad_bits.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, sampleRate, channels, 8) // 8-bit 应拒绝
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeHeader_rejectsZeroSampleRate() {
        val file = tmp.newFile("bad_rate.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, 0, channels, bitsPerSample)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun finalize_rejectsNegativeDataSize() {
        val file = tmp.newFile("bad_size.wav")
        RandomAccessFile(file, "rw").use { raf ->
            WavFileWriter.writeHeader(raf, sampleRate, channels, bitsPerSample)
            WavFileWriter.finalize(raf, -1)
        }
    }

    // ---- little-endian 读取辅助 ----

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}