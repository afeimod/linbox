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
import android.os.Build
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
    // v6.10：系统侧主动终止投屏（Android 14 停止胶囊 / 系统杀投屏）时
    // 的自动收尾 —— 否则帧流断了 UI 还在计时，最后 stop() 必然无帧报错
    private var projectionCallback: MediaProjection.Callback? = null
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

    /** 停止录屏并落盘。
     *
     * v6.10（用户：“录屏都是0秒的假视频”）：MediaRecorder.stop() 在“从未
     * 收到任何视频帧”时必抛 RuntimeException，旧版被 runCatching 静默吞掉
     * 后仍提示“已保存”——落盘的是没有 moov atom 的损坏 MP4（时长 0，
     * 任何播放器都打不开）。现在：stop 失败 = 录制无效 → 删除坏文件并
     * 明确提示失败；stop 成功才扫描入库并提示已保存。
     */
    fun stopRecording(context: Context) {
        if (!recording) return
        val file = recordFile
        recording = false
        mainHandler.removeCallbacks(ticker)
        val stopped = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projectionCallback = null
        runCatching { projection?.stop() }
        recorder = null
        virtualDisplay = null
        projection = null
        recordFile = null
        if (file == null) {
            toast(context, "录屏已停止")
            return
        }
        if (stopped && file.length() > 0L) {
            scan(context, file)
            toast(context, "录屏已保存：${file.name}")
        } else {
            // stop 失败（无帧/编码器异常）= 无 moov 的坏文件 —— 删除，
            // 不再把假视频扫进媒体库骗用户
            runCatching { file.delete() }
            toast(context, "录屏失败：未捕获到画面（已丢弃损坏文件）")
        }
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
        // v6.10（用户：“截屏录屏里应该默认整个屏幕”）：Android 14+ 无参
        // createScreenCaptureIntent() 的授权页默认停在“单个应用（窗口）”，
        // 不改选项就只授权了一个窗口；用 createConfigForDefaultDisplay()
        // 令授权页只提供“整个屏幕”。低版本保留无参调用（无单应用选项）。
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                activity.startActivityForResult(
                    pm.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig
                            .createConfigForDefaultDisplay()
                    ),
                    REQUEST_CODE
                )
            }.onFailure {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CODE)
            }
        } else {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CODE)
        }
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

        // v6.10：投屏回调 —— 系统侧停止（状态栏“停止投屏”胶囊/系统回收）
        // 时自动收尾录屏（保存已录内容），防“UI 永远在录但帧流已断”
        val appContext = context.applicationContext
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { if (recording) stopRecording(appContext) }
            }
        }
        runCatching { projection.registerCallback(cb, mainHandler) }

        this.projection = projection
        this.virtualDisplay = vd
        this.recorder = rec
        this.recordFile = out
        this.projectionCallback = cb
        recording = true
        elapsedSec = 0
        mainHandler.post(ticker)
        toast(context, "录屏开始：${out.name}")
    }

    private fun stopQuietly() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projectionCallback = null
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
