package com.linbox.apps.imageviewer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope

val ImageViewerApp = AppDef(
    id = "image_viewer",
    displayName = "图片查看器",
    iconAsset = "app:image_viewer",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 640.dp,
    defaultHeight = 480.dp,
    pinnedToDesktop = true
) { scope ->
    ImageViewerContent(scope)
}

@Composable
private fun ImageViewerContent(scope: WindowContentScope) {
    val context = LocalContext.current

    // 默认尝试加载主题壁纸
    val assetPath = scope.windowState.launchArgs["asset"]
    // 真实文件路径（来自 /storage/emulated/0/ 下的图片）
    val realPath = scope.windowState.launchArgs["path"]

    val bitmap = remember(assetPath, realPath) {
        when {
            // 优先用真实路径
            realPath != null -> runCatching {
                BitmapFactory.decodeFile(realPath)
            }.getOrNull()
            // 回退到 assets
            assetPath != null -> runCatching {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            else -> null
        }
    }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🖼️", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "无法加载图片\n路径: ${realPath ?: assetPath ?: "未指定"}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 底部工具栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.6f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                scale = (scale * 0.8f).coerceIn(0.5f, 5f)
            }) {
                Icon(Icons.Default.ZoomOut, "Zoom Out", tint = Color.White)
            }
            Text(
                "${(scale * 100).toInt()}%",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.width(50.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(onClick = {
                scale = (scale * 1.25f).coerceIn(0.5f, 5f)
            }) {
                Icon(Icons.Default.ZoomIn, "Zoom In", tint = Color.White)
            }
            IconButton(onClick = {
                scale = 1f; offsetX = 0f; offsetY = 0f
            }) {
                Icon(Icons.Default.Refresh, "Reset", tint = Color.White)
            }
        }
    }
}
