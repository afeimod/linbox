package com.linbox.core.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.linbox.LinBoxApp

/**
 * 虚拟键盘输入通道（v2.13）。
 *
 * 组成：
 * - [KeyboardTarget]：文本编辑会话抽象。两种实现：
 *   [TextFieldValueTarget]（光标级编辑：插入/删除/移动光标/全选/复制粘贴剪切）
 *   [StringTarget]（追加式编辑：适配存量 String 状态的单行输入框）
 * - [VirtualKeyboardController]：全局单例，持有"当前聚焦的编辑会话"与键盘可见性
 * - [Modifier.keyboardAware]：文本框挂载的焦点钩子 —— 聚焦时注册会话并呼出键盘，
 *   失焦时注销；虚拟键盘总开关（默认关闭）关闭时不介入（回退系统输入法）。
 */
abstract class KeyboardTarget(val singleLine: Boolean) {

    /** 单行输入框回车提交回调（键盘 Enter 触发） */
    var onEnter: (() -> Unit)? = null

    /** 在光标处插入文本 */
    abstract fun insertText(text: String)

    /** 退格删除。@return true=有内容被删除 */
    abstract fun backspace(): Boolean

    /** Delete 前向删除。@return true=有内容被删除 */
    abstract fun deleteForward(): Boolean

    /** 光标移动 delta 字符。@return true=支持 */
    abstract fun moveCursor(delta: Int): Boolean

    /** 光标到文本开头。@return true=支持 */
    abstract fun toStart(): Boolean

    /** 光标到文本末尾。@return true=支持 */
    abstract fun toEnd(): Boolean

    /** 全选 */
    abstract fun selectAll(): Boolean

    /** 复制选区（无选区时复制全部）。@return true=已写入剪贴板 */
    abstract fun copy(clipboard: ClipboardManager): Boolean

    /** 剪切选区。@return true=已执行 */
    abstract fun cut(clipboard: ClipboardManager): Boolean

    /** 粘贴剪贴板文本 */
    abstract fun paste(clipboard: ClipboardManager)
}

/**
 * 基于 TextFieldValue 的编辑会话（记事本等多行编辑器）：
 * 支持光标级插入、选区删除、方向键、Home/End、全选、复制/剪切/粘贴。
 */
class TextFieldValueTarget(
    private var getValue: () -> TextFieldValue,
    private var setValue: (TextFieldValue) -> Unit,
    singleLine: Boolean
) : KeyboardTarget(singleLine) {

    fun update(value: () -> TextFieldValue, onValue: (TextFieldValue) -> Unit) {
        getValue = value
        setValue = onValue
    }

    private inline fun edit(transform: (TextFieldValue) -> TextFieldValue) {
        setValue(transform(getValue()))
    }

    override fun insertText(text: String) = edit { v ->
        if (singleLine && text.contains('\n')) return@edit v
        val sel = v.selection
        val before = v.text.substring(0, sel.min)
        val after = v.text.substring(sel.max)
        TextFieldValue(before + text + after, TextRange(sel.min + text.length))
    }

    override fun backspace(): Boolean {
        val v = getValue()
        val sel = v.selection
        return if (!sel.collapsed) {
            edit {
                it.copy(text = it.text.removeRange(sel.min, sel.max), selection = TextRange(sel.min))
            }
            true
        } else if (sel.min > 0) {
            edit {
                it.copy(text = it.text.removeRange(sel.min - 1, sel.min), selection = TextRange(sel.min - 1))
            }
            true
        } else false
    }

    override fun deleteForward(): Boolean {
        val v = getValue()
        val sel = v.selection
        return if (!sel.collapsed) {
            backspace()
        } else if (sel.max < v.text.length) {
            edit {
                it.copy(text = it.text.removeRange(sel.min, sel.min + 1), selection = TextRange(sel.min))
            }
            true
        } else false
    }

    override fun moveCursor(delta: Int): Boolean = edit { v ->
        val pos = (v.selection.min + delta).coerceIn(0, v.text.length)
        v.copy(selection = TextRange(pos))
    }.let { true }

    override fun toStart(): Boolean = edit { it.copy(selection = TextRange(0)) }.let { true }

    override fun toEnd(): Boolean = edit {
        it.copy(selection = TextRange(it.text.length))
    }.let { true }

    override fun selectAll(): Boolean = edit {
        it.copy(selection = TextRange(0, it.text.length))
    }.let { true }

    override fun copy(clipboard: ClipboardManager): Boolean {
        val v = getValue()
        val sel = v.selection
        val text = if (sel.collapsed) v.text else v.text.substring(sel.min, sel.max)
        if (text.isEmpty()) return false
        clipboard.setText(AnnotatedString(text))
        return true
    }

    override fun cut(clipboard: ClipboardManager): Boolean {
        val v = getValue()
        if (v.selection.collapsed) return false
        if (!copy(clipboard)) return false
        backspace()
        return true
    }

    override fun paste(clipboard: ClipboardManager) {
        val text = clipboard.getText()?.text ?: return
        if (text.isNotEmpty()) insertText(text)
    }
}

/**
 * 基于 String 状态的编辑会话（终端/浏览器地址栏/搜索框等单行输入）：
 * 追加式编辑 —— 插入到末尾、退格删末尾；不支持光标移动/全选（String 无光标信息）。
 * Enter 触发 onEnter。
 */
class StringTarget(
    private var getValue: () -> String,
    private var setValue: (String) -> Unit,
    singleLine: Boolean
) : KeyboardTarget(singleLine) {

    fun update(value: () -> String, onValue: (String) -> Unit) {
        getValue = value
        setValue = onValue
    }

    override fun insertText(text: String) {
        if (singleLine && text.contains('\n')) return
        setValue(getValue() + text)
    }

    override fun backspace(): Boolean {
        val s = getValue()
        if (s.isEmpty()) return false
        setValue(s.dropLast(1))
        return true
    }

    override fun deleteForward(): Boolean = false

    override fun moveCursor(delta: Int): Boolean = false

    override fun toStart(): Boolean = false

    override fun toEnd(): Boolean = false

    override fun selectAll(): Boolean = false

    override fun copy(clipboard: ClipboardManager): Boolean {
        val s = getValue()
        if (s.isEmpty()) return false
        clipboard.setText(AnnotatedString(s))
        return true
    }

    override fun cut(clipboard: ClipboardManager): Boolean {
        if (!copy(clipboard)) return false
        setValue("")
        return true
    }

    override fun paste(clipboard: ClipboardManager) {
        val text = clipboard.getText()?.text ?: return
        if (text.isNotEmpty()) insertText(text)
    }
}

/**
 * 虚拟键盘全局控制器。
 *
 * - target：当前聚焦文本框的编辑会话（focus 钩子维护）
 * - visible：键盘可见性（focus 呼出 / Esc 或隐藏按钮收起）
 * - onWinKey：Win 徽标键回调（DesktopEnvironment 注入 = 切换开始菜单）
 */
object VirtualKeyboardController {

    /** 键盘是否可见 */
    var visible by mutableStateOf(false)
        private set

    /** 当前编辑会话 */
    var target by mutableStateOf<KeyboardTarget?>(null)
        private set

    /** Win 徽标键行为（由桌面环境注入） */
    var onWinKey: (() -> Unit)? = null

    fun attach(t: KeyboardTarget) {
        target = t
        visible = true
    }

    fun detach(t: KeyboardTarget) {
        if (target === t) {
            target = null
            visible = false
        }
    }

    /** 收起键盘（保留会话，Esc/隐藏按钮用） */
    fun hide() {
        visible = false
    }

    /** 重新呼出（已有会话时） */
    fun show() {
        if (target != null) visible = true
    }

    fun toggle() {
        if (visible) hide() else if (target != null) visible = true
    }
}

// ============================================================
// 文本框焦点钩子（Modifier.keyboardAware）
// ============================================================

/**
 * 挂到 BasicTextField 上的虚拟键盘钩子（String 版）。
 *
 * 聚焦：注册会话 + 呼出虚拟键盘 + 隐藏系统输入法；
 * 失焦：注销会话（键盘自动收起）。
 * 设置中心"使用虚拟键盘"总开关关闭时不介入。
 *
 * @param value 当前文本 getter
 * @param onValue 文本回写 setter
 * @param singleLine 单行（Enter 走 onEnter 而非换行）
 * @param onEnter 回车回调（如提交搜索 / 执行命令）
 */
fun Modifier.keyboardAware(
    value: () -> String,
    onValue: (String) -> Unit,
    singleLine: Boolean = true,
    onEnter: (() -> Unit)? = null
): Modifier = composed {
    val app = LinBoxApp.get()
    // v2.13.2：默认关闭（与 SettingsStore 默认值一致，避免首帧闪现键盘）
    val master by app.settingsStore.keyboardMaster.collectAsState(initial = false)
    val target = remember { StringTarget(value, onValue, singleLine) }
    SideEffect { target.update(value, onValue) }
    wireKeyboardAware(target, onEnter, master)
}

/**
 * TextFieldValue 版（光标级编辑，记事本等多行编辑器用）。
 *
 * 注意：此变体独立命名为 keyboardAwareEditor 而非同名重载 —— 当 value 与
 * onValue 均以 lambda 字面量传入时，Kotlin 无法在 () -> String 与
 * () -> TextFieldValue 两个同名重载间消歧（Overload resolution ambiguity）。
 */
fun Modifier.keyboardAwareEditor(
    value: () -> TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    singleLine: Boolean = false,
    onEnter: (() -> Unit)? = null
): Modifier = composed {
    val app = LinBoxApp.get()
    // v2.13.2：默认关闭（与 SettingsStore 默认值一致）
    val master by app.settingsStore.keyboardMaster.collectAsState(initial = false)
    val target = remember { TextFieldValueTarget(value, onValue, singleLine) }
    SideEffect { target.update(value, onValue) }
    wireKeyboardAware(target, onEnter, master)
}

/**
 * 公共接线：onEnter / 总开关联动 / 焦点钩子 / 卸载清理。
 * 注意 composed 内不能抽 @Composable 局部函数，这里用 @Composable 顶层私有函数。
 */
@Composable
private fun Modifier.wireKeyboardAware(
    target: KeyboardTarget,
    onEnter: (() -> Unit)?,
    master: Boolean
): Modifier {
    val kb = LocalSoftwareKeyboardController.current
    // v2.13.2：View 级 post 三连压制系统 IME —— Compose 在焦点回调之后才会请求
    // 显示系统输入法（TextInputSession），单次同步 hide 必然跑输竞态，这是
    // “虚拟键盘开着却偶尔弹手机输入法”的根因；post 到队列后面在 show 之后补刀
    val view = LocalView.current
    target.onEnter = onEnter
    DisposableEffect(target) {
        onDispose { VirtualKeyboardController.detach(target) }
    }
    // 总开关运行中关闭：立即注销并收起
    androidx.compose.runtime.LaunchedEffect(master) {
        if (!master) VirtualKeyboardController.detach(target)
    }
    return this.onFocusChanged { st ->
        if (master) {
            if (st.isFocused) {
                kb?.hide()
                view.post { kb?.hide() }
                view.postDelayed({ kb?.hide() }, 120L)
                view.postDelayed({ kb?.hide() }, 300L)
                VirtualKeyboardController.attach(target)
            } else {
                VirtualKeyboardController.detach(target)
            }
        }
    }
}
