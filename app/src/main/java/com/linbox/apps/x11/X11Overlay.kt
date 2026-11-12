package com.linbox.apps.x11

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.x11.X11InputHub


/**
 * X11 附加键盘栏（termux-x11 showAdditionalKbd 对应物）：
 * 画面底部一排玻璃键帽，点按经 X11InputHub 直注 X server ——
 * 不依赖焦点，wine/桌面环境均立即生效；CTRL/ALT 为粘滞修饰
 * （点亮后作用于下一个按键，用后自动释放）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun X11ExtraKeysBar() {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        X11KeyChip("CTRL", active = ctrl) { ctrl = !ctrl }
        X11KeyChip("ALT", active = alt) { alt = !alt }
        listOf(
            "ESC" to KeyEvent.KEYCODE_ESCAPE,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "↑" to KeyEvent.KEYCODE_DPAD_UP,
            "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
            "←" to KeyEvent.KEYCODE_DPAD_LEFT,
            "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
            "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
            "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
            "END" to KeyEvent.KEYCODE_MOVE_END,
            "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
            "回车" to KeyEvent.KEYCODE_ENTER
        ).forEach { (label, keyCode) ->
            X11KeyChip(label, active = false) {
                // 粘滞修饰：先按下 → 目标键按下/抬起 → 修饰抬起并释放粘滞态
                if (ctrl) X11InputHub.forwardKey(KeyEvent.KEYCODE_CTRL_LEFT, true)
                if (alt) X11InputHub.forwardKey(KeyEvent.KEYCODE_ALT_LEFT, true)
                X11InputHub.forwardKey(keyCode, true)
                X11InputHub.forwardKey(keyCode, false)
                if (ctrl) { X11InputHub.forwardKey(KeyEvent.KEYCODE_CTRL_LEFT, false); ctrl = false }
                if (alt) { X11InputHub.forwardKey(KeyEvent.KEYCODE_ALT_LEFT, false); alt = false }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun X11KeyChip(label: String, active: Boolean, onTap: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(36.dp)
            .background(
                if (active) Color(0x661B5E20) else Color(0x3DFFFFFF),
                RoundedCornerShape(6.dp)
            )
            .border(0.8.dp, Color(0x59FFFFFF), RoundedCornerShape(6.dp))
            .combinedClickable(onClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onTap()
            }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color(0xFF69F0AE) else Color(0xFFE0E0E0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

