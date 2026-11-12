package com.linbox.apps.x11

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.termux.x11.LorieView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * v3.2.1（用户问题 1）—— X 层窗口管理助手：无 WM 的 X 屏幕上的
 * "点击置前"（click-to-raise）与"窗口轮转"。
 *
 * ## 背景
 *
 * LinBox 的 X 服务（termux-x11/libXlorie）**没有窗口管理器**。多个
 * wine 会话同时运行时（每个会话各自包一层 `explorer /desktop=LinBox,WxH`
 * 虚拟桌面、铺满整个 X 屏幕），它们的顶层窗口在根窗口上互相堆叠：
 *
 * - X server 把指针事件投递给"该位置最顶层"的窗口 —— 点击后面窗口的
 *   **可见部分**事件其实能到达（应用会反应），但没有任何人负责把它
 *   **提到前面**（raise 是 WM 的职责）→ 用户看到"点了没反应、窗口
 *   一直在后面"；
 * - 后面的窗口被**完全遮挡**时，点击永远落在前面的窗口上 —— 后面的
 *   会话既无法置前也无法操作（连任务栏都救不了：LinBox 任务栏管理的是
 *   Compose 窗口，而全部 wine 会话共享同一个『X11 桌面』窗口）。
 *
 * ## 方案
 *
 * 在 App 侧直接说 X 协议（与 X11FitClient 同源的极简 core-protocol
 * 客户端，独立连接、独立线程，绝不与 fit 线程共享 socket）：
 *
 * 1. [raiseAt] —— 触摸点击落点处的"点击置前"：枚举根窗口子窗口
 *    （QueryTree 返回**自底向上**的堆叠序），从顶层向下找第一个
 *    "已映射 + 非 override-redirect + 几何包含落点"的窗口；若它不是
 *    当前最顶层 → ConfigureWindow(StackMode=Above) + SetInputFocus ——
 *    即 Windows 的 click-to-raise 语义。点在最顶层窗口上时是零动作
 *    （不会扰动 X11FitClient 的贴合状态）。
 *
 * 2. [cycleTopWindow] —— 完全遮挡时的"窗口轮转"：把堆叠序中**紧挨着
 *    当前顶层之下**的那个窗口提到最前。反复按即可在多个 wine 会话间
 *    轮转切换（X11 窗口控制条『切窗』按钮）。
 *
 * wine 会话内部的子窗口（虚拟桌面里的游戏窗口/对话框）由 wine 自己的
 * explorer 桌面管理，不经本对象 —— 本对象只处理根窗口直接子窗口之间
 * 的堆叠（多个 wine 会话/裸窗口）。
 */
object X11WindowOps {
    private const val TAG = "X11WindowOps"
    private const val PREFIX = "/data/data/com.linbox/files/usr"

    /** 参与置前/轮转的最小窗口面积边长（过滤杂项小窗）。 */
    private const val MIN_SIDE = 64

    /** raiseAt 的最小间隔（ms）—— 防抖，正常点击频率远低于此。 */
    private const val RAISE_THROTTLE_MS = 150L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "linbox-x11-winops").apply { isDaemon = true }
    }

    @Volatile private var lastRaiseAt = 0L

    /** 上次 raiseAt 已发现"只有顶层一个候选"的时间 —— 单会话/纯 VD 场景快速短路。 */
    @Volatile private var singleWindowUntil = 0L

    // ------------------------------------------------------------------
    // 对外 API（全部异步 fire-and-forget，绝不阻塞调用线程）
    // ------------------------------------------------------------------

    /**
     * 点击置前：把包含 (xScreen, yScreen) 的最顶层可见根子窗口提到最前
     * （若它不是当前最顶层）。坐标为 **X 屏幕坐标**（SmartTouchBridge
     * 的 toXpx 结果）。已是最顶层/只有一个候选/找不到 → 零动作。
     */
    fun raiseAt(x: Int, y: Int) {
        if (!LorieView.connected()) return
        // 单候选短路期（最近一次枚举只有 1 个可见窗口）：点按都在同一
        // 窗口内，无需每次都开连接枚举。窗口增减难以感知，1 分钟后自废。
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < singleWindowUntil) return
        if (now - lastRaiseAt < RAISE_THROTTLE_MS) return
        lastRaiseAt = now
        exec.execute {
            runCatching { raiseAtSync(x, y) }
                .onFailure { Log.w(TAG, "raiseAt 失败: ${it.message}") }
        }
    }

    /** 窗口轮转：把堆叠序中紧挨当前顶层之下的可见窗口提到最前。 */
    fun cycleTopWindow() {
        if (!LorieView.connected()) return
        exec.execute {
            runCatching { cycleSync() }
                .onFailure { Log.w(TAG, "cycleTopWindow 失败: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // 实现（winops 线程）
    // ------------------------------------------------------------------

    private fun raiseAtSync(x: Int, y: Int) {
        val c = XConn.connect() ?: return
        try {
            val stacked = visibleRootChildren(c)   // 自底向上
            if (stacked.size <= 1) {
                singleWindowUntil = android.os.SystemClock.elapsedRealtime() + 60_000L
                return
            }
            // 自顶向下找第一个包含落点的窗口（即事件会到达的那个）
            val target = stacked.lastIndex.downTo(0).firstOrNull { i ->
                val g = stacked[i].second
                x >= g.x && x < g.x + g.w && y >= g.y && y < g.y + g.h
            } ?: return
            if (target == stacked.lastIndex) return  // 已是最顶层 → 零动作
            val (win, _) = stacked[target]
            c.raiseAndFocus(win)
            Log.i(TAG, "点击置前: 窗口 0x${win.toString(16)}（堆叠位 $target/${stacked.size} → 顶层）")
        } finally {
            runCatching { c.close() }
        }
    }

    private fun cycleSync() {
        val c = XConn.connect() ?: return
        try {
            val stacked = visibleRootChildren(c)   // 自底向上
            if (stacked.size < 2) {
                singleWindowUntil = android.os.SystemClock.elapsedRealtime() + 60_000L
                Log.i(TAG, "窗口轮转: 只有 ${stacked.size} 个可见根子窗口，无可切换")
                return
            }
            // 紧挨当前顶层之下的窗口 → 提到最前
            val (win, _) = stacked[stacked.size - 2]
            c.raiseAndFocus(win)
            Log.i(TAG, "窗口轮转: 窗口 0x${win.toString(16)}（${stacked.size} 个可见窗口，次顶层 → 顶层）")
        } finally {
            runCatching { c.close() }
        }
    }

    /** 根窗口的可见（已映射、非 override-redirect、非迷你）子窗口，按堆叠序（自底向上）。 */
    private fun visibleRootChildren(c: XConn): List<Pair<Long, XConn.Geo>> {
        val ids = c.queryTree(c.root)
        val out = ArrayList<Pair<Long, XConn.Geo>>(ids.size)
        for (id in ids) {
            val a = c.getWindowAttributes(id) ?: continue
            if (!a.mapped || a.overrideRedirect) continue
            val g = c.getGeometry(id) ?: continue
            if (g.w < MIN_SIDE || g.h < MIN_SIDE) continue
            out.add(id to g)
        }
        return out
    }

    // ------------------------------------------------------------------
    // 极简 X 客户端（与 X11FitClient.XConn 同源的 core protocol 子集；
    // 独立短连接：connect → 若干请求 → close，绝不与其享 socket）
    // ------------------------------------------------------------------

    private class XConn private constructor(
        val sock: LocalSocket,
        val input: DataInputStream,
        val out: BufferedOutputStream
    ) {
        var seq = 0
        var root = 0L
        var rootW = 0
        var rootH = 0

        class Geo(val x: Int, val y: Int, val w: Int, val h: Int)
        class Attr(val mapped: Boolean, val overrideRedirect: Boolean)

        fun close() { runCatching { sock.close() } }

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

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

        /** 读到属于本请求的 reply（同 X11FitClient 的序列号匹配/跳过逻辑）。 */
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
                        skipFully(24)
                        if (seq == expectedSeq) return null
                    }
                    code == 1 -> {
                        if (seq == expectedSeq) {
                            val base = ByteArray(24)
                            input.readFully(base)
                            if (extra > 0) {
                                if (body > 512 * 1024)
                                    throw java.io.IOException("reply 附加体过大: $body")
                                val rest = ByteArray(body)
                                input.readFully(rest)
                                return head + base + rest
                            }
                            return head + base
                        }
                        // 迟到/无关回复：整包跳过（32 + 4*len）
                        if (24 + body > 512 * 1024)
                            throw java.io.IOException("无关 reply 过大: $body")
                        skipFully(24 + body)
                    }
                    code == 35 -> {
                        // XGE 变长事件：32 + 4*len
                        if (24 + body > 512 * 1024)
                            throw java.io.IOException("XGE 事件过大: $body")
                        skipFully(24 + body)
                    }
                    else -> skipFully(24)   // 普通事件：固定 32B
                }
            }
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

        fun getWindowAttributes(win: Long): Attr? {
            val buf = newReq(3, 0, 4)
            buf.putInt(win.toInt())
            send(buf)
            val r = readReply(seq) ?: return null
            val rb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
            val mapState = rb.get(26).toInt()
            val ovr = rb.get(27).toInt() != 0
            return Attr(mapState == 2, ovr)
        }

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
         * 置前 + 激活：ConfigureWindow(StackMode=Above) + SetInputFocus。
         * 两者均为无回复请求；可能的异步 error 被后续 readReply 的
         * 序列号跳过逻辑消化（本对象用完即关连接，不消费也无所谓）。
         */
        fun raiseAndFocus(win: Long) {
            // ConfigureWindow: window(4) + mask(2) + pad(2) + stack-mode(4)
            val cfg = newReq(12, 0, 12)
            cfg.putInt(win.toInt())
            cfg.putShort(0x0200)          // CWStackMode
            cfg.putShort(0)               // pad
            cfg.putInt(0)                 // Above
            send(cfg)

            // SetInputFocus: revert-to(detail@1)=RevertToNone(0), window(4), time(4)
            val focus = newReq(42, 0, 8)
            focus.putInt(win.toInt())
            focus.putInt(0)               // CurrentTime
            send(focus)
        }

        companion object {
            /**
             * 连接本机 X server（与 X11FitClient.XConn.connectValidated 同源的
             * 建连逻辑；工厂放伴生对象内，构造器保持 private —— Kotlin 不允许
             * 外层对象直接调用嵌套类的私有构造器）。
             */
            fun connect(): XConn? {
                val dir = java.io.File("$PREFIX/tmp/.X11-unix")
                val files = dir.listFiles { f -> f.name.matches(Regex("X\\d+")) } ?: return null
                for (f in files.sortedByDescending { it.name }) {
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

                        val head = ByteArray(8)
                        input.readFully(head)
                        if (head[0].toInt() != 1) { runCatching { sock.close() }; continue }
                        // setup 回复前缀：success@0, reasonLen@1, major@2-3, minor@4-5, len(words)@6-7
                        val words = (head[6].toInt() and 0xFF) or ((head[7].toInt() and 0xFF) shl 8)
                        if (words <= 0 || words * 4 > 512 * 1024) {
                            runCatching { sock.close() }; continue
                        }
                        val rest = ByteArray(words * 4)
                        input.readFully(rest)
                        if (rest.size < 32) { runCatching { sock.close() }; continue }
                        val vendorLen = (rest[16].toInt() and 0xFF) or ((rest[17].toInt() and 0xFF) shl 8)
                        val nScreens = rest[20].toInt() and 0xFF
                        val nFormats = rest[21].toInt() and 0xFF
                        val off = 32 + (vendorLen + 3) / 4 * 4 + nFormats * 8
                        if (nScreens < 1 || off + 24 > rest.size) {
                            runCatching { sock.close() }; continue
                        }
                        val rb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
                        val root = rb.getInt(off).toLong() and 0xFFFFFFFFL
                        val c = XConn(sock, input, out)
                        c.root = root
                        return c
                    } catch (e: Exception) {
                        Log.w(TAG, "连接 ${f.absolutePath} 失败: ${e.message}")
                        runCatching { sock?.close() }
                    }
                }
                return null
            }
        }
    }
}
