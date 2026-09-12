package com.linbox.apps.x11

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v2.23.0 fix28（配套 glibc-runner v3.12-linbox7 / extras rev38）——
 *               根治 proton 会话"收缩级联"（用户实测：wine-9.2 的 -d/-f
 *               都能铺满，proton 的 -d/-f 都右/下黑边 —— 差异根因）。
 *
 * fix28 定位的问题（2026-09-11 用户深采样 logcat 铁证，-d 与 -f 各一份）：
 *   proton 游戏自带"全屏管理器"：X 屏（root）每变化一次，它就把自己的
 *   窗口重设为"新 root 的 ~96%"（1280x720 root → 窗口 1232x693，恰为
 *   ×0.9625；root 缩到 1232x693 后窗口又变 1186x667 —— 同一比例）。
 *   旧贴合策略见"窗口≠X 屏"就把 X 屏缩到窗口尺寸（applyFit），于是：
 *   fit 缩屏 → proton 缩窗 → fit 再缩屏 → …… 级联 2-3 轮
 *   （1232x693 → 1186x667 → 1129x634），画面越缩越小、四周黑边。
 *   wine-9.2 对 root 变化无此反应（窗口不动），贴合一次即收敛 ——
 *   这就是"wine-9.2 两种模式都好、proton 两种模式都坏"的根源。
 *   显示层拉伸只能把"整张 X 屏"铺满 Android 窗口，root 内部黑边
 *   （窗口小于 root 的四周露黑）必须靠本客户端在 X 层解决。
 *
 * fix28 新决策策略（只动本文件决策环；握手协议 / -d / -f / -F 语义零变化）：
 *   1.【稳定门】窗口尺寸必须连续两轮观测一致（≈1.6s）才允许动 X 屏 ——
 *      绝不追着正在变化的窗口缩放 root（级联的引爆点）；
 *   2.【缩屏预算】每次握手（resetSession，glibc-runner 重写握手文件 /
 *      用户手动切分辨率时清零）applyFit 至多 2 次（开窗器→游戏两段式
 *      窗口刚好用完）—— 级联在预算层面不可能超过 2 轮；
 *   3.【对抗窗 + 钉满】applyFit 后 4s 内窗口再度缩水 = proton 对抗，
 *      立即"钉满"：把游戏窗口撑回覆盖整个 X 屏（moveResizeWindow，
 *      root 不动），游戏/DXVK 收 WM_SIZE 后按屏幕分辨率重绘 → 铺满；
 *      稳定但预算耗尽/窗口越界（异常小窗）时同样钉满兜底；
 *   4.【对抗上限 + 放手】钉满连续 4 次仍被改回 → 完全放手（连对齐也
 *      不动，仅节流日志），保持握手分辨率，绝不缩屏；新握手重置；
 *   5.【缩屏下限】贴合缩屏不得低于握手面积 50%（防异常小窗劫持 root；
 *      握手未知/跟随窗口会话跳过下限检查，行为同旧版）；
 *   6. 老游戏（DirectDraw 固定分辨率）路径不变：窗口稳定 + 预算内 →
 *      一次贴合缩屏 → 收敛（与 wine-9.2 实测有效路径一致）。
 *   （09-09 旧包 FATAL X11FitClient connectValidated 崩溃复核：现行代码
 *   主循环已整体 catch(Throwable)、connectValidated 逐 socket try/catch
 *   且协议解析全部判界，rev36+ 结构下不可复现，不再改动。）
 *
 * v2.22.5 fix15 —— X11 游戏窗口自适应铺满（根治"窗口化游戏四周黑边"）。
 * v2.22.5 fix16 —— 协议解析全面修正 + OOM 闪退根治。
 * v2.22.5 fix17 —— 自适应从"一次性"升级为"持续维持"，并扩展到跟随窗口会话。
 * v2.22.6 fix18 —— 协议偏移权威校正（4 处致命错位）+ -d 会话"窗口撑满屏幕"策略。
 * v2.22.6 fix19 —— 策略反转：贴合（X 屏贴游戏客户区+显示层拉伸）成为
 *               默认；撑满（resize 游戏窗口）降级为 -F/--fitwin 可选。
 *
 * fix19 修正的问题（用户实测 fix18 后：左/上黑边已消，但下/右仍黑，
 * 必须手动把分辨率改成和游戏一样才撑满）：
 *   撑满策略把游戏窗口 resize 到整个 X 屏幕后，老游戏（DirectDraw
 *   固定分辨率）"接受"了新窗口尺寸但仍在原分辨率区域（如 868x652）
 *   绘制 —— X 层检查"窗口==屏幕"判定已铺满（假稳定），贴合回退永远
 *   不触发，右/下残留未绘制黑区。X 协议层面无法感知窗口内容是否真被
 *   画满，因此撑满对老游戏不可靠 —— 仅保留为可选（现代游戏跟随
 *   WM_SIZE 重绘，原生分辨率渲染比 Upscale 清晰）。默认改用贴合：
 *   平移窗口到 (0,0) + X 屏幕缩成游戏客户区尺寸 + 显示层拉伸铺满，
 *   对任意窗口化游戏均无黑边（用户实测验证过此路径：手动把分辨率
 *   设成和游戏一样时能撑满 = 贴合的静态版本）。
 *
 * fix18 修正的问题（用户实测日志：root=0x0 ≠ 回调缓存 1024x768；
 * 未发现可铺满的游戏窗口——连续 10 秒；-d 1024x768 游戏窗口只有
 * 868x652、四周黑边；Alt+Enter 全屏却正常占满）：
 *   1.【Setup 屏幕尺寸读错位】原代码在 SCREEN 记录的 off+16/off+18 读
 *      宽高 —— 那里是 current_input_masks（4 字节，一般=0），真正的
 *      pixWidth/pixHeight 在 off+20/off+22（xcb_screen_t /
 *      Xproto.h xWindowRoot 均可验证）。后果：日志恒报 root=0x0，
 *      fallback 回写 screenW/H=0，自适应彻底失效；
 *   2.【窗口属性读错位】原代码在 r[30]/r[31] 读 map_state 和
 *      override_redirect —— 那里是 colormap 的第 2/3 字节（合法 XID
 *      高位字节，几乎从不等于 2/0）。真正的位置在 r[26]/r[27]
 *      （xcb_get_window_attributes_reply_t）。后果：所有窗口都被判
 *      "未映射 + override-redirect"，永远"未发现可铺满的游戏窗口"；
 *   3.【InternAtom 请求缺 padding】原请求在 name_len(2B) 后直接拼
 *      name —— 协议要求 name_len 后有 2 字节 pad，name 从偏移 8 开始
 *      （Xproto.h xInternAtomReq：nbytes@4、pad@6、name@8）。后果：
 *      服务器按偏移 8 解析出错位的原子名 → _NET_FRAME_EXTENTS 永远
 *      intern 不到 → 边框尺寸始终缺失；
 *   4.【MoveWindow 请求非法】ConfigureWindow 协议要求：window(4B) 后是
 *      value_mask(2B)+pad(2B)，随后每项参数占 4 字节值槽（xproto.h
 *      xConfigureWindowReq：mask@8、pad2@10、values@12 起）。原实现
 *      无 mask、x/y 直接以 INT16 拼在 window 后 —— 服务器把 x 的低
 *      16 位当掩码解析，请求长度也对不上 → MoveWindow 全部被拒，
 *      窗口永远纹丝不动；
 *   5.【-d 分辨率被窗口尺寸劫持】X 屏幕被 glibc-runner 握手设为
 *      1024x768 后，游戏窗口仍是它创建时（屏幕还没切换前）被 wine
 *      截到屏幕尺寸的 868x652 —— 旧策略会把 X 屏幕回缩到 868x652
 *      （用户要的 1024x768 没了）。现在：exactFromRunner（-d 握手或
 *      手动固定分辨率）会话改为把游戏窗口主动撑到整个 X 屏幕（客户
 *      区=1024x768，DXVK 重建交换链即恢复真实分辨率），游戏连续
 *      4 轮对抗性改回尺寸则退回旧贴合策略；跟随窗口会话维持旧策略。
 *   （QueryTree/GetGeometry/GetProperty 的偏移经同一套头文件核对
 *   确认原代码正确，未改动。）
 *
 * fix17 修正的问题（设备实测：固定 1024x768 时游戏 868x652 被 wine 居中、
 * 四周黑块；跟随窗口时仅上方/左侧黑块；日志只有"线程已启动"一条）：
 *   1. 【不回正】旧逻辑 lastFitWin 去重：同一窗口尺寸不变就永久跳过 ——
 *      但 X 屏幕一旦被外部改动（用户重设分辨率 / glibc-runner 握手重放 /
 *      wine Alt+Enter 切显示模式），wine 会把游戏窗口重新居中，旧逻辑
 *      视而不见 → 黑边回归且永不自愈。现在每轮校验"客户区左上角是否
 *      在 (0,0)"与"X 屏幕是否等于客户区"，不满足就重新平移/重设
 *      （幂等，稳定后零动作）；
 *   2. 【误判已铺满】旧 alreadyFull 只查 x<=0 && client>=root-8 —— 窗口
 *      摆在 (-5,-15) 也算"已铺满"。现在要求精确对齐（x==-left && y==-top）；
 *   3. 【大小不符即拒连】connectValidated 要求 root==回调缓存尺寸，一旦
 *      回调缓存与服务器失步就永远静默返回 null（日志只剩"线程已启动"的
 *      元凶）。现在全部 socket 尺寸不配时采用第一个活服务器并以其 root
 *      为准（回写 screenW/H），且所有静默路径补齐日志（无 socket /
 *      连接失败 / root 不符）；
 *   4. 【跟随窗口不支持】旧设计只在 exact 会话启用 —— 跟随窗口会话里
 *      游戏窗口被 wine 居中后上方/左侧露黑（用户截图 2）。现在两种会话
 *      都启用：跟随窗口会话 fit 时经 applyFit 把 X 屏幕定为游戏客户区
 *      （控制条如实显示 exact 值），显示层拉伸铺满，黑块消失；
 *   5. 【桌面会话保护】除游戏外还存在 ≥70% root 面积的映射窗口
 *      （xfdesktop/桌面环境）时不做 fit —— 桌面里开窗口化游戏不会被
 *      劫持整块屏幕；纯游戏会话（-d 或裸会话）无桌面窗口，fit 正常执行。
 *   （fix16 的协议逐字段修正说明见 git 历史；协议实现本轮未再改动。）
 *
 * 问题本质（fix11b~fix14 显示层修复后仍残留）：-d 模式下 X 屏幕分辨率由
 * 握手文件设定（如 1024x768），但窗口化游戏自己的窗口往往只有 868x652
 * 这类自选尺寸且被 wine 居中摆放 —— 用户看到的黑边是"游戏窗口之外的
 * root 黑背景"，烧在 X 画面内部。显示层拉伸（displayStretch）只能把
 * "整张 X 屏幕"拉伸铺满 Android 窗口，无法去除画面内部的黑边；
 * Alt+Enter/游戏内全屏又依赖游戏自身支持，不可控。
 *
 * 原理：X server（libXlorie）在 $PREFIX/tmp/.X11-unix/Xn 上监听标准 X11
 * 协议 —— wine 是它的客户，本对象也是一个客户（App 与 termux 同 UID，
 * socket 直接可连）。周期性 QueryTree 枚举根窗口的顶层窗口，找到最大的
 * 可见普通窗口（= 游戏），然后：
 *   1. 读 _NET_FRAME_EXTENTS（wine 把自己的 NC 边框尺寸写在窗口属性上：
 *      [left,right,top,bottom]；无此属性则不平移、按整窗适配）；
 *   2. 把游戏窗口【平移】到 (-left, -top) —— 标题栏/边框推出屏幕外，
 *      客户区恰好覆盖 X 屏幕原点。只平移不 resize（默认贴合），不触发
 *      游戏 WM_SIZE/D3D 重建，无"拉扯战斗"；
 *   3. 经 X11ResolutionLink.applyFit 把 X 屏幕尺寸设为客户区尺寸
 *      (w-left-right, h-top-bottom) —— 游戏客户区 = X 屏幕，
 *      显示层再拉伸铺满 Android 窗口，四边黑边彻底消失（fix19：
 *      -d/-native 全部会话默认此策略）；
 *   4. 之后每轮巡检【维持】该状态：wine 重新居中 → 重新平移；X 屏幕
 *      被外部改动 → 重新 applyFit。
 *
 * 撑满（可选，-F/--fitwin 握手标记 → X11ResolutionLink.windowStretch）：
 *   moveResizeWindow 把游戏窗口客户区一次到位地撑到整个 X 屏幕，
 *   游戏/DXVK 收到 WM_SIZE 后按目标分辨率重建渲染缓冲 —— 仅对能跟随
 *   WM_SIZE 重绘的现代游戏有效；连续 MAX_FIT_FIGHTS 轮窗口尺寸被游戏
 *   改回（对抗）则退回贴合。GLR_VD 虚拟桌面（-v）的会话里 VD 窗口
 *   本身就是屏幕尺寸，落在稳定态零动作。
 *
 * 启用范围：固定分辨率与跟随窗口会话均启用（fix19 起策略统一为贴合，
 * 撑满需 -F 显式请求）；已对齐铺满（真全屏游戏、GLR_VD 虚拟桌面）零
 * 动作；桌面环境存在时跳过。
 *
 * 协议实现说明：仅需 core protocol（Setup/QueryTree/GetWindowAttributes/
 * GetGeometry/InternAtom/GetProperty/MoveWindow），无扩展依赖。wine 无
 * WM 时窗口均为根窗口直接子窗口，枚举安全。
 */
object X11FitClient {
    private const val TAG = "X11FitClient"
    private const val PREFIX = "/data/data/com.linbox/files/usr"
    private const val POLL_MS = 800L

    /** fix18/fix19：撑满模式下游戏连续抗拒外部 resize 的轮数上限（超过则退回贴合策略） */
    private const val MAX_FIT_FIGHTS = 4

    /** fix28：每次握手（resetSession）允许的 applyFit 根调整次数上限
     *  （开窗器→游戏两段式窗口各一次；收缩级联在预算层面不可能超过 2 轮） */
    private const val MAX_ROOT_ADJUSTS = 2

    /** fix28：applyFit 之后多少 ms 内窗口再度缩水视为"游戏对抗"（→ 钉满） */
    private const val FIGHT_WINDOW_MS = 4000L

    /** fix28：对抗放弃后的巡检间隔（完全放手，仅节流日志） */
    private const val GIVEUP_POLL_MS = 5000L

    /** fix28：贴合缩屏下限 —— 客户区面积 < 握手面积/MIN_FIT_AREA_DEN 时不允许缩 root */
    private const val MIN_FIT_AREA_DEN = 2

    /** fix16：任何协议变长读取的上限（本客户端的回复都很小，超过即为 desync/异常值） */
    private const val MAX_BLOCK = 512 * 1024

    /** fix17：桌面环境守卫阈值 —— 除游戏外存在 ≥70% root 面积的映射窗口 → 视为桌面会话 */
    private const val DESKTOP_AREA_NUM = 7
    private const val DESKTOP_AREA_DEN = 10

    /** 当前 X 屏幕尺寸（"已铺满则跳过"判断；回调/applyFit/连接 fallback 三方同步） */
    @Volatile var screenW: Int = 0
    @Volatile var screenH: Int = 0

    // ---- fix28 决策状态（仅 fit 线程读写；resetSession 可跨线程重置） ----
    /** 握手分辨率（X11ResolutionLink.apply 登记；0=未知/跟随窗口 → 缩屏下限跳过） */
    @Volatile private var handshakeW = 0
    @Volatile private var handshakeH = 0
    /** 上一轮观测（稳定门：同窗口同尺寸连续两轮才允许动 root） */
    @Volatile private var lastWinId = 0L
    @Volatile private var lastW = 0
    @Volatile private var lastH = 0
    /** 本次握手已用的 applyFit 次数（≤ MAX_ROOT_ADJUSTS） */
    @Volatile private var rootAdjusts = 0
    /** 连续"钉满"被游戏改回的次数（≤ MAX_FIT_FIGHTS） */
    @Volatile private var pinFights = 0
    /** 上次 applyFit 时刻（对抗窗判定用，elapsedRealtime） */
    @Volatile private var lastFitAt = 0L
    /** 对抗放弃标记（放手后完全不动，直到 resetSession/铺满态复位） */
    @Volatile private var gaveUp = false

    /**
     * fix28：自适应会话重置 —— 新握手（glibc-runner 重写 .linbox-x11-res /
     * 用户手动切分辨率）或 fit 线程重启时调用：预算/对抗计数全部清零，
     * 重新自适应。握手未知（native/跟随窗口）传 0 → 缩屏下限自动跳过。
     */
    @Synchronized
    fun resetSession(hsW: Int, hsH: Int) {
        handshakeW = hsW; handshakeH = hsH
        lastWinId = 0L; lastW = 0; lastH = 0
        rootAdjusts = 0; pinFights = 0; lastFitAt = 0L; gaveUp = false
        Log.i(TAG, "自适应会话重置（握手 ${if (hsW > 0) "${hsW}x${hsH}" else "未知/跟随窗口"}）")
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var activeConn: XConn? = null

    /** fix17：节流日志（同类消息 10s 一条），静默路径全部可见又不刷屏 */
    private val lastLogAt = HashMap<String, Long>()
    private fun logT(key: String, msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastLogAt) {
            if (now - (lastLogAt[key] ?: 0L) < 10_000L) return
            lastLogAt[key] = now
        }
        Log.i(TAG, msg)
    }

    /** 仅游戏会话启用（exact/native 均可）。重复调用安全。 */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "X11FitClient").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "自适应铺满线程已启动（游戏会话）")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        thread = null
        runCatching { activeConn?.close() }
        activeConn = null
        Log.i(TAG, "自适应铺满线程已停止")
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun socketDirs(): List<File> = listOf(
        File("$PREFIX/tmp/.X11-unix"),
        File("$PREFIX/var/run/.X11-unix")
    )

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    // ============================================================
    // 主循环
    // ============================================================
    private fun loop() {
        var conn: XConn? = null

        while (running) {
            try {
                val sw = screenW
                val sh = screenH
                if (sw < 160 || sh < 120) { sleep(POLL_MS); continue }

                if (conn == null || !conn.alive()) {
                    runCatching { conn?.close() }
                    conn = XConn.connectValidated(sw, sh)
                    if (conn == null) { sleep(1500); continue }
                    activeConn = conn
                    // fix18：连接后重读一轮 screenW/H（fallback 可能已回写服务器
                    // 实际尺寸），避免本轮用连接前的旧值去适配。
                    continue
                }
                val c = conn!!

                val game = findGameWindow(c, sw, sh)
                if (game == null) {
                    // fix28：游戏窗口消失 → 稳定门观测作废（下一个新窗口从
                    // 第一轮观测重新起算，防止"同尺寸新窗口"被误判为稳定）
                    lastWinId = 0L; lastW = 0; lastH = 0
                    logT("nogame", "未发现可铺满的游戏窗口（等待中）")
                    sleep(POLL_MS); continue
                }

                // fix17 桌面环境守卫：桌面会话里窗口化游戏是"桌面上的一扇窗"，
                // 不能劫持整块屏幕；纯游戏会话（-d/裸会话）无桌面窗口不触发。
                if (game.desktopPresent) {
                    logT("desktop", "检测到桌面环境（存在大面积其他窗口），跳过自适应")
                    sleep(POLL_MS * 2); continue
                }

                // NC 边框：wine 会写 _NET_FRAME_EXTENTS；没有则整窗适配（不平移）
                val ext = c.getFrameExtents(game.id) ?: intArrayOf(0, 0, 0, 0)
                var L = ext[0]; var R = ext[1]; var T = ext[2]; var B = ext[3]
                // 防御：属性值异常（>=窗口尺寸）→ 退化为整窗适配
                if (L < 0 || R < 0 || T < 0 || B < 0 || L + R >= game.w || T + B >= game.h) {
                    L = 0; R = 0; T = 0; B = 0
                }

                val clientW = game.w - L - R
                val clientH = game.h - T - B
                if (clientW < 160 || clientH < 120) { sleep(POLL_MS); continue }

                // fix17 稳定铺满态：客户区左上角精确在 (0,0) 且 X 屏幕==客户区。
                // 真全屏游戏（wine RandR 切过屏幕）、GLR_VD 虚拟桌面都落在
                // 这一支 —— 零动作，不与 wine 抢权。
                val aligned = game.x == -L && game.y == -T
                val rootMatch = clientW == sw && clientH == sh
                // fix28 稳定门：同窗口同尺寸连续两轮（≈1.6s）才视为"已稳定"
                val stable = game.id == lastWinId && game.w == lastW && game.h == lastH

                if (aligned && rootMatch) {
                    pinFights = 0
                    gaveUp = false
                    lastWinId = game.id; lastW = game.w; lastH = game.h
                    sleep(POLL_MS); continue
                }

                // fix28 对抗放弃态：完全放手（连对齐也不动，避免继续打架），
                // 仅节流提示。恢复途径：游戏内全屏（Alt+Enter）/ -F 重启 /
                // 新握手（resetSession）/ 关闭重开会话。
                if (gaveUp) {
                    logT("giveup", "游戏持续对抗外部调整，自适应已放手" +
                        "（X 屏保持 ${sw}x${sh}；新握手或重启会话可重置）")
                    lastWinId = game.id; lastW = game.w; lastH = game.h
                    sleep(GIVEUP_POLL_MS); continue
                }

                // 对齐（只平移不改尺寸，零 WM_SIZE 风险）：把 NC 边框推出屏幕外，
                // 客户区左上角对齐 X 屏幕原点。
                if (!aligned) c.moveWindow(game.id, -L, -T)

                if (!rootMatch) {
                    val shrink = clientW < sw || clientH < sh
                    val areaOk = !shrink || handshakeW <= 0 || handshakeH <= 0 ||
                        clientW.toLong() * clientH * MIN_FIT_AREA_DEN >=
                        handshakeW.toLong() * handshakeH
                    val now = android.os.SystemClock.elapsedRealtime()
                    val inFightWindow = lastFitAt > 0 && now - lastFitAt < FIGHT_WINDOW_MS

                    if (X11ResolutionLink.windowStretch) {
                        // 撑满策略（-F/--fitwin 显式请求，语义同 fix18）：把游戏
                        // 窗口客户区一次到位撑到整个 X 屏幕（root 不动），现代
                        // 游戏收到 WM_SIZE/DXVK 重建交换链后按屏幕分辨率原生
                        // 渲染（比贴合的 Upscale 清晰）。fix28：连续
                        // MAX_FIT_FIGHTS 轮被游戏改回 → 放手（旧版此处会跌入
                        // 贴合缩屏 —— proton 对抗下正是收缩级联的入口）。
                        if (pinFights < MAX_FIT_FIGHTS) {
                            c.moveResizeWindow(game.id, -L, -T, sw + L + R, sh + T + B)
                            pinFights++
                            Log.i(TAG, "自适应(撑满): 窗口 ${game.w}x${game.h}@(${game.x},${game.y}) " +
                                "边框[$L,$R,$T,$B] → 客户区撑到 X 屏幕 ${sw}x${sh}" +
                                " [对抗 $pinFights/$MAX_FIT_FIGHTS]")
                            sleep(1200)
                        } else {
                            gaveUp = true
                            Log.w(TAG, "自适应放弃(撑满): 窗口持续被游戏改回，" +
                                "保持 X 屏幕 ${sw}x${sh} 不再调整（新握手或重启会话可重置）")
                            sleep(GIVEUP_POLL_MS)
                        }
                    } else if (stable && rootAdjusts < MAX_ROOT_ADJUSTS && areaOk) {
                        // 贴合策略（fix19 起默认；fix28 起须过稳定门+预算+下限）：
                        // 平移已在上方完成，X 屏幕贴成客户区尺寸，显示层拉伸
                        // 铺满 —— 老游戏（DirectDraw 固定分辨率）唯一可靠路径，
                        // wine-9.2 实测一次即收敛。
                        X11ResolutionLink.applyFit(clientW, clientH)
                        rootAdjusts++
                        lastFitAt = now
                        Log.i(TAG, "自适应(贴合): 窗口 ${game.w}x${game.h}@(${game.x},${game.y}) " +
                            "边框[$L,$R,$T,$B] → X屏幕=${clientW}x${clientH}" +
                            " [预算 ${MAX_ROOT_ADJUSTS - rootAdjusts}/${MAX_ROOT_ADJUSTS}]")
                        sleep(1200)
                    } else if (inFightWindow && pinFights < MAX_FIT_FIGHTS) {
                        // fix28 钉满（对抗响应）：贴合后 proton 的全屏管理器立刻
                        // 把窗口缩回 ~96%（对 root 变化的反应）—— 绝不追着缩
                        // root，反手把窗口撑回覆盖整个 X 屏（root 不动），游戏
                        // /DXVK 收 WM_SIZE 后按屏幕分辨率重绘 → 铺满。
                        c.moveResizeWindow(game.id, -L, -T, sw + L + R, sh + T + B)
                        pinFights++
                        Log.i(TAG, "自适应(钉满): 窗口 ${game.w}x${game.h}@(${game.x},${game.y}) " +
                            "贴合后被改回 → 窗口撑到 X 屏幕 ${sw}x${sh}（root 不动）" +
                            " [对抗 $pinFights/$MAX_FIT_FIGHTS]")
                        sleep(1200)
                    } else if (stable && pinFights < MAX_FIT_FIGHTS) {
                        // fix28 钉满（兜底）：窗口稳定但不匹配（缩屏预算耗尽/
                        // 面积下限拦截），X 屏保持不动，窗口撑到 X 屏。
                        c.moveResizeWindow(game.id, -L, -T, sw + L + R, sh + T + B)
                        pinFights++
                        Log.i(TAG, "自适应(钉满): 窗口 ${game.w}x${game.h} 稳定但不匹配 " +
                            "X 屏幕 ${sw}x${sh}（调整 ${rootAdjusts}/${MAX_ROOT_ADJUSTS}" +
                            "${if (areaOk) "" else " ·面积下限"}）→ 窗口撑到 X 屏幕" +
                            " [对抗 $pinFights/$MAX_FIT_FIGHTS]")
                        sleep(1200)
                    } else {
                        // fix28 放手：稳定窗口钉满 4 轮仍被改回，或窗口持续变化
                        // 且无可用动作 —— 保持握手分辨率，完全不再调整。
                        gaveUp = true
                        Log.w(TAG, "自适应放弃: 窗口 ${game.w}x${game.h} 持续不匹配 X 屏幕 " +
                            "${sw}x${sh}（贴合 ${rootAdjusts}/${MAX_ROOT_ADJUSTS} ·" +
                            " 钉满对抗 ${pinFights}/${MAX_FIT_FIGHTS}）—— 保持握手分辨率" +
                            "（新握手或重启会话可重置）")
                        sleep(GIVEUP_POLL_MS)
                    }
                } else {
                    // rootMatch 但未对齐：上方 moveWindow 已处理，等待生效。
                    sleep(POLL_MS)
                }

                // fix28：记录本轮观测（稳定门基准）。记录的是"动作前"读到的
                // 窗口几何 —— 下一轮读到的若与之相同，即连续两轮一致。
                lastWinId = game.id; lastW = game.w; lastH = game.h
            } catch (e: Throwable) {
                // fix16：捕获 Throwable —— 任何 Error（如 OOM）都不再杀死进程，
                // 退化为关闭连接 + 退避重试。
                if (running) Log.w(TAG, "自适应循环重试: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { conn?.close() }
                conn = null
                activeConn = null
                sleep(1500)
            }
        }
        runCatching { conn?.close() }
        activeConn = null
    }

    /** 候选窗口（游戏/其启动器）。desktopPresent=存在大面积"其他"映射窗口（桌面环境）。 */
    private class Win(val id: Long, val x: Int, val y: Int, val w: Int, val h: Int,
                      val desktopPresent: Boolean)

    private fun findGameWindow(c: XConn, sw: Int, sh: Int): Win? {
        val ids = c.queryTree(c.root)
        var bestId = 0L; var bx = 0; var by = 0; var bw = 0; var bh = 0
        var bestArea = 0L; var secondArea = 0L
        for (id in ids) {
            val a = c.getWindowAttributes(id) ?: continue
            if (!a.mapped || a.overrideRedirect) continue
            val g = c.getGeometry(id) ?: continue
            if (g.w < sw * 0.25 || g.h < sh * 0.25) continue  // 小窗口（对话框/任务栏类）不参与
            val area = g.w.toLong() * g.h
            if (area > bestArea) {
                secondArea = bestArea
                bestArea = area; bestId = id; bx = g.x; by = g.y; bw = g.w; bh = g.h
            } else if (area > secondArea) {
                secondArea = area
            }
        }
        if (bestId == 0L) return null
        // fix17：除最大窗口（游戏）之外，还有 ≥70% root 的映射窗口 → 桌面环境
        val desktop = secondArea * DESKTOP_AREA_DEN >= (sw.toLong() * sh) * DESKTOP_AREA_NUM
        return Win(bestId, bx, by, bw, bh, desktop)
    }

    // ============================================================
    // 极简 X11 客户端（core protocol）
    // ============================================================
    private class XConn private constructor(
        val sock: LocalSocket,
        val input: DataInputStream,
        val out: BufferedOutputStream
    ) {
        var seq = 0
        var root = 0L
        var rootW = 0
        var rootH = 0

        fun alive(): Boolean = runCatching { sock.fileDescriptor.valid() }.getOrDefault(false)

        fun close() {
            runCatching { sock.close() }
        }

        /** 组装请求：op@0, detail@1, len(words 含头)@2, 随后 body。序列号 16 位回绕。 */
        private fun newReq(op: Int, detail: Int, bodyBytes: Int): ByteBuffer {
            seq = (seq + 1) and 0xFFFF
            val total = 4 + bodyBytes
            val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(op.toByte())
            buf.put(detail.toByte())
            buf.putShort((total / 4).toShort())
            return buf
        }

        private fun send(buf: ByteBuffer) {
            out.write(buf.array())
            out.flush()
        }

        private val scratch = ByteArray(8192)

        private fun skipFully(n: Int) {
            var left = n
            while (left > 0) {
                val step = minOf(left, scratch.size)
                input.readFully(scratch, 0, step)
                left -= step
            }
        }

        /**
         * 读取直到"属于 expectedSeq 的回复"；返回 null = 本请求收到 X error。
         * fix16：按序列号匹配 —— 属于本请求的 reply 正常返回；迟到的 reply
         * /异步错误/事件按协议长度整包跳过；一切变长读取有 MAX_BLOCK 上限，
         * desync 也不再可能产生巨型分配。
         * X reply 布局：1B type + 1B detail + 2B seq + 4B length(4 字节单位)
         * + 24B（凑足 32B 基数）+ 4*length 附加数据。事件/错误固定 32B，
         * XGE(type=35) 变长 32+4n。
         */
        private fun readReply(expectedSeq: Int): ByteArray? {
            while (true) {
                val head = ByteArray(8)
                input.readFully(head)
                val code = head[0].toInt() and 0xFF
                val seq = u16(head, 2)
                val extra = (head[4].toInt() and 0xFF) or
                    ((head[5].toInt() and 0xFF) shl 8) or
                    ((head[6].toInt() and 0xFF) shl 16) or
                    ((head[7].toInt() and 0xFF) shl 24)
                val body = extra * 4
                when {
                    code == 0 -> {
                        // X error：固定 32B。属于本请求 → 请求失败（返回 null）
                        skipFully(24)
                        if (seq == expectedSeq) return null
                    }
                    code == 1 -> {
                        if (seq == expectedSeq) {
                            val base = ByteArray(24)
                            input.readFully(base)
                            if (extra > 0) {
                                if (body > MAX_BLOCK) throw IOException("reply 附加体过大: $body")
                                val rest = ByteArray(body)
                                input.readFully(rest)
                                return head + base + rest
                            }
                            return head + base
                        }
                        // 迟到/无关回复：整包跳过（32 + 4*len）
                        if (24 + body > MAX_BLOCK) throw IOException("无关 reply 过大: $body")
                        skipFully(24 + body)
                    }
                    code == 35 -> {
                        // XGE 变长事件：32 + 4*len
                        if (24 + body > MAX_BLOCK) throw IOException("XGE 事件过大: $body")
                        skipFully(24 + body)
                    }
                    else -> skipFully(24)   // 普通事件：固定 32B
                }
            }
        }

        fun internAtom(name: String): Long {
            val nb = name.toByteArray(Charsets.US_ASCII)
            // fix18：InternAtom 请求体 = name_len(2B) + pad(2B) + name + pad4
            // （Xproto.h xInternAtomReq：nbytes@4、pad@6、name 从偏移 8 开始）。
            // 原实现缺 2 字节 pad，name 错位到偏移 6 —— 服务器从偏移 8 解析
            // 出的原子名错位 → _NET_FRAME_EXTENTS 永远 intern 不到。
            val pad = (4 - nb.size % 4) % 4
            val buf = newReq(16, 1, 4 + nb.size + pad) // only-if-exists=1
            buf.putShort(nb.size.toShort())
            buf.putShort(0)                              // 协议要求的 2 字节 pad
            buf.put(nb)
            buf.put(ByteArray(pad))
            send(buf)
            val r = readReply(seq) ?: return 0L
            return ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).getInt(8).toLong() and 0xFFFFFFFFL
        }

        /** _NET_FRAME_EXTENTS → [left,right,top,bottom]；无属性返回 null。 */
        fun getFrameExtents(win: Long): IntArray? {
            val atom = internAtom("_NET_FRAME_EXTENTS")
            if (atom == 0L) return null
            val buf = newReq(20, 0, 20)
            buf.putInt(win.toInt()); buf.putInt(atom.toInt()); buf.putInt(0) // AnyPropertyType
            buf.putInt(0); buf.putInt(4) // long-offset 0, long-length 4
            send(buf)
            val r = readReply(seq) ?: return null
            val rb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
            // GetProperty 回复（xcb_get_property_reply_t）：format@r[1]
            // （回复头 detail 位，原 fix16 此项正确）、value_len@r[16]、
            // value 从 r[32] 起。
            val format = rb.get(1).toInt() and 0xFF
            val valueLen = rb.getInt(16)
            if (format != 32 || valueLen < 4 || r.size < 48) return null
            val v = ByteBuffer.wrap(r, 32, 16).order(ByteOrder.LITTLE_ENDIAN)
            return intArrayOf(v.int, v.int, v.int, v.int)
        }

        fun queryTree(win: Long): List<Long> {
            val buf = newReq(15, 0, 4)
            buf.putInt(win.toInt())
            send(buf)
            val r = readReply(seq) ?: return emptyList()
            val rb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
            val n = rb.getShort(16).toInt() and 0xFFFF
            if (32 + n * 4 > r.size) return emptyList()
            val out = ArrayList<Long>(n)
            for (i in 0 until n)
                out.add(rb.getInt(32 + i * 4).toLong() and 0xFFFFFFFFL)
            return out
        }

        class Attr(val mapped: Boolean, val overrideRedirect: Boolean)

        fun getWindowAttributes(win: Long): Attr? {
            val buf = newReq(3, 0, 4)
            buf.putInt(win.toInt())
            send(buf)
            val r = readReply(seq) ?: return null
            val rb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
            // GetWindowAttributes 回复（xcb_get_window_attributes_reply_t /
            //   Xproto.h xGetWindowAttributesReply，全包偏移）：
            //   visual@8 class@12 bit-grav@14 win-grav@15 planes@16 pixel@20
            //   save-under@24 map-installed@25 map-state@26 override@27
            //   colormap@28 all-masks@32
            // fix18：原读 30/31 —— 那是 colormap 的第 2/3 字节（合法 XID
            // 高位），所有窗口被判"未映射 + override-redirect"，
            // "未发现可铺满的游戏窗口"日志的直接元凶。
            val mapState = rb.get(26).toInt()
            val ovr = rb.get(27).toInt() != 0
            return Attr(mapState == 2, ovr)
        }

        class Geo(val x: Int, val y: Int, val w: Int, val h: Int)

        fun getGeometry(win: Long): Geo? {
            val buf = newReq(14, 0, 4)
            buf.putInt(win.toInt())
            send(buf)
            val r = readReply(seq) ?: return null
            val rb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
            return Geo(rb.getShort(12).toInt(), rb.getShort(14).toInt(),
                rb.getShort(16).toInt() and 0xFFFF, rb.getShort(18).toInt() and 0xFFFF)
        }

        /**
         * ConfigureWindow（只移动）：fix18 修正编码 ——
         * 协议要求 [12][pad][len][window(4)][value-mask(2)][pad(2)][x(4)][y(4)]，
         * mask=CWX|CWY=0x3，每项值占 4 字节槽（INT16 存低 16 位）。
         * 原实现无 mask、x/y 直接以 INT16 拼 —— 服务器把 x 低 16 位当
         * 掩码解析且请求长度不符 → 请求被拒，窗口永远不动。
         */
        fun moveWindow(win: Long, x: Int, y: Int) {
            // body 16B = window(4) + mask(2) + pad(2) + x(4) + y(4)；总 20B = 5 words
            val buf = newReq(12, 0, 16)
            buf.putInt(win.toInt())
            buf.putShort(0x0003)          // CWX | CWY
            buf.putShort(0)               // pad
            buf.putInt(x)
            buf.putInt(y)
            send(buf)
            // 无回复请求；可能的异步 error 由 readReply 的序列号跳过逻辑消化
        }

        /**
         * ConfigureWindow（移动+缩放）：fix18 新增 ——
         * mask=CWX|CWY|CWWidth|CWHeight=0xF，值槽 [x][y][w][h] 各 4 字节。
         * fix18 撑满策略用它把游戏窗口客户区一次到位地铺满整个 X 屏幕，
         * 游戏/DXVK 收到 WM_SIZE 后按目标分辨率重建渲染缓冲。
         */
        fun moveResizeWindow(win: Long, x: Int, y: Int, w: Int, h: Int) {
            // body 24B = window(4) + mask(2) + pad(2) + x/y/w/h(16)；总 28B = 7 words
            val buf = newReq(12, 0, 24)
            buf.putInt(win.toInt())
            buf.putShort(0x000F)          // CWX | CWY | CWWidth | CWHeight
            buf.putShort(0)               // pad
            buf.putInt(x)
            buf.putInt(y)
            buf.putInt(w)
            buf.putInt(h)
            send(buf)
        }

        companion object {
            /**
             * 连接并列出候选。原版严格要求 root==wantWxwantH（识别本会话
             * display），失步时永远静默 null（fix17 问题 3）。现在：
             * 优先连接尺寸匹配的 socket；全部不配时采用第一个活服务器，
             * 以服务器实际 root 为准并回写 screenW/H —— 回调缓存只是
             * 提示，服务器才是事实。
             */
            fun connectValidated(wantW: Int, wantH: Int): XConn? {
                var fallback: XConn? = null
                var sawSocket = false
                for (dir in socketDirs()) {
                    val files = dir.listFiles { f -> f.name.matches(Regex("X\\d+")) } ?: continue
                    for (f in files.sortedByDescending { it.name }) {
                        sawSocket = true
                        var sock: LocalSocket? = null
                        try {
                            sock = LocalSocket()
                            sock.connect(LocalSocketAddress(f.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                            sock.soTimeout = 4000
                            val input = DataInputStream(BufferedInputStream(sock.inputStream, 16384))
                            val out = BufferedOutputStream(sock.outputStream, 4096)

                            // setup 请求：byte-order 'l'，协议 11.0，无认证
                            val req = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                            req.put('l'.code.toByte()); req.put(0)
                            req.putShort(11); req.putShort(0)
                            req.putShort(0); req.putShort(0); req.putShort(0)
                            out.write(req.array()); out.flush()

                            // setup 回复：8B 前缀 [success, reasonLen, major(2),
                            // minor(2), 附加数据长(2)]（fix16：长度是 CARD16@6-7）
                            val head = ByteArray(8)
                            input.readFully(head)
                            if (head[0].toInt() != 1) { runCatching { sock.close() }; continue }
                            val words = u16(head, 6)
                            if (words <= 0 || words * 4 > MAX_BLOCK) {
                                Log.w(TAG, "setup 附加数据长度异常(${words} words) 前缀=" +
                                    head.joinToString("") { "%02x".format(it) } + "，跳过 ${f.name}")
                                runCatching { sock.close() }; continue
                            }
                            val rest = ByteArray(words * 4)
                            input.readFully(rest)
                            if (rest.size < 32) { runCatching { sock.close() }; continue }
                            val rb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
                            // 固定 32B：release@0 ridBase@4 ridMask@8 motion@12
                            // vendorLen@16 maxReq@18 nScreens@20 nFormats@21
                            // imageByteOrder@22 bitOrder@23 unit@24 pad@25
                            // minKey@26 maxKey@27 unused@28
                            val vendorLen = u16(rest, 16)
                            val nScreens = rest[20].toInt() and 0xFF
                            val nFormats = rest[21].toInt() and 0xFF
                            // SCREEN[0] = 32 + pad4(vendor) + 8*nFormats
                            val off = 32 + (vendorLen + 3) / 4 * 4 + nFormats * 8
                            if (nScreens < 1 || off + 36 > rest.size) {
                                Log.w(TAG, "setup 结构越界 off=$off size=${rest.size} " +
                                    "vendor=$vendorLen fmt=$nFormats scr=$nScreens，跳过 ${f.name}")
                                runCatching { sock.close() }; continue
                            }
                            val root = rb.getInt(off).toLong() and 0xFFFFFFFFL
                            // SCREEN 记录（xcb_screen_t / Xproto.h xWindowRoot）：
                            //   root@+0 cmap@+4 white@+8 black@+12
                            //   current-input-masks@+16(4B) pixWidth@+20 pixHeight@+22
                            //   mmW@+24 mmH@+26 …
                            // fix18：原在 off+16/off+18 读宽高 —— 读到的是
                            // current_input_masks（通常=0）→ 日志恒报 root=0x0、
                            // fallback 回写 screenW/H=0。真正 pixWidth/pixHeight
                            // 在 off+20/off+22。
                            val w = rb.getShort(off + 20).toInt() and 0xFFFF
                            val h = rb.getShort(off + 22).toInt() and 0xFFFF
                            val c = XConn(sock, input, out)
                            c.root = root; c.rootW = w; c.rootH = h
                            if (w == wantW && h == wantH) {
                                Log.i(TAG, "已连接 X server: ${f.name} root=${w}x${h} " +
                                    "rootWin=0x${root.toString(16)}")
                                return c
                            }
                            Log.i(TAG, "socket ${f.name}: root=${w}x${h} ≠ 回调缓存 " +
                                "${wantW}x${wantH}，暂存备选（以服务器为准）")
                            if (fallback == null) fallback = c
                            else runCatching { c.close() }
                        } catch (e: Exception) {
                            Log.w(TAG, "连接 ${f.absolutePath} 失败: ${e.message}")
                            runCatching { sock?.close() }
                        }
                    }
                }
                fallback?.let { c ->
                    // fix18：服务器尺寸合法才回写本地缓存（防御性 —— 解析已
                    // 修正，此处只在真实失步时兜底，绝不再把 0x0 写进缓存）。
                    if (c.rootW >= 160 && c.rootH >= 120) {
                        Log.w(TAG, "无 root==${wantW}x${wantH} 的 display，采用实际 root " +
                            "${c.rootW}x${c.rootH}（服务器为准，回写本地缓存）")
                        screenW = c.rootW
                        screenH = c.rootH
                    } else {
                        Log.w(TAG, "实际 root ${c.rootW}x${c.rootH} 尺寸异常，保持回调缓存 " +
                            "${wantW}x${wantH}（等待服务器就绪）")
                    }
                    return c
                }
                if (!sawSocket) {
                    logT("nosock", "未发现 X socket（$PREFIX/tmp/.X11-unix/Xn）—— X server 未启动？")
                }
                return null
            }
        }
    }
}

/**
 * v2.22.5 fix15 —— X11 会话清理：关闭 X11 桌面窗口时终止 wine。
 * App 与 termux 环境（/data/data/com.linbox）同 UID，直接以 termux 的
 * bash 执行清理：先 TERM wineserver（wine 随之正常收尾所有 Windows
 * 进程），再 KILL 兜底残留的 wine-preloader/.exe 进程。
 */
object X11Session {
    private const val TAG = "X11Session"
    private const val PREFIX = "/data/data/com.linbox/files/usr"

    @Volatile private var lastKillAt = 0L

    /** 关闭 X11 窗口时调用（最小化不触发）。幂等防抖，后台执行。 */
    fun killWine() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastKillAt < 2000) return
        lastKillAt = now

        Thread({
            try {
                val bash = File("$PREFIX/bin/bash")
                if (!bash.exists()) {
                    Log.w(TAG, "termux bash 不存在，跳过 wine 清理")
                    return@Thread
                }
                // ${'$'}2 为 awk 的 $2（Kotlin 模板转义）
                val script = """
                    ps -ef 2>/dev/null | grep -E 'wineserver|wine-preloader|wine64-preloader|\.exe' | grep -v grep | awk '{print ${'$'}2}' | xargs -r kill 2>/dev/null
                    sleep 1
                    ps -ef 2>/dev/null | grep -E 'wineserver|wine-preloader|wine64-preloader|\.exe' | grep -v grep | awk '{print ${'$'}2}' | xargs -r kill -9 2>/dev/null
                    echo "[LinBox] X11 窗口已关闭，wine 会话已清理"
                """.trimIndent()
                val p = ProcessBuilder(bash.absolutePath, "-c", script)
                p.redirectErrorStream(true)
                val out = p.start().inputStream.bufferedReader().readText()
                Log.i(TAG, "wine 清理完成: ${out.lineSequence().lastOrNull()?.trim() ?: "ok"}")
            } catch (e: Exception) {
                Log.w(TAG, "wine 清理失败: ${e.message}")
            }
        }, "X11WineKill").apply { isDaemon = true; start() }
    }
}
