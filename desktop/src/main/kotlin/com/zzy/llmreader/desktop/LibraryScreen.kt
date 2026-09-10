package com.zzy.llmreader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    Column(modifier = modifier.padding(24.dp)) {
        Text("书库", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "支持 TXT / Markdown / HTML / EPUB 本地文档，以及 8 个在线小说站点。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenFile) { Text("打开本地文件") }
            OutlinedButton(onClick = onAddUrl) { Text("添加网页小说") }
        }

        Spacer(Modifier.height(24.dp))
        Text("最近阅读", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(state.loadingText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            return
        }

        if (state.recents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有阅读记录。打开一个本地文件，或粘贴小说链接开始。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.recents, key = { it.uri }) { book ->
                RecentRow(
                    book = book,
                    onOpen = { app.openRecent(book) },
                    onRemove = { app.removeRecent(book) },
                )
            }
        }
    }
}

@Composable
private fun RecentRow(book: RecentBook, onOpen: () -> Unit, onRemove: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .background(
                        if (book.source == RecentBookSource.WEB) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (book.source == RecentBookSource.WEB) "网页" else "本地")
                        append(" · ")
                        append(formatTime(book.openedAt))
                        append(" · ")
                        append(book.uri)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "删除记录")
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
