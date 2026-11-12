package com.linbox.core.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex

/**
 * 窗口宿主：渲染所有窗口，按 zIndex 分层。
 *
 * v3.2.8（用户问题 3 修复·防）：置前不再改子节点顺序 —— 改用
 * `Modifier.zIndex` 纯绘制分层。旧实现按 zIndex 排序 children：每次点击
 * 置前都会重排 Box 子节点，AndroidView 互操作节点（X11 桌面的
 * LorieView —— SurfaceView）随重排经历 detach/attach，SurfaceView 的
 * surface 在 detach 时被销毁（surfaceChanged(null) → X server 失去输出
 * surface）→ 设备实测"点击其他窗口后 X11 画面丢失、声音还在"（v3.2.1
 * 吞 0x0 只挡住了 X 屏被清 0x0，没挡住 surface 本体的销毁/重建链）。
 * zIndex 修饰符只影响同父节点内的绘制与命中顺序，零视图层级变动 ——
 * 置前从此不触碰 interop 视图。X11Surface 侧配套 surface 死亡检测 +
 * 重获顶层时 regenerate()（恢复面），两层合力根治。
 */
@Composable
fun WindowHost(
    workAreaModifier: Modifier = Modifier.fillMaxSize()
) {
    val wm = remember { WindowManager.get() }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        wm.observe { revision++ }
    }

    BoxWithConstraints(modifier = workAreaModifier) {
        val workWidth = constraints.maxWidth
        val workHeight = constraints.maxHeight

        // v3.2.8：维持插入顺序（稳定），zIndex 经 Modifier.zIndex 生效
        // （绘制/命中分层）。children 顺序永不变动 → interop 视图
        // （LorieView/SurfaceView）不因置前被 detach/attach。
        val windows = remember(revision) {
            wm.windows.toList()
        }

        windows.forEach { state ->
            if (state.isVisible) {
                key(state.id) {
                    Box(
                        modifier = Modifier
                            // zIndex 大 = 最上层（与旧 sortedBy zIndex 语义一致）
                            .zIndex(state.zIndex.toFloat())
                    ) {
                        val app = AppRegistry.get(state.appId)
                        if (app != null) {
                            WindowChrome(
                                state = state,
                                workAreaWidth = workWidth,
                                workAreaHeight = workHeight
                            ) {
                                val scope = WindowContentScope(
                                    windowState = state,
                                    onClose = { wm.close(state.id) },
                                    onTitleChange = { newTitle ->
                                        state.title = newTitle
                                    }
                                )
                                app.content.invoke(scope)
                            }
                        }
                    }
                }
            }
        }
    }
}
