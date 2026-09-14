package com.linbox.apps.dac

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.util.Log

/**
 * DacView — DAC 显示视图（SurfaceView）
 *
 * Copyright 2026 LinBox Project (MIT)
 *
 * 复用 LinBox 输入系统（core/input）：虚拟鼠标 / 键盘事件 → DacInput → JNI →
 * winedac.drv。触摸即鼠标：点按=左键、双指=右键、滚轮手势=wheel。
 */
class DacView(container: FrameLayout) : SurfaceHolder.Callback2, View.OnTouchListener {

    companion object {
        private const val TAG = "LinBoxDAC"
    }

    private val surfaceView = SurfaceView(container.context).apply {
        holder.addCallback(this@DacView)
        setOnTouchListener(this@DacView)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private var connected = false
    private var scaleFactor = 1f          // 视图 → 虚拟桌面 缩放
    private var desktopW = 1280
    private var desktopH = 720

    private val scaleDetector = ScaleGestureDetector(
        container.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.25f, 4f)
                return true
            }
        }
    )

    init {
        container.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // 物理/虚拟键盘事件直注（聚焦后收键；软键盘经 LinBox 输入条）
        surfaceView.setOnKeyListener { _, _, event ->
            val down = event.action == KeyEvent.ACTION_DOWN
            if (event.repeatCount > 0 && !down) false
            else {
                DacInput.onHardwareKey(event.keyCode, event.metaState, down)
                true
            }
        }
    }

    /** Surface 就绪回调（DacApp 连接前等待） */
    fun postWhenSurfaceReady(block: () -> Unit) {
        val holder = surfaceView.holder
        if (holder.surface?.isValid == true) { surfaceView.post(block); return }
        pendingReady = block
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { pendingReady?.invoke(); pendingReady = null }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private var pendingReady: (() -> Unit)? = null

    /** 连接 bridge（由 DacApp 在窗口打开后调用） */
    fun connect(socketPath: String): Int {
        val surface: Surface = surfaceView.holder.surface ?: return DacNative.BACKEND_NONE
        if (!surface.isValid) return DacNative.BACKEND_NONE
        surfaceView.requestFocus()
        val backend = DacNative.nativeConnect(surface, socketPath)
        connected = backend != DacNative.BACKEND_NONE
        Log.i(TAG, "connect backend=$backend socket=$socketPath")
        return backend
    }

    fun disconnect() {
        if (connected) {
            DacNative.nativeDisconnect()
            connected = false
        }
    }

    fun setDesktopSize(w: Int, h: Int) {
        desktopW = w
        desktopH = h
    }

    fun release() {
        disconnect()
        (surfaceView.parent as? FrameLayout)?.removeView(surfaceView)
    }

    // ---------------- SurfaceHolder.Callback2 ----------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surface changed ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surface destroyed")
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}

    // ---------------- 触摸 → 虚拟鼠标 ----------------

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        val viewW = surfaceView.width.toFloat()
        val viewH = surfaceView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return true

        // 触点 → 虚拟桌面坐标
        val dx = event.x / viewW * desktopW
        val dy = event.y / viewH * desktopH

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val btn = if (event.pointerCount >= 2) DacNative.BTN_RIGHT else DacNative.BTN_LEFT
                DacNative.nativeSendMouse(
                    DacNative.MOUSE_MOVE or DacNative.MOUSE_BUTTON,
                    dx, dy, btn, 0f, 0f, true
                )
            }
            MotionEvent.ACTION_MOVE -> {
                DacNative.nativeSendMouse(DacNative.MOUSE_MOVE, dx, dy, 0, 0f, 0f, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val btn = if (event.pointerCount >= 2) DacNative.BTN_RIGHT else DacNative.BTN_LEFT
                DacNative.nativeSendMouse(
                    DacNative.MOUSE_MOVE or DacNative.MOUSE_BUTTON,
                    dx, dy, 0, 0f, 0f, true
                )
            }
            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                DacNative.nativeSendMouse(
                    DacNative.MOUSE_WHEEL, dx, dy, 0, 0f,
                    vScroll * 120f, true
                )
            }
        }
        return true
    }
}
