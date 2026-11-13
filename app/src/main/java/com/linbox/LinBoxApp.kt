package com.linbox

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.preference.PreferenceManager
import androidx.room.Room
import com.linbox.data.db.AppDatabase
import com.linbox.data.prefs.SettingsStore
import com.linbox.core.theme.ThemeManager
import com.linbox.apps.containers.launcher.ProcessCleanup
import com.linbox.apps.x11.X11WindowController
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LoriePreferences
import com.termux.x11.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：初始化 Room DB、ThemeManager、SettingsStore 等单例。
 *
 * LinBox 容器化改造（AnWind 全量移植）：在原有终端 + X11 + DAC 壳层之上
 * 叠加 Wine 容器系统 —— bionic rootfs 自 assets 铺装、容器窗口应用注册、
 * 上次进程死亡遗留 wine 孤儿树清扫。
 */
class LinBoxApp : Application() {

    /** Room 数据库（浏览器书签/历史、容器运行快捷方式）。 */
    lateinit var database: AppDatabase
    lateinit var themeManager: ThemeManager
    lateinit var settingsStore: SettingsStore

    /**
     * v2.17 应用级协程作用域：生命周期与应用进程一致，不随任何窗口/组合销毁。
     *
     * 修复“选择图片自定义桌面壁纸不生效”：旧版在窗口内用
     * rememberCoroutineScope 启动 DataStore 写入后立即关窗，窗口组合销毁时
     * 协程被取消，写入随机丢失。涉及持久化的操作一律改用本作用域。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * v2.22.2 fix9.6：终端侧 X server 的连接广播改由 X11WindowController 处理
     * （跳转 LinBox 的全屏 X11 页面并完成渲染连接），不再拉起独立
     * 全屏 Activity（保留作兼容推障入口，可从等待页手动进入）。
     * wine 容器启动链（X11Support）拉起的 X 服务同样经此通路自动上屏。
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

        // v6.4：前台 Activity 跟踪（X11MiniWindow 迷你浮窗需要 decorView
        // 宿主；与 DacApp 内部回调同款方案，提升为全局能力）。
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {
                topActivity = activity
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (topActivity === activity) topActivity = null
            }
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (topActivity === activity) topActivity = null
            }
        })

        // v3.0 原生启动链配套（AnWind 移植）：清扫上次进程死亡遗留的
        // wine 孤儿树（wineserver / wine-preloader / 各 .exe / box64-wine）。
        // 残留的 wineserver 会让新 wine 客户端连上旧会话 ——
        // "第一次能启动，之后再也起不来"的根因；后台线程执行，
        // 扫描 /proc 开销毫秒级。
        applicationScope.launch(Dispatchers.IO) {
            runCatching { ProcessCleanup.cleanup() }
        }

        // Room 数据库（浏览器书签/历史 + 容器运行快捷方式）
        // v6.2：注册显式迁移（shortcuts 加 config 列）—— 显式迁移优先于
        // fallbackToDestructiveMigration 生效，存量数据不丢。
        database = Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

        themeManager = ThemeManager(this)
        settingsStore = SettingsStore(this)

        // LinBox DAC（tools/merge-into-repo.sh 注入）：注入上下文并初始化。
        // 命令行链路：linbox-dac → am broadcast DAC_START → DacReceiver → DacApp
        com.linbox.apps.dac.DacApp.appContext = this
        com.linbox.apps.dac.DacApp.init(null)   // null = 兜底全屏覆盖层（挂前台 Activity decorView）

        // v2.22.2 fix9.6：X11 偏好提前就位 —— X11 页面内的 LorieView
        // （onMeasure/getDimensionsFromSettings/onCreateInputConnection）
        // 读取静态 prefs，不等 X11 Activity 创建，避免 NPE 与测量错误。
        LoriePreferences.prefs = Prefs(this)
        migrateX11Defaults()

        // 注册窗口化应用（容器管理器 / exe 选择器 / 浏览器 / 文件管理器）
        com.linbox.apps.AppBootstrap.registerAll()

        // 内置 X11 界面：监听终端侧 X server 与容器启动链 X 服务的连接广播
        registerReceiver(
            x11LaunchReceiver,
            IntentFilter(CmdEntryPoint.ACTION_START)
        )

        // v2.22.3 fix10：分辨率握手桥 —— 监听 glibc-runner / 容器启动链写入的
        // $PREFIX/tmp/.linbox-x11-res（-d WxH 虚拟桌面/全屏分辨率），
        // 让 X11 页面的 X 屏幕真正按游戏分辨率创建（等比缩放显示）。
        com.linbox.apps.x11.X11ResolutionLink.start(this)

        // v2.22.2 X11 客户端宿主定位文件自愈：APK 升级后安装路径变化，
        // 每次启动在后台线程刷新 etc/linbox-x11.env（未装 bootstrap 时静默）
        applicationScope.launch(Dispatchers.IO) {
            try {
                com.linbox.apps.terminal.termux.TermuxBootstrapInstaller
                    .refreshX11Env(this@LinBoxApp)
                // fix9.10：救援库就位保障（幂等）——与迁移路径互为备份，
                // 迁移异常中断时打开主界面一次仍可部署 etc/linbox/rescue
                com.linbox.apps.terminal.termux.TermuxBootstrapInstaller
                    .ensureRescueLibs(this@LinBoxApp)
                // v2.26 rev40 [A1]（AnWind 移植）：内置 tzst 资产自解压（幂等）——
                // rootfs/mesa/box64/turnip 按指纹铺装到 bionic rootfs；
                // dxvk 仅对"已构建"的 wine 前缀解压到 drive_c/windows
                // （未构建的前缀自动等待，前缀就绪后的首次启动/进容器
                // 界面即补齐），不预创建任何前缀目录。
                com.linbox.apps.terminal.termux.LinBoxTzstAssets
                    .installAllIfNeeded(this@LinBoxApp)
            } catch (t: Throwable) {
                // v2.24.1 闪退修复：启动期后台铺装是"尽力而为"的幂等操作，
                // 任何失败（含 UnsatisfiedLinkError 等 Error 级——Exception
                // 捕不到）都必须降级为日志，绝不允许在 Application.onCreate
                // 杀死主进程；指纹未落盘的资产会在下次启动自动重试。
                android.util.Log.e(
                    "LinBoxApp",
                    "启动期 bootstrap/tzst 铺装失败（已降级，下次启动重试）",
                    t
                )
            }
        }
    }

    companion object {
        lateinit var instance: LinBoxApp
            private set

        /** v6.4：当前前台 Activity（迷你浮窗挂 decorView 用；无前台时 null）。 */
        @Volatile var topActivity: android.app.Activity? = null
            private set

        fun get(): LinBoxApp = instance

        fun get(context: Context): LinBoxApp =
            context.applicationContext as LinBoxApp
    }
}
