package com.linbox.core.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints

/**
 * 窗口宿主：渲染所有窗口，按 zIndex 排序。
 *
 * 订阅 WindowManager 的状态变化，每次窗口列表变更时 recompose。
 * FULLSCREEN 应用占满工作区（任务栏上方），FLOATING 应用按自身尺寸渲染。
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

        // 按 zIndex 升序渲染，zIndex 大的在最上层
        val sorted = remember(revision) {
            wm.windows.sortedBy { it.zIndex }
        }

        sorted.forEach { state ->
            if (state.isVisible) {
                key(state.id) {
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
