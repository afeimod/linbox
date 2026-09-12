package com.linbox.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * Emoji 图标渲染器：用于快捷方式自定义图标的兜底。
 */
@Composable
fun EmojiPainter(emoji: String, size: Dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Text(text = emoji, fontSize = (size.value * 0.7f).sp)
    }
}
