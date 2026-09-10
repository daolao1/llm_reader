package com.zzy.llmreader.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.pdf.PdfPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PdfReader(
    padding: PaddingValues,
    book: BookContent.Pdf,
    fontScale: Float,
    onSetFontScale: (Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pager = remember(book.sourceUri) {
        runCatching { PdfPager(context, Uri.parse(book.sourceUri)) }
    }

    DisposableEffect(book.sourceUri) {
        onDispose { pager.getOrNull()?.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val pp = pager.getOrNull()
        if (pp == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "PDF 打开失败：${pager.exceptionOrNull()?.message}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("共 ${pp.pageCount} 页", style = MaterialTheme.typography.titleSmall)
            Row {
                Button(onClick = { onSetFontScale(fontScale - 0.1f) }) { Text("缩小") }
                Text(
                    "%.1fx".format(fontScale),
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .align(Alignment.CenterVertically),
                )
                Button(onClick = { onSetFontScale(fontScale + 0.1f) }) { Text("放大") }
            }
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pp.pageCount) { index ->
                PdfPageItem(pager = pp, pageIndex = index, scale = fontScale)
            }
        }
    }
}

@Composable
private fun PdfPageItem(pager: PdfPager, pageIndex: Int, scale: Float) {
    var bmp by remember(pageIndex, scale) { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(pageIndex, scale) {
        val widthPx = with(density) {
            // 估算屏幕宽（dp -> px），实际渲染宽度由 LazyColumn 决定
            (1080f * scale).toInt()
        }
        bmp = withContext(Dispatchers.IO) {
            runCatching { pager.render(pageIndex, widthPx) }.getOrNull()
        }
    }

    val current = bmp
    if (current == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f),
            contentAlignment = Alignment.Center,
        ) {
            Text("加载第 ${pageIndex + 1} 页…")
        }
    } else {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}
