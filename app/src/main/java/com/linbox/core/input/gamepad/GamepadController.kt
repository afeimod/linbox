package com.linbox.core.input.gamepad

import android.view.KeyEvent
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟游戏手柄（v2.15）：
 * 1. 数据模型 —— 按钮BUTTON / 摇杆JOYSTICK / 十字键DPAD 三类元素，
 *    每个元素有独立的位置（归一化 0..1）、大小（dp）、映射与方向模式
 * 2. 输入路由 —— 键盘走 WebView.dispatchKeyEvent（真实事件管线，isTrusted=true，
 *    HTML5/Flash 游戏通吃）；鼠标走 JS 合成 MouseEvent（左/右/中键 + 滚轮）
 * 3. 配置持久化 —— JSON 序列化到 DataStore（org.json 系统自带，零依赖）
 *
 * 摇杆/十字键方向模式：ARROWS（↑↓←→）↔ WASD，可在设置窗切换。
 */
object GamepadController {

    // ============================================================
    // 元素类型与动作
    // ============================================================

    enum class ElementType { BUTTON, JOYSTICK, DPAD }

    /** 方向模式 */
    enum class DirMode { ARROWS, WASD }

    /**
     * 按键动作：覆盖全部键盘按键 + 鼠标功能。
     * @param keyCode android.view.KeyEvent.KEYCODE_*（鼠标动作为 -1）
     */
    data class PadAction(
        val kind: String,      // "key" | "mouse"
        val keyCode: Int,      // key: KeyEvent.KEYCODE_*；mouse: MOUSE_* 常量
        val label: String      // 显示名
    ) {
        companion object {
            const val MOUSE_LEFT = 1
            const val MOUSE_RIGHT = 2
            const val MOUSE_MIDDLE = 3
            const val MOUSE_SCROLL_UP = 4
            const val MOUSE_SCROLL_DOWN = 5

            fun key(keyCode: Int, label: String) = PadAction("key", keyCode, label)
            fun mouse(code: Int, label: String) = PadAction("mouse", code, label)
        }
    }

    /** 单个手柄元素 */
    data class PadElement(
        val id: String = UUID.randomUUID().toString().take(8),
        val type: ElementType,
        /** 按钮显示文字（A/B/X/Y/L1/R1/Start...；摇杆=方向模式标签） */
        val label: String,
        /** BUTTON 映射的动作 */
        val action: PadAction = PadAction.key(KeyEvent.KEYCODE_SPACE, "Space"),
        /** JOYSTICK / DPAD 方向模式 */
        val dirMode: DirMode = DirMode.ARROWS,
        /** 归一化位置（相对屏幕宽高 0..1） */
        val posX: Float,
        val posY: Float,
        /** 元素直径 dp */
        val sizeDp: Float
    )

    // ============================================================
    // 全部可选映射（设置窗"映射选择器"的完整清单：所有键盘 + 鼠标功能）
    // ============================================================

    /** 映射分组：完整键盘 + 鼠标 */
    val ACTION_GROUPS: List<Pair<String, List<PadAction>>> by lazy {
        // 注意：keyCodeFromString 区分大小写，Android 常量名全大写（KEYCODE_A），
        // 拼小写会返回 KEYCODE_UNKNOWN(0)，导致按键无输出！
        val letters = ('A'..'Z').map { c ->
            PadAction.key(
                KeyEvent.keyCodeFromString("KEYCODE_$c").let { if (it != 0) it else KeyEvent.KEYCODE_A + (c - 'A') },
                c.toString()
            )
        }
        val digits = ('0'..'9').map { c ->
            PadAction.key(
                KeyEvent.keyCodeFromString("KEYCODE_$c").let { if (it != 0) it else KeyEvent.KEYCODE_0 + (c - '0') },
                c.toString()
            )
        }
        val fn = (1..12).map { i ->
            PadAction.key(
                KeyEvent.keyCodeFromString("KEYCODE_F$i").let { kc -> if (kc != 0) kc else KeyEvent.KEYCODE_F1 + (i - 1) },
                "F$i"
            )
        }
        val symbols = listOf(",", ".", "/", ";", "'", "[", "]", "-", "=", "\\", "`").map { s ->
            PadAction.key(symbolKeyCode(s), s)
        }
        val control = listOf(
            PadAction.key(KeyEvent.KEYCODE_SPACE, "Space"),
            PadAction.key(KeyEvent.KEYCODE_ENTER, "Enter"),
            PadAction.key(KeyEvent.KEYCODE_DEL, "Bksp"),
            PadAction.key(KeyEvent.KEYCODE_FORWARD_DEL, "Del"),
            PadAction.key(KeyEvent.KEYCODE_TAB, "Tab"),
            PadAction.key(KeyEvent.KEYCODE_ESCAPE, "Esc"),
            PadAction.key(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift"),
            PadAction.key(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl"),
            PadAction.key(KeyEvent.KEYCODE_ALT_LEFT, "Alt"),
            PadAction.key(KeyEvent.KEYCODE_HOME, "Home"),
            PadAction.key(KeyEvent.KEYCODE_MOVE_END, "End"),
            PadAction.key(KeyEvent.KEYCODE_PAGE_UP, "PgUp"),
            PadAction.key(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn"),
            PadAction.key(KeyEvent.KEYCODE_INSERT, "Ins"),
            PadAction.key(KeyEvent.KEYCODE_SYSRQ, "PrtSc")
        )
        val numpad = (0..9).map { i ->
            val kc = KeyEvent.keyCodeFromString("KEYCODE_NUMPAD_$i")
            PadAction.key(if (kc != 0) kc else KeyEvent.KEYCODE_NUMPAD_0 + i, "小键盘$i")
        } + listOf(
            PadAction.key(KeyEvent.KEYCODE_NUMPAD_ADD, "小键盘+"),
            PadAction.key(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "小键盘-"),
            PadAction.key(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "小键盘×"),
            PadAction.key(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "小键盘÷"),
            PadAction.key(KeyEvent.KEYCODE_NUMPAD_DOT, "小键盘."),
            PadAction.key(KeyEvent.KEYCODE_NUMPAD_ENTER, "小键盘⏎")
        )
        val arrows = listOf(
            PadAction.key(KeyEvent.KEYCODE_DPAD_UP, "↑"),
            PadAction.key(KeyEvent.KEYCODE_DPAD_DOWN, "↓"),
            PadAction.key(KeyEvent.KEYCODE_DPAD_LEFT, "←"),
            PadAction.key(KeyEvent.KEYCODE_DPAD_RIGHT, "→")
        )
        val wasd = listOf(
            PadAction.key(KeyEvent.KEYCODE_W, "W"),
            PadAction.key(KeyEvent.KEYCODE_A, "A"),
            PadAction.key(KeyEvent.KEYCODE_S, "S"),
            PadAction.key(KeyEvent.KEYCODE_D, "D")
        )
        val mouse = listOf(
            PadAction.mouse(PadAction.MOUSE_LEFT, "鼠标左键"),
            PadAction.mouse(PadAction.MOUSE_RIGHT, "鼠标右键"),
            PadAction.mouse(PadAction.MOUSE_MIDDLE, "鼠标中键"),
            PadAction.mouse(PadAction.MOUSE_SCROLL_UP, "滚轮 ↑"),
            PadAction.mouse(PadAction.MOUSE_SCROLL_DOWN, "滚轮 ↓")
        )
        listOf(
            "字母" to letters,
            "数字" to digits,
            "功能键" to fn,
            "控制键" to control,
            "方向键" to arrows,
            "WASD" to wasd,
            "符号" to symbols,
            "小键盘" to numpad,
            "鼠标" to mouse
        )
    }

    /** 符号 → Android keyCode（直接常量，确保映射准确） */
    private fun symbolKeyCode(s: String): Int = when (s) {
        "," -> KeyEvent.KEYCODE_COMMA
        "." -> KeyEvent.KEYCODE_PERIOD
        "/" -> KeyEvent.KEYCODE_SLASH
        ";" -> KeyEvent.KEYCODE_SEMICOLON
        "'" -> KeyEvent.KEYCODE_APOSTROPHE
        "[" -> KeyEvent.KEYCODE_LEFT_BRACKET
        "]" -> KeyEvent.KEYCODE_RIGHT_BRACKET
        "-" -> KeyEvent.KEYCODE_MINUS
        "=" -> KeyEvent.KEYCODE_EQUALS
        "\\" -> KeyEvent.KEYCODE_BACKSLASH
        "`" -> KeyEvent.KEYCODE_GRAVE
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    /** 符号 → KeyEvent 常量名（仅显示/调试用） */
    @Suppress("unused")
    private fun symbolKeycodeName(s: String): String = when (s) {
        "," -> "COMMA"
        "." -> "PERIOD"
        "/" -> "SLASH"
        ";" -> "SEMICOLON"
        "'" -> "APOSTROPHE"
        "[" -> "LEFT_BRACKET"
        "]" -> "RIGHT_BRACKET"
        "-" -> "MINUS"
        "=" -> "EQUALS"
        "\\" -> "BACKSLASH"
        "`" -> "GRAVE"
        else -> "UNKNOWN"
    }

    // ============================================================
    // 预设布局
    // ============================================================

    /** 经典手柄布局（v2.15.3 新默认）：左摇杆=WASD + 左十字键=方向键 +
     *  右六键 JKLUIO + Start≡=Tab。按钮表面直接显示映射按键名 */
    fun classicLayout(): List<PadElement> = listOf(
        PadElement(type = ElementType.JOYSTICK, label = "摇杆", dirMode = DirMode.WASD,
            posX = 0.16f, posY = 0.72f, sizeDp = 150f),
        PadElement(type = ElementType.DPAD, label = "十字键", dirMode = DirMode.ARROWS,
            posX = 0.38f, posY = 0.72f, sizeDp = 120f),
        PadElement(type = ElementType.BUTTON, label = "A",
            action = PadAction.key(KeyEvent.KEYCODE_J, "J"),
            posX = 0.84f, posY = 0.70f, sizeDp = 62f),
        PadElement(type = ElementType.BUTTON, label = "B",
            action = PadAction.key(KeyEvent.KEYCODE_K, "K"),
            posX = 0.74f, posY = 0.78f, sizeDp = 62f),
        PadElement(type = ElementType.BUTTON, label = "X",
            action = PadAction.key(KeyEvent.KEYCODE_L, "L"),
            posX = 0.74f, posY = 0.60f, sizeDp = 62f),
        PadElement(type = ElementType.BUTTON, label = "Y",
            action = PadAction.key(KeyEvent.KEYCODE_U, "U"),
            posX = 0.64f, posY = 0.68f, sizeDp = 62f),
        PadElement(type = ElementType.BUTTON, label = "L1",
            action = PadAction.key(KeyEvent.KEYCODE_I, "I"),
            posX = 0.12f, posY = 0.42f, sizeDp = 56f),
        PadElement(type = ElementType.BUTTON, label = "R1",
            action = PadAction.key(KeyEvent.KEYCODE_O, "O"),
            posX = 0.88f, posY = 0.42f, sizeDp = 56f),
        PadElement(type = ElementType.BUTTON, label = "≡",
            action = PadAction.key(KeyEvent.KEYCODE_TAB, "Tab"),
            posX = 0.50f, posY = 0.82f, sizeDp = 48f)
    )

    /** 简单布局：仅左十字键（方向键） + 右 J/K 两键 */
    fun simpleLayout(): List<PadElement> = listOf(
        PadElement(type = ElementType.DPAD, label = "十字键", dirMode = DirMode.ARROWS,
            posX = 0.18f, posY = 0.72f, sizeDp = 140f),
        PadElement(type = ElementType.BUTTON, label = "A",
            action = PadAction.key(KeyEvent.KEYCODE_J, "J"),
            posX = 0.82f, posY = 0.70f, sizeDp = 70f),
        PadElement(type = ElementType.BUTTON, label = "B",
            action = PadAction.key(KeyEvent.KEYCODE_K, "K"),
            posX = 0.70f, posY = 0.78f, sizeDp = 70f)
    )

    // ============================================================
    // 运行时状态（Compose 可观察）
    // ============================================================

    /** 手柄是否显示（游戏时开启） */
    var visible by mutableStateOf(false)

    /** 设置悬浮窗是否打开 */
    var settingsOpen by mutableStateOf(false)

    /** 编辑模式：元素可直接拖动定位，显示删除角标 */
    var editMode by mutableStateOf(false)

    /** 当前元素列表 */
    var elements by mutableStateOf(classicLayout())
        private set

    /** 当前选中编辑的元素 id（设置窗用） */
    var selectedElementId by mutableStateOf<String?>(null)

    /** 是否已加载持久化配置（避免首帧默认布局闪现后被覆盖） */
    private var loaded = false

    /** 配置版本：v2 = 摇杆WASD / 十字键方向键 / JKLUIO 默认布局；
     *  旧版本（裸数组或 v<2）加载时自动升级为新默认 */
    private const val CONFIG_VERSION = 2

    fun updateElements(list: List<PadElement>) {
        elements = list
    }

    fun selectElement(id: String?) {
        selectedElementId = id
    }

    /** 从持久化 JSON 恢复（带版本：旧版配置自动升级为 v2 新默认布局） */
    fun loadFromJson(json: String?) {
        loaded = true
        if (json.isNullOrBlank()) {
            elements = classicLayout()
            return
        }
        runCatching {
            val arr: JSONArray = try {
                val root = JSONObject(json)
                if (root.optInt("v", 1) < CONFIG_VERSION) return@runCatching
                root.optJSONArray("elements") ?: return@runCatching
            } catch (_: org.json.JSONException) {
                // v2.15.2 及之前是裸数组格式 = 旧默认布局（摇杆/十字键均为方向键、
                // ABXY=空格/回车…），直接升级为 v2 新默认（摇杆WASD + JKLUIO）
                return@runCatching
            }
            val list = mutableListOf<PadElement>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PadElement(
                        id = o.optString("id", UUID.randomUUID().toString().take(8)),
                        type = ElementType.valueOf(o.optString("type", "BUTTON")),
                        label = o.optString("label", ""),
                        action = PadAction(
                            kind = o.optJSONObject("action")?.optString("kind") ?: "key",
                            keyCode = o.optJSONObject("action")?.optInt("keyCode") ?: KeyEvent.KEYCODE_SPACE,
                            label = o.optJSONObject("action")?.optString("label") ?: ""
                        ),
                        dirMode = DirMode.valueOf(o.optString("dirMode", "ARROWS")),
                        posX = o.optDouble("posX", 0.5).toFloat(),
                        posY = o.optDouble("posY", 0.7).toFloat(),
                        sizeDp = o.optDouble("sizeDp", 60.0).toFloat()
                    )
                )
            }
            if (list.isNotEmpty()) elements = list
        }
    }

    /** 序列化到 JSON（带版本号，持久化） */
    fun toJson(): String {
        val arr = JSONArray()
        elements.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("type", e.type.name)
                    .put("label", e.label)
                    .put("action", JSONObject().put("kind", e.action.kind).put("keyCode", e.action.keyCode).put("label", e.action.label))
                    .put("dirMode", e.dirMode.name)
                    .put("posX", e.posX.toDouble())
                    .put("posY", e.posY.toDouble())
                    .put("sizeDp", e.sizeDp.toDouble())
            )
        }
        return JSONObject().put("v", CONFIG_VERSION).put("elements", arr).toString()
    }

    // ============================================================
    // 输入路由：键盘 → WebView.dispatchKeyEvent（真实管线）
    // ============================================================

    /** 当前接收按键的 WebView（浏览器活动标签注册） */
    @Volatile
    private var targetWebView: WeakReference<WebView>? = null

    // ============================================================
    // 按键状态跟踪（v2.15.3 防联键核心）
    // ============================================================

    /** 引用计数：多个元素映射同一键（如摇杆 WASD + 按钮也映射 W）时，
     * 只有第一个按下者发 ACTION_DOWN、最后一个抬起者才发 ACTION_UP，
     * 页面不会收到 down/down/up/up 这种会"卡键"的序列 */
    private val keyRefCounts = HashMap<Int, Int>()

    /** 每个键的真实按下时刻（UP 事件的 downTime 必须用按下时间，事件语义才正确） */
    private val keyDownTimes = HashMap<Int, Long>()

    /** 浏览器注册输入目标（切换目标前先释放旧页面还按着的键，防联键） */
    fun attachWebView(webView: WebView?) {
        val cur = targetWebView?.get()
        if (cur === webView) return
        if (cur != null) releaseAllKeys()
        targetWebView = webView?.let { WeakReference(it) }
    }

    fun detachWebView(webView: WebView?) {
        val cur = targetWebView?.get()
        if (webView == null || cur === webView) {
            releaseAllKeys()
            targetWebView = null
        }
    }

    /**
     * 底层派发：原始 keydown/keyup 事件。
     * v2.22.3 fix11b：新增 X11 路由 —— 浏览器无活跃 WebView 时，
     * 按键经 X11InputHub 直注 X server，桌面虚拟手柄由此控制 X11
     * 界面（wine/游戏）；有 WebView 时保持原浏览器行为不变。
     */
    private fun dispatchKeyEventRaw(keyCode: Int, keyAction: Int, downTime: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        val ev = KeyEvent(downTime, now, keyAction, keyCode, 0)
        val wv = targetWebView?.get()
        if (wv != null) {
            runCatching { wv.dispatchKeyEvent(ev) }
            return
        }
        // 无浏览器目标 → X11 桌面窗口接管（手柄控制 wine/X11）
        com.termux.x11.X11InputHub.forwardKey(keyCode, keyAction == KeyEvent.ACTION_DOWN)
    }

    /** 按下按键（引用计数：已在按下状态的键不重发 DOWN） */
    fun pressKey(keyCode: Int) {
        if (keyCode <= 0) return
        val n = keyRefCounts.getOrDefault(keyCode, 0)
        if (n > 0) {
            keyRefCounts[keyCode] = n + 1
            return
        }
        keyRefCounts[keyCode] = 1
        val now = android.os.SystemClock.uptimeMillis()
        keyDownTimes[keyCode] = now
        dispatchKeyEventRaw(keyCode, KeyEvent.ACTION_DOWN, now)
    }

    /** 抬起按键（引用计数归零才发 UP；多余的 UP 直接忽略） */
    fun releaseKey(keyCode: Int) {
        if (keyCode <= 0) return
        val n = keyRefCounts.getOrDefault(keyCode, 0)
        if (n <= 0) return
        if (n == 1) {
            keyRefCounts.remove(keyCode)
            val down = keyDownTimes.remove(keyCode) ?: android.os.SystemClock.uptimeMillis()
            dispatchKeyEventRaw(keyCode, KeyEvent.ACTION_UP, down)
        } else {
            keyRefCounts[keyCode] = n - 1
        }
    }

    /** 一键抬起全部按下的键（隐藏手柄 / 进编辑模式 / 打开设置 / 切换标签时调用，
     * 杜绝"松开后还在一直输出"） */
    fun releaseAllKeys() {
        if (keyRefCounts.isEmpty()) return
        keyRefCounts.keys.toList().forEach { releaseKey(it) }
    }

    /**
     * 派发鼠标动作。v2.22.3 fix11b：优先在虚拟鼠标指针位置（浏览器），
     * 无浏览器目标时把左/右/中键与滚轮直注 X11（X 指针当前位置）。
     */
    fun dispatchMouse(mouseCode: Int) {
        val wv = targetWebView?.get()
        if (wv == null) {
            // X11 路由：真实鼠标 down→up 序列 / 滚轮（与 SmartTouchBridge 同参）
            when (mouseCode) {
                PadAction.MOUSE_LEFT -> {
                    com.termux.x11.X11InputHub.forwardMouseButton(
                        com.termux.x11.input.InputStub.BUTTON_LEFT, true)
                    com.termux.x11.X11InputHub.forwardMouseButton(
                        com.termux.x11.input.InputStub.BUTTON_LEFT, false)
                }
                PadAction.MOUSE_RIGHT -> {
                    com.termux.x11.X11InputHub.forwardMouseButton(
                        com.termux.x11.input.InputStub.BUTTON_RIGHT, true)
                    com.termux.x11.X11InputHub.forwardMouseButton(
                        com.termux.x11.input.InputStub.BUTTON_RIGHT, false)
                }
                PadAction.MOUSE_MIDDLE -> {
                    com.termux.x11.X11InputHub.forwardMouseButton(
                        com.termux.x11.input.InputStub.BUTTON_MIDDLE, true)
                    com.termux.x11.X11InputHub.forwardMouseButton(
                        com.termux.x11.input.InputStub.BUTTON_MIDDLE, false)
                }
                PadAction.MOUSE_SCROLL_UP -> com.termux.x11.X11InputHub.forwardWheel(-120f)
                PadAction.MOUSE_SCROLL_DOWN -> com.termux.x11.X11InputHub.forwardWheel(120f)
            }
            return
        }
        // 位置：优先虚拟鼠标指针（根坐标），换算到 WebView 本地坐标
        val pos = com.linbox.core.input.MouseController.position
        val loc = IntArray(2)
        runCatching { wv.getLocationOnScreen(loc) }
        var x = pos.x - loc[0]
        var y = pos.y - loc[1]
        if (x < 0 || y < 0 || x > wv.width || y > wv.height) {
            // 指针不在 WebView 内 → 使用视图中心
            x = wv.width / 2f
            y = wv.height / 2f
        }
        val js = when (mouseCode) {
            PadAction.MOUSE_LEFT -> mouseJs(x, y, 0)
            PadAction.MOUSE_RIGHT -> mouseJs(x, y, 2) + contextMenuJs(x, y)
            PadAction.MOUSE_MIDDLE -> mouseJs(x, y, 1)
            PadAction.MOUSE_SCROLL_UP -> wheelJs(x, y, -3)
            PadAction.MOUSE_SCROLL_DOWN -> wheelJs(x, y, 3)
            else -> return
        }
        runCatching { wv.evaluateJavascript(js, null) }
    }

    private fun mouseJs(x: Float, y: Float, button: Int): String = """
        (function(){
          var el = document.elementFromPoint($x, $y);
          if(!el) el = document;
          var common = {bubbles:true, cancelable:true, composed:true, view:window,
                        clientX:$x, clientY:$y, screenX:$x, screenY:$y, button:$button};
          el.dispatchEvent(new MouseEvent('mousedown', common));
          el.dispatchEvent(new MouseEvent('mouseup', common));
          el.dispatchEvent(new MouseEvent('click', common));
        })();
    """.trimIndent()

    private fun contextMenuJs(x: Float, y: Float): String = """
        (function(){
          var el = document.elementFromPoint($x, $y);
          if(!el) el = document;
          el.dispatchEvent(new MouseEvent('contextmenu', {bubbles:true, cancelable:true,
            view:window, clientX:$x, clientY:$y, screenX:$x, screenY:$y, button:2}));
        })();
    """.trimIndent()

    private fun wheelJs(x: Float, y: Float, delta: Int): String = """
        (function(){
          var el = document.elementFromPoint($x, $y);
          if(!el) el = document;
          el.dispatchEvent(new WheelEvent('wheel', {bubbles:true, cancelable:true,
            view:window, clientX:$x, clientY:$y, deltaY:$delta, deltaMode:0}));
        })();
    """.trimIndent()

    // ============================================================
    // 触发入口（覆盖层调用）
    // ============================================================

    /** 按钮按下 */
    fun onButtonPress(element: PadElement) {
        if (element.action.kind == "mouse") dispatchMouse(element.action.keyCode)
        else pressKey(element.action.keyCode)
    }

    /** 按钮抬起 */
    fun onButtonRelease(element: PadElement) {
        if (element.action.kind == "mouse") return  // 鼠标点击在按下时一次性完成
        releaseKey(element.action.keyCode)
    }

    /** 方向按下/抬起（摇杆/十字键共用；按方向模式映射为方向键或 WASD） */
    fun onDirection(dir: Char, pressed: Boolean, dirMode: DirMode) {
        // dir: 'U' 'D' 'L' 'R'
        val keyCode = when (dirMode) {
            DirMode.ARROWS -> when (dir) {
                'U' -> KeyEvent.KEYCODE_DPAD_UP
                'D' -> KeyEvent.KEYCODE_DPAD_DOWN
                'L' -> KeyEvent.KEYCODE_DPAD_LEFT
                else -> KeyEvent.KEYCODE_DPAD_RIGHT
            }
            DirMode.WASD -> when (dir) {
                'U' -> KeyEvent.KEYCODE_W
                'D' -> KeyEvent.KEYCODE_S
                'L' -> KeyEvent.KEYCODE_A
                else -> KeyEvent.KEYCODE_D
            }
        }
        if (pressed) pressKey(keyCode) else releaseKey(keyCode)
    }

    /** 无目标时是否有接收方（覆盖层可显示提示；X11 窗口连接时也算有目标） */
    fun hasTarget(): Boolean =
        targetWebView?.get() != null || com.termux.x11.X11InputHub.isX11ForwardReady()

    // ============================================================
    // v2.16.4：手柄元素命中几何（窗口坐标 px）
    // ============================================================
    // 背景：手柄摇杆/按钮按住时会消费自己的指针变化，而 Compose interop
    // 过滤器（compose-ui 1.6.8 PointerInteropFilter.dispatchToView）只要
    // 发现"任何一个指针变化被消费"就停止向内嵌原生 View 派发事件 ——
    // 手柄移动时另一根手指的拖动/点击永远到不了目标 View。
    // 链路：GamepadOverlay 用 onGloballyPositioned 把每个元素的
    // boundsInRoot（窗口坐标）登记到这里；原生 interop 容器可在
    // dispatchTouchEvent 入口据此把"落点在手柄元素内"的指针从
    // MotionEvent 中剥离，让手柄与页面触摸各收各的指针。

    /** 每个手柄元素的命中矩形（窗口坐标 px；仅 UI 线程读写） */
    private val elementHitRects = HashMap<String, FloatArray>() // [l,t,r,b]

    /** 命中判定外扩（px）：吸收 boundsInRoot 与 View 窗口坐标间的亚像素偏差 */
    private const val HIT_MARGIN_PX = 12f

    /** 覆盖层布局后登记元素命中矩形（GamepadElementHost 调用） */
    fun registerElementHit(id: String, l: Float, t: Float, r: Float, b: Float) {
        elementHitRects[id] = floatArrayOf(l, t, r, b)
    }

    /** 手柄隐藏/离开组合时清空（防陈旧矩形误剥离正常触摸） */
    fun clearElementHits() {
        elementHitRects.clear()
    }

    /** 是否有可用命中矩形（无 = 手柄未显示，浏览器侧零开销直通） */
    fun hasElementHits(): Boolean = elementHitRects.isNotEmpty()

    /** 窗口坐标 (x,y) 是否落在任一手柄元素内（含少量外扩） */
    fun isOverPadElement(x: Float, y: Float): Boolean {
        for (rect in elementHitRects.values) {
            if (x >= rect[0] - HIT_MARGIN_PX && x <= rect[2] + HIT_MARGIN_PX &&
                y >= rect[1] - HIT_MARGIN_PX && y <= rect[3] + HIT_MARGIN_PX
            ) return true
        }
        return false
    }

    // ============================================================
    // v2.32：X11 触摸分流 —— 手柄 UI 命中区（元素 + 迷你工具条）
    // ============================================================
    // 背景：X11 侧手柄 UI 移入 LorieView 容器内的独立 Compose 宿主后，
    // 由 X11TouchSplitLayout 在 View 层按"落点是否在手柄 UI 内"逐指分流：
    // 手柄指针 → 手柄宿主（Compose），屏幕指针 → LorieView（SmartTouchBridge）。
    // 判定区 = 元素命中矩形（elementHitRects）∪ 迷你工具条（⚙✎✕）。
    // 工具条必须并入判定：它不在 elementHitRects 里，若被分到屏幕流，
    // ⚙✎✕ 按钮在 X11 下会点不到（指针被路由去了 LorieView）。

    /** 迷你工具条命中矩形（窗口坐标 px [l,t,r,b]；仅 UI 线程读写） */
    private val toolbarHitRect = FloatArray(4)
    private var hasToolbarHitRect = false

    /** 迷你工具条布局后登记命中矩形（GamepadOverlayContent 调用） */
    fun registerToolbarHit(l: Float, t: Float, r: Float, b: Float) {
        toolbarHitRect[0] = l; toolbarHitRect[1] = t
        toolbarHitRect[2] = r; toolbarHitRect[3] = b
        hasToolbarHitRect = true
    }

    /** 手柄隐藏/离开组合时清空工具条命中（对齐 clearElementHits 生命周期） */
    fun clearToolbarHit() {
        hasToolbarHitRect = false
    }

    /** 是否有工具条命中矩形 */
    fun hasToolbarHit(): Boolean = hasToolbarHitRect

    /**
     * X11 触摸分流的手柄侧命中判定：窗口坐标 (x,y) 落在任一手柄元素
     * 或迷你工具条内 = 手柄指针（进手柄宿主），否则 = 屏幕指针（进桥）。
     */
    fun isOverPadUi(x: Float, y: Float): Boolean {
        if (isOverPadElement(x, y)) return true
        if (hasToolbarHitRect &&
            x >= toolbarHitRect[0] - HIT_MARGIN_PX && x <= toolbarHitRect[2] + HIT_MARGIN_PX &&
            y >= toolbarHitRect[1] - HIT_MARGIN_PX && y <= toolbarHitRect[3] + HIT_MARGIN_PX
        ) return true
        return false
    }
}
