package com.linbox.apps.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope

val CalculatorApp = AppDef(
    id = "calculator",
    displayName = "计算器",
    iconAsset = "app:calculator",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 340.dp,
    defaultHeight = 540.dp,
    pinnedToDesktop = true
) { scope ->
    CalculatorContent(scope)
}

/**
 * 计算器 - Win11 风格重构
 *
 * - 顶部显示区：表达式 + 当前数字
 * - 按钮区：4 列 x 6 行
 *   - 数字按钮：浅灰背景
 *   - 运算符按钮：强调色背景
 *   - 功能按钮（C/±/%/⌫）：中灰背景
 * - 圆角按钮、阴影
 *
 * v2.14.10 输入状态机重写（修复 "1 + 2 显示成 12" 的输入错乱）：
 * - 引入 waitingForOperand：按运算符/等号后等待新操作数，下一个数字
 *   【替换】当前显示而非追加 —— 旧版缺此状态，导致 1 → + → 2 连成了 "12"；
 * - 支持链式运算（1 + 2 + 3 = 6：按第二个 + 时先算中间结果）；
 * - 表达式行实时显示完整算式（"1 + 2"），等号后显示 "1 + 2 = 3"
 *   （旧版第二操作数从不入表达式行，出现截图中的 "1 + = 12"）；
 * - % 采用 Windows 语义（有运算符时 = 第一操作数 × 当前值 ÷ 100）；
 * - Error 态按数字/C 即恢复；⌫/CE/± 语义对齐 Windows 计算器。
 */
@Composable
private fun CalculatorContent(_scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    var display by remember { mutableStateOf("0") }
    var expression by remember { mutableStateOf("") }
    var operand1 by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    /** 等待新操作数：true 时下一个数字替换 display（而非追加） */
    var waitingForOperand by remember { mutableStateOf(true) }

    /** 输入第二操作数期间，表达式行实时显示 "1 + 2" */
    fun syncExpressionWithEntry() {
        val op1 = operand1
        val op = pendingOp
        if (op != null && op1 != null) {
            expression = "${formatResult(op1)} $op $display"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
            .padding(8.dp)
    ) {
        // ===== 显示区 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(theme.cardBackgroundColor)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom
        ) {
            // 历史表达式
            Text(
                text = expression,
                color = theme.secondaryTextColor,
                fontSize = 13.sp,
                textAlign = TextAlign.End
            )
            Spacer(Modifier.height(4.dp))
            // 当前数字
            Text(
                text = display,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(10.dp))

        // ===== 按钮网格 =====
        val buttons = listOf(
            listOf("%", "CE", "C", "⌫"),
            listOf("¹⁄ₓ", "x²", "√", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "=")
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { label ->
                        CalcButton(
                            label = label,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when (label) {
                                    "C" -> {
                                        display = "0"; expression = ""
                                        operand1 = null; pendingOp = null
                                        waitingForOperand = true
                                    }
                                    "CE" -> {
                                        display = "0"
                                        waitingForOperand = true
                                    }
                                    "±" -> {
                                        // 等待第二操作数期间不翻转（显示的是第一操作数，翻了会与
                                        // 实际参与运算的值不一致）；其余情况翻转当前条目
                                        if (display != "0" && display != "Error" &&
                                            !(waitingForOperand && pendingOp != null)
                                        ) {
                                            display = if (display.startsWith("-"))
                                                display.removePrefix("-") else "-$display"
                                            syncExpressionWithEntry()
                                        }
                                    }
                                    "%" -> {
                                        // Windows 语义：有挂起运算符时，% = 第一操作数 × 当前值 ÷ 100
                                        val d = display.toDoubleOrNull() ?: 0.0
                                        val v = if (operand1 != null && pendingOp != null)
                                            operand1!! * d / 100.0 else d / 100.0
                                        display = formatResult(v)
                                        waitingForOperand = false
                                        syncExpressionWithEntry()
                                    }
                                    "⌫" -> {
                                        if (waitingForOperand || display == "Error") {
                                            display = "0"
                                            waitingForOperand = true
                                        } else {
                                            display = if (display.length > 1) display.dropLast(1) else "0"
                                            if (display == "-") display = "0"
                                            syncExpressionWithEntry()
                                        }
                                    }
                                    "¹⁄ₓ" -> {
                                        val d = display.toDoubleOrNull() ?: 0.0
                                        val r = if (d != 0.0) 1.0 / d else Double.NaN
                                        applyUnary("1/x", d, r, pendingOp, operand1).let {
                                            display = it.first; expression = it.second
                                        }
                                        waitingForOperand = false
                                    }
                                    "x²" -> {
                                        val d = display.toDoubleOrNull() ?: 0.0
                                        applyUnary("sqr", d, d * d, pendingOp, operand1).let {
                                            display = it.first; expression = it.second
                                        }
                                        waitingForOperand = false
                                    }
                                    "√" -> {
                                        val d = display.toDoubleOrNull() ?: 0.0
                                        val r = if (d >= 0) kotlin.math.sqrt(d) else Double.NaN
                                        applyUnary("√", d, r, pendingOp, operand1).let {
                                            display = it.first; expression = it.second
                                        }
                                        waitingForOperand = false
                                    }
                                    "+", "−", "×", "÷" -> {
                                        val current = display.toDoubleOrNull()
                                        if (pendingOp != null && operand1 != null &&
                                            !waitingForOperand && current != null
                                        ) {
                                            // 链式运算：先求中间结果作为新的第一操作数（1 + 2 + → 3 +）
                                            display = formatResult(compute(operand1!!, current, pendingOp!!))
                                        }
                                        operand1 = display.toDoubleOrNull()
                                        pendingOp = label
                                        waitingForOperand = true
                                        expression = "$display $label"
                                    }
                                    "=" -> {
                                        val op2 = display.toDoubleOrNull()
                                        val result = if (operand1 != null && pendingOp != null && op2 != null) {
                                            compute(operand1!!, op2, pendingOp!!)
                                        } else op2 ?: 0.0
                                        // 完整算式入表达式行："1 + 2 ="
                                        expression = if (operand1 != null && pendingOp != null && op2 != null) {
                                            "${formatResult(operand1!!)} $pendingOp ${formatResult(op2)} ="
                                        } else {
                                            "$display ="
                                        }
                                        display = formatResult(result)
                                        operand1 = null
                                        pendingOp = null
                                        waitingForOperand = true
                                    }
                                    "." -> {
                                        if (waitingForOperand || display == "Error") {
                                            display = "0."
                                            waitingForOperand = false
                                        } else if (!display.contains(".")) {
                                            display += "."
                                        }
                                        syncExpressionWithEntry()
                                    }
                                    else -> {
                                        // 数字键
                                        if (label.length == 1 && label[0].isDigit()) {
                                            when {
                                                waitingForOperand || display == "Error" -> {
                                                    display = label
                                                    waitingForOperand = false
                                                }
                                                display == "0" -> display = label
                                                display == "-0" -> display = "-$label"
                                                display.length < 16 -> display += label
                                            }
                                            syncExpressionWithEntry()
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 四则运算（÷0 返回 NaN → formatResult 显示 Error） */
private fun compute(a: Double, b: Double, op: String): Double = when (op) {
    "+" -> a + b
    "−" -> a - b
    "×" -> a * b
    "÷" -> if (b != 0.0) a / b else Double.NaN
    else -> b
}

/**
 * 一元运算结果与表达式行。
 * - 有挂起二元运算：表达式行保持 "op1 ± 新值"（新值即一元运算结果，可继续 = 或链算）
 * - 无挂起运算：表达式行显示 "√(9) = 3" 形式
 */
private fun applyUnary(
    name: String,
    operand: Double,
    result: Double,
    pendingOp: String?,
    operand1: Double?
): Pair<String, String> {
    val newDisplay = formatResult(result)
    val expr = if (pendingOp != null && operand1 != null) {
        "${formatResult(operand1)} $pendingOp $newDisplay"
    } else {
        "$name(${formatResult(operand)}) = $newDisplay"
    }
    return Pair(newDisplay, expr)
}

@Composable
private fun CalcButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val isOp = label in listOf("+", "−", "×", "÷", "=")
    val isFunc = label in listOf("C", "CE", "±", "%", "⌫", "¹⁄ₓ", "x²", "√")
    val isEquals = label == "="
    val bg = when {
        isEquals -> theme.accentColor
        isOp -> theme.buttonBackgroundColor
        isFunc -> theme.cardBackgroundColor
        else -> theme.buttonBackgroundColor
    }
    val fg = when {
        isEquals -> Color.White
        isFunc -> theme.accentColor
        else -> if (theme.isDark) Color.White else Color.Black
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 18.sp,
            fontWeight = if (isOp) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun formatResult(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "Error"
    return if (d == d.toLong().toDouble()) d.toLong().toString()
           else d.toString()
}
