package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.winhandler.WinHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;

/**
 * LinBox（v2.22.3 fix10）：X11 输入中枢（app 进程级单例）。
 *
 * 解决三个问题：
 * 1. 虚拟手柄只在兼容全屏 Activity 里有半套实现（touch 路由缺失、
 *    profile 永远不会被加载），浮动窗口（X11Surface）则完全没有。
 *    本中枢统一管理 WinHandler（wine 侧 winhandler.exe 的 UDP 对端）
 *    与"当前活跃手柄层"，浮动窗口 / 全屏 Activity 共享同一实例；
 * 2. WinHandler 原先强绑定 MainActivity 且每次 onCreate 新建、onDestroy
 *    关闭 —— 全屏 Activity 退出会把浮动窗口的手柄链路一起带走（两个
 *    实例抢 7947 端口）。现由本中枢持有，随 app 进程存活，幂等启动；
 * 3. 手柄 profile 的加载/记忆：把用户选过的 profile id 存在默认
 *    SharedPreferences（linbox_gamepad_profile_id），下次开启直接恢复。
 *
 * 注意：本类不做任何触摸事件路由 —— 路由由各宿主完成
 * （全屏 Activity：MainActivity.dispatchTouchEvent；
 *   浮动窗口：X11Surface 的 SmartTouchBridge）。
 */
public class X11InputHub implements WinHandler.Host {
    private static final String TAG = "X11InputHub";
    private static final String KEY_PROFILE_ID = "linbox_gamepad_profile_id";

    private static volatile X11InputHub sInstance;

    public static X11InputHub get(Context context) {
        if (sInstance == null) {
            synchronized (X11InputHub.class) {
                if (sInstance == null)
                    sInstance = new X11InputHub(context.getApplicationContext());
            }
        }
        return sInstance;
    }

    private final Context appContext;
    private WinHandler winHandler;
    private InputControlsManager controlsManager;
    /** 当前活跃手柄层（浮动窗口或全屏 Activity 的 InputControlsView）。 */
    private volatile InputControlsView activeControlsView;

    /**
     * LinBox（v2.22.3 fix11b）：当前活跃的 X11 渲染视图（LorieView）。
     * 桌面虚拟手柄（GamepadController）的按键/鼠标经本引用直注 X ——
     * 手柄由此作用于 X11 界面（wine/游戏），不再依赖 Winlator 手柄层。
     */
    private volatile LorieView activeLorieView;
    /** 已转发到 X 且仍按着的键（X11 窗口销毁时统一补发 UP，防卡键）。 */
    private final java.util.Set<Integer> forwardedDownKeys = new java.util.HashSet<>();

    private X11InputHub(Context appContext) {
        this.appContext = appContext;
    }

    /**
     * wine 侧游戏手柄通道（UDP 7947 对端），幂等启动。
     *
     * LinBox hotfix：start() 内含 InetAddress.getLocalHost()（DNS 解析，
     * 属 StrictMode 网络操作）与 DatagramSocket bind —— 若在主线程调用会抛
     * NetworkOnMainThreadException 直接闪退（X11 桌面浮动窗口 factory 与
     * 全屏 Activity onCreate 都在主线程取用本方法，用户实测"打开 X11 桌面
     * app 闪退"即此）。这里必须切后台线程启动；调用方（无论主线程还是
     * 后台线程）拿到的都是已创建的 WinHandler 实例，可以立刻 setWinHandler 接线，
     * UDP 收发在其自身线程就绪后自然生效。
     */
    public synchronized WinHandler getWinHandler() {
        if (winHandler == null) {
            WinHandler wh = new WinHandler(this);
            winHandler = wh;
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    wh.start();
                    Log.i(TAG, "WinHandler 已启动（UDP 7947，浮动窗口/全屏共享）");
                } catch (Throwable t) {
                    Log.e(TAG, "WinHandler 启动失败（不影响 X11 桌面其它功能）", t);
                }
            });
        }
        return winHandler;
    }

    /** 宿主（浮动窗口 / 全屏 Activity）注册当前活跃手柄层；null = 注销。 */
    public void setActiveControlsView(InputControlsView view) {
        activeControlsView = view;
    }

    // ============================================================
    // LinBox（v2.22.3 fix11b）：桌面虚拟手柄 → X11 输入桥
    // ============================================================

    /**
     * X11 宿主注册/注销活跃渲染视图（X11Surface factory 创建时注册，
     * 窗口关闭时传 null）。注销时对仍按着的键补发 UP，防止游戏卡键。
     */
    public void setActiveLorieView(LorieView view) {
        LorieView old = activeLorieView;
        if (old != null && old != view) {
            synchronized (forwardedDownKeys) {
                for (int kc : forwardedDownKeys) {
                    try { old.sendKeyEvent(0, kc, false); } catch (Throwable ignored) {}
                }
                forwardedDownKeys.clear();
            }
        }
        activeLorieView = view;
    }

    /** X11 是否可接收手柄转发（活跃视图已连接 X server）。 */
    public boolean isX11ForwardAvailable() {
        LorieView v = activeLorieView;
        return v != null && LorieView.connected();
    }

    /** 手柄键盘事件直注 X。返回 false = 无 X11 目标（调用方回落原路径）。 */
    public boolean forwardKeyToX(int keyCode, boolean down) {
        LorieView v = activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendKeyEvent(0, keyCode, down);
            synchronized (forwardedDownKeys) {
                if (down) forwardedDownKeys.add(keyCode);
                else forwardedDownKeys.remove(keyCode);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手柄鼠标按键直注 X（在当前 X 指针位置按下/抬起）。 */
    public boolean forwardMouseButtonToX(int button, boolean down) {
        LorieView v = activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendMouseEvent(0f, 0f, button, down, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手柄滚轮直注 X（deltaY 正 = 内容下滚）。 */
    public boolean forwardWheelToX(float deltaY) {
        LorieView v = activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendMouseWheelEvent(0f, deltaY);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 静态便捷入口：桌面手柄（app 模块 GamepadController）调用。 */
    public static boolean forwardKey(int keyCode, boolean down) {
        X11InputHub h = sInstance;
        return h != null && h.forwardKeyToX(keyCode, down);
    }

    /**
     * v2.22.5 fix14：向 X 发送 Alt+Enter 组合键 —— wine/wined3d/DXVK 的
     * 全屏切换标准键。窗口化游戏（如 820x612 带标题栏居中、四周露 root
     * 黑背景）一键切到全屏：DXVK/wine 请求 ChangeDisplaySettings → X server
     * 经 RandR(ProcRRSetScreenConfig) 把 X 屏幕切成游戏分辨率 → 游戏铺满
     * root，显示层 displayStretch 再把画面拉伸铺满 Android 窗口 ——
     * 黑边彻底消失。
     */
    public static boolean sendAltEnter() {
        X11InputHub h = sInstance;
        LorieView v = h == null ? null : h.activeLorieView;
        if (v == null || !LorieView.connected()) return false;
        try {
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, true);
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ENTER, true);
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ENTER, false);
            v.sendKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendAltEnter failed", t);
            return false;
        }
    }

    public static boolean forwardMouseButton(int button, boolean down) {
        X11InputHub h = sInstance;
        return h != null && h.forwardMouseButtonToX(button, down);
    }

    public static boolean forwardWheel(float deltaY) {
        X11InputHub h = sInstance;
        return h != null && h.forwardWheelToX(deltaY);
    }

    public static boolean isX11ForwardReady() {
        X11InputHub h = sInstance;
        return h != null && h.isX11ForwardAvailable();
    }

    @Override
    public InputControlsView getInputControlsView() {
        return activeControlsView;
    }

    public synchronized InputControlsManager getControlsManager() {
        if (controlsManager == null)
            controlsManager = new InputControlsManager(appContext);
        return controlsManager;
    }

    /** 记住用户选择的手柄 profile（下次开启自动恢复）。 */
    public void rememberProfile(ControlsProfile profile) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(appContext);
        sp.edit().putInt(KEY_PROFILE_ID, profile != null ? profile.id : 0).apply();
    }

    public int getRememberedProfileId() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(appContext);
        return sp.getInt(KEY_PROFILE_ID, 0);
    }

    /**
     * 恢复记忆的（或第一个可用的）非模板 profile；没有可用 profile 返回 null。
     */
    public ControlsProfile pickProfile() {
        ArrayList<ControlsProfile> profiles = getControlsManager().getProfiles(true);
        if (profiles == null || profiles.isEmpty()) return null;

        int remembered = getRememberedProfileId();
        for (ControlsProfile p : profiles)
            if (p.id == remembered) return p;

        return profiles.get(0);
    }

    /**
     * 开/关虚拟手柄层（浮动窗口与全屏 Activity 共用）。
     *
     * @param controlsView 目标手柄层视图
     * @param enable       true=显示并加载 profile；false=隐藏并卸载
     * @return 实际生效的 profile（关闭时返回 null）
     */
    public ControlsProfile setGamepadEnabled(InputControlsView controlsView, boolean enable) {
        if (controlsView == null) return null;
        if (!enable) {
            controlsView.setProfile(null);
            controlsView.setVisibility(android.view.View.GONE);
            controlsView.invalidate();
            setActiveControlsView(null);
            return null;
        }

        ControlsProfile profile = pickProfile();
        setActiveControlsView(controlsView);
        controlsView.setShowTouchscreenControls(true);
        controlsView.setVisibility(android.view.View.VISIBLE);
        controlsView.requestFocus();
        controlsView.setProfile(profile);
        controlsView.invalidate();
        if (profile != null)
            rememberProfile(profile);
        Log.i(TAG, "虚拟手柄 " + (profile != null ? "已启用: " + profile.getName() : "无可用 profile"));
        return profile;
    }

    /** 重新加载 profile 列表（编辑器保存后调用）。 */
    public void reloadProfiles() {
        getControlsManager().loadProfiles(true);
    }

    /** profile 文件目录（供编辑器/导入导出使用）。 */
    public File getProfilesDir() {
        return InputControlsManager.getProfilesDir(appContext);
    }
}
