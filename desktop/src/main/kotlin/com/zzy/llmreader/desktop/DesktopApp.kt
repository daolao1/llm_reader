package com.zzy.llmreader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zzy.llmreader.LlmReaderCore
import com.zzy.llmreader.data.AppTheme
import com.zzy.llmreader.data.TranslationMode
import java.awt.FileDialog
import java.awt.Frame
import java.awt.SystemColor
import java.io.File

/** 桌面端根组件：书库 / 阅读器二选一，外加设置与"添加链接"两个对话框。 */
@Composable
fun DesktopApp() {
    val core = remember { LlmReaderCore(defaultDesktopDirs()) }
    val scope = rememberCoroutineScope()
    val app = remember(core, scope) { DesktopAppState(core, scope) }
    val state by app.state.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(true) }

    val dark = when (state.prefs.appTheme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> isSystemDark()
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    state = state,
                    showToc = showToc,
                    onToggleToc = { showToc = !showToc },
                    onBack = { app.closeBook() },
                    onOpenFile = { pickFile()?.let(app::openLocalFile) },
                    onAddUrl = { showUrlDialog = true },
                    onTranslate = { app.translateChapter(state.currentChapter) },
                    onCycleMode = { app.cycleTranslationMode() },
                    onFontScale = { app.setFontScale(it) },
                    onCycleTheme = {
                        val next = when (state.prefs.appTheme) {
                            AppTheme.SYSTEM -> AppTheme.LIGHT
                            AppTheme.LIGHT -> AppTheme.DARK
                            AppTheme.DARK -> AppTheme.SYSTEM
                        }
                        app.savePrefs(state.prefs.copy(appTheme = next))
                    },
                    onSettings = { showSettings = true },
                )
                Divider()

                Row(modifier = Modifier.fillMaxSize()) {
                    if (state.book == null) {
                        LibraryScreen(
                            state = state,
                            app = app,
                            onOpenFile = { pickFile()?.let(app::openLocalFile) },
                            onAddUrl = { showUrlDialog = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        if (showToc) {
                            TocPane(state = state, app = app, modifier = Modifier.width(260.dp))
                            Divider(vertical = true)
                        }
                        ReaderScreen(state = state, app = app, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        if (showSettings) {
            SettingsDialog(state = state, app = app, onDismiss = { showSettings = false })
        }
        if (showUrlDialog) {
            UrlDialog(
                onDismiss = { showUrlDialog = false },
                onConfirm = { url ->
                    showUrlDialog = false
                    app.openWebBook(url)
                },
            )
        }
        state.error?.let { message ->
            ErrorBar(message = message, onDismiss = { app.dismissError() })
        }
    }
}

@Composable
private fun TopBar(
    state: DesktopUiState,
    showToc: Boolean,
    onToggleToc: () -> Unit,
    onBack: () -> Unit,
    onOpenFile: () -> Unit,
    onAddUrl: () -> Unit,
    onTranslate: () -> Unit,
    onCycleMode: () -> Unit,
    onFontScale: (Float) -> Unit,
    onCycleTheme: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.book != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回书库")
            }
            IconButton(onClick = onToggleToc) {
                Icon(Icons.Filled.List, contentDescription = "目录")
            }
        }

        Text(
            text = state.book?.title ?: "LLM Reader",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )

        if (state.book != null) {
            state.translationProgress?.let {
                Text(it, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onCycleMode) {
                Icon(Icons.Filled.Translate, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(
                    when (state.translationMode) {
                        TranslationMode.ORIGINAL -> "原文"
                        TranslationMode.TRANSLATED -> "译文"
                        TranslationMode.BILINGUAL -> "双语"
                    },
                )
            }
            TextButton(
                onClick = onTranslate,
                enabled = state.currentChapter !in state.translatingChapters,
            ) {
                Text(if (state.currentChapter in state.translatingChapters) "翻译中…" else "翻译本章")
            }
            TextButton(onClick = { onFontScale(state.fontScale - 0.1f) }) { Text("A-") }
            TextButton(onClick = { onFontScale(state.fontScale + 0.1f) }) { Text("A+") }
        } else {
            TextButton(onClick = onOpenFile) { Text("打开文件") }
            TextButton(onClick = onAddUrl) { Text("添加网页小说") }
        }

        IconButton(onClick = onCycleTheme) {
            Icon(
                if (state.prefs.appTheme == AppTheme.DARK) Icons.Filled.LightMode
                else Icons.Filled.DarkMode,
                contentDescription = "切换主题",
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置")
        }
    }
}

@Composable
private fun TocPane(state: DesktopUiState, app: DesktopAppState, modifier: Modifier) {
    val book = state.book ?: return
    Column(modifier = modifier.fillMaxHeight().padding(8.dp)) {
        Text("目录（${book.chapters.size}）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(book.chapters) { index, chapter ->
                val selected = index == state.currentChapter
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    onClick = { app.selectChapter(index) },
                ) {
                    val label = chapter.translatedTitle
                        ?.takeIf { state.translationMode != TranslationMode.ORIGINAL && it.isNotBlank() }
                        ?: chapter.title
                    Text(
                        text = "${index + 1}. $label",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加网页小说") },
        text = {
            Column {
                Text(
                    "粘贴作品链接。支持 Literotica / Syosetu / AO3 / Pixiv / " +
                        "Hameln / Kakuyomu / ScribbleHub / Royal Road。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("小说 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) { Text("打开") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun Divider(vertical: Boolean = false) {
    val color = MaterialTheme.colorScheme.outlineVariant
    if (vertical) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(color))
    } else {
        Box(Modifier.fillMaxWidth().height(1.dp).background(color))
    }
}

/** 用 AWT 原生文件对话框挑一个小说文件。 */
private fun pickFile(): File? {
    val dialog = FileDialog(null as Frame?, "选择小说文件", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val lower = name.lowercase()
        listOf(".txt", ".md", ".markdown", ".html", ".htm", ".xhtml", ".epub", ".log")
            .any { lower.endsWith(it) }
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: return null, name)
}

private fun isSystemDark(): Boolean {
    val bg = runCatching { SystemColor.window }.getOrNull() ?: return false
    val luminance = bg.red * 0.299 + bg.green * 0.587 + bg.blue * 0.114
    return luminance < 128
}
