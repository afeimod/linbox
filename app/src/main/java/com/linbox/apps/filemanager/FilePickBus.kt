package com.linbox.apps.filemanager

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 跨窗口文件选择总线（v2.14.10）。
 *
 * 背景：记事本「打开」与媒体播放器「打开文件」旧版调用系统文件选择器
 * （SAF / ACTION_OPEN_DOCUMENT），会跳出应用到手机自带的文件管理器，
 * 与 LinBox 桌面环境割裂。v2.14.10 起统一改为：
 * 应用内拉起【文件资源管理器】窗口（launchArgs.pickMode = "text"/"media"，
 * targetApp = "notepad"/"media_player"，targetWindow = 发起窗口 id），
 * 用户在资源管理器里点选文件后，经本总线把文件路径精确投递回发起窗口。
 *
 * 用法：
 * - 目标应用（记事本/媒体播放器）进入组合时 [listen] 注册回调
 *   （携带自身 appId 与 windowId），离开组合/窗口关闭时调用返回的注销函数；
 * - 文件资源管理器选择模式下点击文件 → [deliver]；
 *   返回 false 表示无匹配监听者（目标窗口已关闭/最小化离开组合），
 *   调用方自行兜底（如直接开新窗口并传 path）。
 *
 * 线程安全：CopyOnWriteArrayList，注册/投递均可在任意线程。
 */
object FilePickBus {

    private class Listener(
        val targetApp: String,
        val targetWindow: String,
        val onPick: (String) -> Unit
    )

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * v6.1 待投递信箱：发起页导航离开（去文件管理器选文件的间隙）期间的
     * 选择结果，按 targetApp 暂存；发起页重新组合（listen 重注册）时取出投递。
     *
     * 背景：v6.0 全屏页化后，发起页（如游戏库添加游戏弹窗）在跳转
     * 文件管理器时离开组合 → 监听器注销 → 选完文件 deliver 落空 →
     * “发起页面已离开，已取消选择”。信箱把结果保留到发起页归来。
     */
    private val pending = ConcurrentHashMap<String, String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 注册文件选择回调。
     *
     * @param targetApp 目标应用 id（"notepad" / "media_player"），
     *                  与资源管理器窗口 launchArgs["targetApp"] 对应
     * @param targetWindow 发起窗口 id（资源管理器 launchArgs["targetWindow"]），
     *                  精确路由到发起窗口，多个同类窗口并存时不串扰；
     *                  传空串则匹配该应用的任意监听者
     * @param onPick    收到所选文件绝对路径（主线程投递）
     * @return 注销函数（窗口离开组合/关闭时调用，防泄漏）
     */
    fun listen(
        targetApp: String,
        targetWindow: String,
        onPick: (String) -> Unit
    ): () -> Unit {
        val l = Listener(targetApp, targetWindow, onPick)
        listeners.add(l)
        // v6.1：发起页去而复返 —— 取出待投递的选择结果立即送达
        // （post 到主线程，避免在组合期内同步写状态）
        pending.remove(targetApp)?.let { path ->
            mainHandler.post { onPick(path) }
        }
        return { listeners.remove(l) }
    }

    /**
     * 投递所选文件路径。
     *
     * @param targetApp 目标应用 id
     * @param targetWindow 目标窗口 id（空串 = 该应用任意监听者）
     * @return true = 已有监听者接收，或已转入待投递信箱（发起页归来即送达）；
     *         false = 无匹配且未暂存
     */
    fun deliver(targetApp: String, targetWindow: String, path: String): Boolean {
        var handled = false
        listeners.forEach {
            val appMatch = it.targetApp == targetApp
            val winMatch = targetWindow.isEmpty() || it.targetWindow == targetWindow
            if (appMatch && winMatch) {
                it.onPick(path)
                handled = true
            }
        }
        if (!handled && targetApp.isNotBlank()) {
            // v6.1：发起页暂离（导航去文件管理器的间隙）—— 暂存而非丢弃，
            // 待发起页重新组合（listen 重注册）时投递，选择不再丢失
            pending[targetApp] = path
            handled = true
        }
        return handled
    }
}
