package com.zzy.llmreader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zzy.llmreader.LlmReaderCore
import com.zzy.llmreader.data.AppTheme
import com.zzy.llmreader.data.ReadingTheme
import com.zzy.llmreader.data.TranslationMode
import java.awt.FileDialog
import java.awt.Frame
import java.awt.SystemColor
import java.io.File

@Composable
fun DesktopApp() {
    val core = remember { LlmReaderCore(defaultDesktopDirs()) }
    val scope = rememberCoroutineScope()
    val app = remember(core, scope) { DesktopAppState(core, scope) }
    val state by app.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showReadingPanel by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    val dark = when (state.prefs.appTheme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> isSystemDark()
    }
    ReaderTheme(dark = dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (state.book == null) {
                LibraryScreen(
                    state = state,
                    app = app,
                    onOpenFile = { pickFile()?.let(app::openLocalFile) },
                    onAddUrl = { showUrlDialog = true },
                    modifier = Modifier.fillMaxSize(),
                )
                LibraryTopActions(
                    onTheme = {
                        val next = when (state.prefs.appTheme) {
                            AppTheme.SYSTEM -> AppTheme.LIGHT
                            AppTheme.LIGHT -> AppTheme.DARK
                            AppTheme.DARK -> AppTheme.SYSTEM
                        }
                        app.savePrefs(state.prefs.copy(appTheme = next))
                    },
                    onSettings = { showSettings = true },
                    dark = state.prefs.appTheme == AppTheme.DARK,
                )
            } else {
                ReaderScreen(
                    state = state,
                    app = app,
                    chromeVisible = chromeVisible,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onBack = { app.closeBook() },
                    onShowToc = { showToc = true },
                    onShowSettings = { showReadingPanel = true },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showToc) {
                    ChapterDrawer(state, app, onDismiss = { showToc = false })
                }
                if (showReadingPanel) {
                    ReadingPanel(state, app, onDismiss = { showReadingPanel = false })
                }
            }
            if (showSettings) SettingsDialog(state, app, onDismiss = { showSettings = false })
            if (showUrlDialog) UrlDialog(onDismiss = { showUrlDialog = false }, onConfirm = {
                showUrlDialog = false
                app.openWebBook(it)
            })
            state.error?.let { ErrorBar(it, onDismiss = { app.dismissError() }) }
        }
    }
}

@Composable
private fun LibraryTopActions(onTheme: () -> Unit, onSettings: () -> Unit, dark: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onTheme) {
            Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "切换主题")
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
        Spacer(Modifier.width(156.dp))
    }
}

@Composable
private fun ChapterDrawer(state: DesktopUiState, app: DesktopAppState, onDismiss: () -> Unit) {
    val book = state.book ?: return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f)).clickable(onClick = onDismiss))
        Surface(
            modifier = Modifier.width(310.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("目录", style = MaterialTheme.typography.titleLarge)
                        Text(book.title, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭目录") }
                }
                Spacer(Modifier.height(20.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(book.chapters) { index, chapter ->
                        if (index == 0 || chapter.groupTitle != book.chapters[index - 1].groupTitle) {
                            chapter.groupTitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
                            }
                        }
                        val selected = index == state.currentChapter
                        Surface(
                            onClick = { app.selectChapter(index); onDismiss() },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(30.dp))
                                Text(chapter.translatedTitle?.takeIf { state.translationMode != TranslationMode.ORIGINAL && it.isNotBlank() }
                                    ?: chapter.title,
                                    style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (chapter.translatedContent != null) {
                                    Icon(Icons.Default.Translate, contentDescription = "已有译文",
                                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingPanel(state: DesktopUiState, app: DesktopAppState, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .2f)).clickable(onClick = onDismiss))
        Surface(
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(20.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Text("阅读偏好", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭") }
                }
                Spacer(Modifier.height(22.dp))
                Text("显示方式", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslationMode.entries.forEach { mode ->
                        Surface(
                            onClick = { app.savePrefs(state.prefs.copy(translationMode = mode)) },
                            shape = RoundedCornerShape(50),
                            color = if (state.translationMode == mode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(when (mode) {
                                TranslationMode.ORIGINAL -> "原文"
                                TranslationMode.TRANSLATED -> "译文"
                                TranslationMode.BILINGUAL -> "双语对照"
                            }, modifier = Modifier.padding(horizontal = 17.dp, vertical = 10.dp),
                                color = if (state.translationMode == mode) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatSize, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("正文字号", modifier = Modifier.weight(1f))
                    TextButton(onClick = { app.setFontScale(state.fontScale - .1f) }) { Text("A−") }
                    Text("${(state.fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { app.setFontScale(state.fontScale + .1f) }) { Text("A+") }
                }
                Spacer(Modifier.height(12.dp))
                Text("阅读纸张", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReadingTheme.entries.forEach { theme ->
                        Surface(
                            onClick = { app.savePrefs(state.prefs.copy(readingTheme = theme)) },
                            shape = CircleShape,
                            color = when (theme) {
                                ReadingTheme.DAY -> Color(0xFFFCFAF2)
                                ReadingTheme.SEPIA -> Color(0xFFE9DBC1)
                                ReadingTheme.NIGHT -> Color(0xFF20231F)
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                if (state.prefs.readingTheme == theme) 2.dp else 1.dp,
                                if (state.prefs.readingTheme == theme) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.outline,
                            ),
                            modifier = Modifier.size(38.dp),
                        ) {}
                    }
                    Text(when (state.prefs.readingTheme) {
                        ReadingTheme.DAY -> "明亮"
                        ReadingTheme.SEPIA -> "护眼"
                        ReadingTheme.NIGHT -> "夜间"
                    }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically))
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { app.translateChapter(state.currentChapter); onDismiss() },
                    enabled = state.currentChapter !in state.translatingChapters,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.currentChapter in state.translatingChapters) "正在翻译…" else "翻译本章")
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
        title = { Text("添加一本在线小说") },
        text = {
            Column {
                Text("粘贴作品链接，目录和阅读进度会保存在本机。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("小说链接") }, placeholder = { Text("https://…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("支持 Literotica、Syosetu、AO3、Pixiv、Hameln、Kakuyomu、ScribbleHub、Royal Road。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) { Text("加入书架") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

private fun pickFile(): File? {
    val dialog = FileDialog(null as Frame?, "选择小说文件", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val lower = name.lowercase()
        listOf(".txt", ".md", ".markdown", ".html", ".htm", ".xhtml", ".epub", ".log").any { lower.endsWith(it) }
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: return null, name)
}

private fun isSystemDark(): Boolean {
    val bg = runCatching { SystemColor.window }.getOrNull() ?: return false
    return bg.red * .299 + bg.green * .587 + bg.blue * .114 < 128
}
