package com.example.ai_recorder_android.core.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * 全屏截屏（仅全屏单屏，Metis S2.1：不做 region 截屏 / 多屏选择 / 悬浮窗）。
 *
 * 用 MediaProjection.createVirtualDisplay + ImageReader 抓取默认 Display 全屏帧，
 * 转 PNG 写入附件目录（`attachments/att_{uuid}.png`，对齐 StorageService 语义）。
 * 必须在 MediaProjection 授权会话内调用（todo 16 传入已授权 projection）。
 *
 * 注意：应在后台线程调用（内部有最多 ~1s 的帧等待轮询）。
 */
class ScreenshotCapturer(private val context: Context) {

    private val tag = "ScreenshotCapturer"

    /**
     * 截取全屏并保存为 PNG。
     *
     * @param projection 已授权的 MediaProjection（同一会话）
     * @param outputFile 输出 PNG 文件（调用方决定路径，如 attachments/att_{uuid}.png）
     * @return 成功返回 true；失败（无帧 / IO 错误）返回 false 不抛异常
     */
    fun capture(projection: MediaProjection, outputFile: File): Boolean {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        var bitmap: Bitmap? = null
        // API 34+ 要求 callback 非空；回调需绑定专用 Looper 线程。
        // 每次 capture 独立建线程，finally 中 quitSafely，避免跨会话泄漏。
        val callbackThread = HandlerThread("screenshot-vd-cb").apply { start() }
        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection.createVirtualDisplay(
                "screenshot-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                object : VirtualDisplay.Callback() {},
                Handler(callbackThread.looper),
            )
            val image = waitForImage(imageReader) ?: run {
                Log.w(tag, "截屏超时：未获取到帧")
                return false
            }
            try {
                bitmap = imageToBitmap(image, width, height)
            } finally {
                image.close()
            }
            val bmp = bitmap ?: run {
                Log.w(tag, "截屏失败：图像数据为空")
                return false
            }
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(tag, "截屏已保存: ${outputFile.absolutePath} (${width}x$height)")
            return true
        } catch (e: Exception) {
            Log.w(tag, "截屏失败: ${e.message}")
            return false
        } finally {
            imageReader?.close()
            virtualDisplay?.release()
            bitmap?.recycle()
            callbackThread.quitSafely()
        }
    }

    /** 轮询等待首帧（最多 ~1s），无帧返回 null。 */
    private fun waitForImage(imageReader: ImageReader): Image? {
        repeat(FRAME_WAIT_ATTEMPTS) {
            imageReader.acquireLatestImage()?.let { return it }
            Thread.sleep(FRAME_WAIT_INTERVAL_MS)
        }
        return null
    }

    /** Image(RGBA_8888) → Bitmap，去掉 rowStride 填充（显式复制，full 可安全 recycle）。 */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val full = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        full.copyPixelsFromBuffer(buffer)
        // createBitmap(source, ...) 共享像素，recycle full 会失效；显式绘制复制一份
        val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(cropped).drawBitmap(full, 0f, 0f, null)
        full.recycle()
        return cropped
    }

    companion object {
        private const val FRAME_WAIT_ATTEMPTS = 10
        private const val FRAME_WAIT_INTERVAL_MS = 100L
    }
}