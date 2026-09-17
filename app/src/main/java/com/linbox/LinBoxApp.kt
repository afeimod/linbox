package com.linbox

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.preference.PreferenceManager
import com.linbox.data.prefs.SettingsStore
import com.linbox.core.theme.ThemeManager
import com.linbox.apps.x11.X11WindowController
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LoriePreferences
import com.termux.x11.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：初始化 ThemeManager、SettingsStore 等单例。
 *
 * 壳层为全屏页面（终端主页 / X11 / 设置），无浮动窗口系统。
 */
class LinBoxApp : Application() {

    lateinit var themeManager: ThemeManager
    lateinit var settingsStore: SettingsStore

    /**
     * v2.17 应用级协程作用域：生命周期与应用进程一致，不随任何窗口/组合销毁。
     *
     * 修复“选择图片自定义桌面壁纸不生效”：旧版在窗口内用
     * rememberCoroutineScope 启动 DataStore 写入后立即关窗，窗口组合销毁时
     * 协程被取消，写入随机丢失。涉及持久化的操作（设壁纸/锁屏壁纸等）
     * 一律改用本作用域。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * v2.22.2 fix9.6：终端侧 X server 的连接广播改由 X11WindowController 处理
     * （在 LinBox 桌面内弹出/聚焦 X11 窗口并完成渲染连接），不再拉起独立
     * 全屏 Activity（保留作兼容推障入口，可从等待页手动进入）。
     */
    private val x11LaunchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CmdEntryPoint.ACTION_START) {
                X11WindowController.onBroadcastReceived(context, intent)
            }
        }
    }

    /**
     * v2.22.2 fix9.6：X11 偏好默认值一次性迁移。
     *
     * fix9.6 改变三个默认值：全屏沉浸（fullscreen/hideCutout，修复被
     * 手机导航键遮挡）、默认关闭 X11 自带键盘栏（showAdditionalKbd /
     * additionalKbdVisible）。存量安装里这些键已被旧默认值落盘，仅改
     * Prefs 默认值不生效 —— 用 linboxDefaultsRev 标记一次性覆写。
     * 旧版偏好面板在 LinBox 中无入口，用户不可能手动改过这些键，覆写安全。
     */
    private fun migrateX11Defaults() {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (sp.getInt("linboxDefaultsRev", 0) >= 1) return
        sp.edit()
            .putInt("linboxDefaultsRev", 1)
            .putBoolean("fullscreen", true)
            .putBoolean("hideCutout", true)
            .putBoolean("showAdditionalKbd", false)
            .putBoolean("additionalKbdVisible", false)
            .apply()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        themeManager = ThemeManager(this)
        settingsStore = SettingsStore(this)

        // LinBox DAC（tools/merge-into-repo.sh 注入）：注入上下文并初始化。
        // 命令行链路：linbox-dac → am broadcast DAC_START → DacReceiver → DacApp
        com.linbox.apps.dac.DacApp.appContext = this
        com.linbox.apps.dac.DacApp.init(null)   // null = 兜底全屏覆盖层（挂前台 Activity decorView）


        // v2.22.2 fix9.6：X11 偏好提前就位 —— X11 窗口内的 LorieView
        // （onMeasure/getDimensionsFromSettings/onCreateInputConnection）
        // 读取静态 prefs，不等 X11 Activity 创建，避免 NPE 与测量错误。
        LoriePreferences.prefs = Prefs(this)
        migrateX11Defaults()

        // 内置 X11 界面：监听终端侧 X server 的连接广播
        registerReceiver(
            x11LaunchReceiver,
            IntentFilter(CmdEntryPoint.ACTION_START)
        )

        // v2.22.3 fix10：分辨率握手桥 —— 监听 glibc-runner 写入的
        // $PREFIX/tmp/.linbox-x11-res（-d WxH 虚拟桌面/全屏分辨率），
        // 让 X11 窗口的 X 屏幕真正按游戏分辨率创建（等比缩放显示）。
        com.linbox.apps.x11.X11ResolutionLink.start(this)

        // v2.22.2 X11 客户端宿主定位文件自愈：APK 升级后安装路径变化，
        // 每次启动在后台线程刷新 etc/linbox-x11.env（未装 bootstrap 时静默）
        applicationScope.launch(Dispatchers.IO) {
            com.linbox.apps.terminal.termux.TermuxBootstrapInstaller
                .refreshX11Env(this@LinBoxApp)
            // fix9.10：救援库就位保障（幂等）——与迁移路径互为备份，
            // 迁移异常中断时打开主界面一次仍可部署 etc/linbox/rescue
            com.linbox.apps.terminal.termux.TermuxBootstrapInstaller
                .ensureRescueLibs(this@LinBoxApp)
        }
    }

    companion object {
        lateinit var instance: LinBoxApp
            private set

        fun get(): LinBoxApp = instance

        fun get(context: Context): LinBoxApp =
            context.applicationContext as LinBoxApp
    }
}
