package com.linbox.core

/**
 * v3.4.0【性能大修 · AnWind 移植】：主界面 launcher 式降载开关。
 *
 * 背景（AnWind 用户实测：bionic 方式速度不到 proot 容器一半）：
 * LinBox 的 X11 页面与主界面（容器主页/高级页/浮动窗口）同栖一个
 * Activity/进程 —— X11 游戏真全屏时，被覆盖的主界面 Compose 树仍然
 * 活着：容器状态轮询（运行态 ● / 前缀构建进度）等周期性重组仍在
 * 跳动。这些主线程工作与 wine/box64 的游戏线程抢大核 —— 主界面成了
 * X11 运行时的常驻 CPU 竞争者。
 *
 * 语义对齐手机桌面（launcher）：launcher 被全屏应用覆盖即让出一切
 * 后台刷新 —— **X11 运行时才是前台主进程**，主界面按"桌面软件占用
 * 逻辑"退位：
 *  - 容器主页 3s 运行态刷新：真全屏期间不刷新（重组静止）
 *  - 其余周期性轮询：同上
 *
 * 退出全屏（返回键）瞬间恢复 —— [update] 由壳层（LinBoxShell /
 * X11 页面）按 X11 真全屏状态同步。
 *
 * 配套（同轮 v3.4.0 性能大修，已在移植链中落地）：
 *  - X11Support：exec app_process【之前】先启动 X11KeepAliveService
 *    前台服务，把 App 钉回前台态，fork 继承 top-app cgroup；
 *  - X11WindowOps：无 WM X 屏的 click-to-raise（点击置前）与窗口
 *    轮转（切窗），避免每次置前全屏往返。
 */
object PerfGovernor {

    /** X11 真全屏会话进行中 → 主界面进入降载态。 */
    @Volatile
    var quiesced: Boolean = false
        private set

    /** 主界面是否应暂停自刷新（容器状态等周期循环读此门禁）。 */
    fun shouldTick(): Boolean = !quiesced

    /** 由壳层按 X11 真全屏状态同步（SideEffect，主线程写）。 */
    fun update(x11TrueFullscreen: Boolean) {
        quiesced = x11TrueFullscreen
    }
}
