package com.linbox.core.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v4.0 截图 / 录屏（主页四大功能入口之一）。
 *
 * 基于 MediaProjection（系统级整屏捕获，X11 游戏画面 / 容器窗口一并捕获）：
 * - 截图：ImageReader 虚拟屏取一帧 → PNG → /sdcard/Pictures/LinBox/
 * - 录屏：MediaRecorder(H.264) → MP4 → /sdcard/Movies/LinBox/
 *
 * targetSdk 锁 28（Termux exec 约束），Android 14+ 无前台服务类型强制，
 * 每次捕获都重新走系统授权弹窗（一次授权只做一件事，语义清晰）。
 *
 * 用法：Activity 转发 [onActivityResult]；UI 观察 [recording]/[elapsedSec]。
 */
object ScreenCaptureManager {

    private const val REQUEST_CODE = 4711
    private const val MAX_RECORD_HEIGHT = 1080

    /** 录屏进行中（UI 状态）。 */
    var recording by mutableStateOf(false)
        private set

    /** 本次录屏已进行秒数（UI 状态）。 */
    var elapsedSec by mutableStateOf(0)
        private set

    private var pendingAction: String? = null   // "shot" | "record"
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (recording) {
                elapsedSec++
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    // ============================================================
    // 授权请求（Activity 转发结果）
    // ============================================================

    /** 请求一次整屏截图（弹系统授权 → 截一帧存 PNG）。 */
    fun requestScreenshot(activity: Activity) {
        pendingAction = ACTION_SHOT
        launchConsent(activity)
    }

    /** 请求开始录屏（弹系统授权 → 开始 MP4 录制）。 */
    fun requestRecording(activity: Activity) {
        if (recording) return
        pendingAction = ACTION_RECORD
        launchConsent(activity)
    }

    /** 停止录屏并落盘。 */
    fun stopRecording(context: Context) {
        if (!recording) return
        val file = recordFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.stop() }
        recorder = null
        virtualDisplay = null
        projection = null
        recordFile = null
        recording = false
        mainHandler.removeCallbacks(ticker)
        file?.let { scan(context, it) }
        toast(context, if (file != null) "录屏已保存：${file.name}" else "录屏已停止")
    }

    /** Activity.onActivityResult 转发；消费返回 true。 */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val action = pendingAction
        pendingAction = null
        if (action == null) return true
        if (resultCode != Activity.RESULT_OK || data == null) {
            toast(activity, "已取消授权")
            return true
        }
        when (action) {
            ACTION_SHOT -> takeScreenshotAsync(activity, resultCode, data)
            ACTION_RECORD -> runCatching { startRecording(activity, resultCode, data) }
                .onFailure { toast(activity, "录屏启动失败：${it.message}") }
        }
        return true
    }

    private fun launchConsent(activity: Activity) {
        val pm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        activity.startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CODE)
    }

    // ============================================================
    // 截图
    // ============================================================

    private fun takeScreenshotAsync(context: Context, resultCode: Int, data: Intent) {
        Thread {
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            var projection: MediaProjection? = null
            var reader: ImageReader? = null
            var display: VirtualDisplay? = null
            try {
                projection = pm.getMediaProjection(resultCode, data)
                val m = context.resources.displayMetrics
                val w = m.widthPixels
                val h = m.heightPixels
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                display = projection.createVirtualDisplay(
                    "linbox-shot", w, h, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
                // 等待首帧（最多 3s）
                val deadline = SystemClock.uptimeMillis() + 3000
                var image: Image? = null
                while (image == null && SystemClock.uptimeMillis() < deadline) {
                    image = reader.acquireLatestImage() ?: run { Thread.sleep(40); null }
                }
                if (image == null) {
                    toast(context, "截图失败：未捕获到画面")
                    return@Thread
                }
                val bmp = image.toBitmap()
                image.close()
                // 保存 PNG
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "LinBox"
                )
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, "Screenshot_${stamp()}.png")
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                scan(context, out)
                toast(context, "截图已保存：${out.name}")
            } catch (e: Exception) {
                toast(context, "截图失败：${e.message}")
            } finally {
                runCatching { reader?.close() }
                runCatching { display?.release() }
                runCatching { projection?.stop() }
            }
        }.apply { name = "linbox-screenshot" }.start()
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bmp = Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        // 裁掉行尾 padding
        return if (rowPadding == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, width, height).also { if (it !== bmp) bmp.recycle() }
    }

    // ============================================================
    // 录屏
    // ============================================================

    private fun startRecording(context: Context, resultCode: Int, data: Intent) {
        stopQuietly()
        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = pm.getMediaProjection(resultCode, data)
        val m = context.resources.displayMetrics
        var w = m.widthPixels
        var h = m.heightPixels
        if (h > MAX_RECORD_HEIGHT) {
            w = w * MAX_RECORD_HEIGHT / h
            h = MAX_RECORD_HEIGHT
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "LinBox"
        )
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "Record_${stamp()}.mp4")

        @Suppress("DEPRECATION")
        val rec = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(w, h)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(30)
            setOutputFile(out.absolutePath)
            prepare()
        }
        val vd = projection.createVirtualDisplay(
            "linbox-rec", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rec.surface, null, null
        )
        rec.start()

        this.projection = projection
        this.virtualDisplay = vd
        this.recorder = rec
        this.recordFile = out
        recording = true
        elapsedSec = 0
        mainHandler.post(ticker)
        toast(context, "录屏开始：${out.name}")
    }

    private fun stopQuietly() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.stop() }
        recorder = null
        virtualDisplay = null
        projection = null
        recording = false
        mainHandler.removeCallbacks(ticker)
    }

    // ============================================================
    // 工具
    // ============================================================

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun scan(context: Context, file: File) {
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), null, null
            )
        }
    }

    private fun toast(context: Context, msg: String) {
        mainHandler.post {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private const val ACTION_SHOT = "shot"
    private const val ACTION_RECORD = "record"
}
