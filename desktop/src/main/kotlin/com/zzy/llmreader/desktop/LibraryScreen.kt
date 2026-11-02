package com.zzy.llmreader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zzy.llmreader.data.RecentBook
import com.zzy.llmreader.data.RecentBookSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    state: DesktopUiState,
    app: DesktopAppState,
    onOpenFile: () -> Unit,
    onAddUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的书房", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (state.recents.isEmpty()) "一段故事，从这里开始。"
                        else "${state.recents.size} 本书，随时等你回来。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    onClick = onOpenFile,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加书籍", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        if (state.recents.isNotEmpty()) {
            item { HeroReading(book = state.recents.first(), onOpen = { app.openRecent(state.recents.first()) }) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.recents.isEmpty()) "开始你的书架" else "全部书籍",
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAddUrl) {
                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("从网页添加")
                }
            }
        }
        if (state.loading) {
            item { LoadingShelf(state.loadingText) }
        } else if (state.recents.isEmpty()) {
            item { EmptyShelf(onOpenFile, onAddUrl) }
        } else {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(152.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                    modifier = Modifier.fillMaxWidth().height((((state.recents.size + 4) / 5) * 252).dp),
                ) {
                    items(state.recents, key = { it.uri }) { book ->
                        ShelfBook(book, onOpen = { app.openRecent(book) }, onRemove = { app.removeRecent(book) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroReading(book: RecentBook, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.title, width = 76.dp, height = 108.dp, hero = true)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("接着上次的旅程", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .7f))
                Spacer(Modifier.height(8.dp))
                Text(book.title, style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("已为你保留阅读位置", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f))
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .13f)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("继续阅读", color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ShelfBook(book: RecentBook, onOpen: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.widthIn(max = 160.dp)) {
        Box {
            Surface(onClick = onOpen, color = Color.Transparent) {
                BookCover(book.title, width = 144.dp, height = 194.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (book.source == RecentBookSource.WEB) "在线 · ${formatTime(book.openedAt)}" else "本地 · ${formatTime(book.openedAt)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.DeleteOutline,
                contentDescription = "移除《${book.title}》",
                modifier = Modifier.size(18.dp).clickable(onClick = onRemove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BookCover(title: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, hero: Boolean = false) {
    val (start, end) = coverPalette(title)
    Box(
        modifier = Modifier
            .size(width, height)
            .shadow(if (hero) 4.dp else 8.dp, RoundedCornerShape(5.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(start, end)), RoundedCornerShape(5.dp)),
    ) {
        Box(Modifier.width(5.dp).height(height).background(Color.White.copy(alpha = .12f)))
        Column(Modifier.padding(start = 18.dp, top = 20.dp, end = 12.dp, bottom = 14.dp)) {
            Text(title, style = if (width > 100.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontFamily = SerifReading, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("静  心  阅  读", style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = .66f))
        }
    }
}

@Composable
private fun EmptyShelf(onOpenFile: () -> Unit, onAddUrl: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null,
                modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text("先放进一本书", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("本地 TXT / Markdown / HTML / EPUB，或在线小说链接。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenFile) { Text("选择文件") }
            TextButton(onClick = onAddUrl) { Text("粘贴链接") }
        }
    }
}

@Composable
private fun LoadingShelf(label: String) {
    Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(16.dp))
        Text(label.ifBlank { "正在准备书籍…" })
    }
}

private val timeFormat = SimpleDateFormat("M月d日", Locale.getDefault())
private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
