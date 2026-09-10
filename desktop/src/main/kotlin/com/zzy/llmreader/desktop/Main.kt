package com.zzy.llmreader.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * 桌面端入口。
 *
 * 与 Android 端共用 :shared 里的全部核心逻辑（站点抓取、LLM 翻译、配置仓储、
 * 本地文档解析），这里只提供桌面 UI。
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 880.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "LLM Reader",
    ) {
        DesktopApp()
    }
}
