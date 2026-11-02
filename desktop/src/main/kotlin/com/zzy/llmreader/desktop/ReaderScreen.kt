package com.zzy.llmreader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zzy.llmreader.data.ReadingTheme
import com.zzy.llmreader.data.TranslationMode
import com.zzy.llmreader.format.RichBlock
import com.zzy.llmreader.format.htmlToRichBlocks
import com.zzy.llmreader.format.stripInlineImageMarkers
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.model.Chapter
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ReaderScreen(
    state: DesktopUiState,
    app: DesktopAppState,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onBack: () -> Unit,
    onShowToc: () -> Unit,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapter = state.current
    val listState = rememberLazyListState()

    // 网页小说章节懒加载：切到未抓取的章节时按需拉取
    LaunchedEffect(state.currentChapter, state.webCanonicalUrl, chapter?.content == null) {
        if (chapter?.content == null) app.ensureChapterLoaded(state.currentChapter)
    }
    // 切章回到顶部
    LaunchedEffect(state.currentChapter) { listState.scrollToItem(0) }
    // 打开书时恢复一次；主动切章仍回顶部。窗口/字号变化只重新测量，不突然跳章。
    var lastMeasuredBook by remember { androidx.compose.runtime.mutableStateOf<BookContent.Text?>(null) }
    var lastMeasuredChapter by remember { androidx.compose.runtime.mutableStateOf(-1) }

    val (bg, fg) = readingColors(state.prefs.readingTheme, state.prefs.appTheme)
    val canvas = if (state.prefs.readingTheme == ReadingTheme.NIGHT) Color(0xFF20221F) else bg
    val mode = state.translationMode

    val primaryHtml = remember(chapter, mode) { primaryHtml(chapter, mode) }
    val secondaryHtml = remember(chapter, mode) {
        // 只有**确实存在译文**时才显示原文对照。
        // 旧实现无条件取 chapter.content，于是未翻译的章节在双语模式下
        // 会被原样印两遍，中间还加一条「—— 原文 ——」分隔线。
        if (mode == TranslationMode.BILINGUAL && chapter?.translatedContent != null) {
            chapter.content
        } else {
            null
        }
    }
    val primaryBlocks = remember(primaryHtml) { primaryHtml.toBlocks() }
    val secondaryBlocks = remember(secondaryHtml) { secondaryHtml.toBlocks() }
    val listScrollRange = remember { mutableStateOf(0f) }
    val blockStartIndex = if (mode != TranslationMode.ORIGINAL &&
        chapter?.translatedContent == null &&
        state.currentChapter !in state.translatingChapters
    ) 2 else 1

    // 等 LazyList 完成测量后拿到可滚动像素范围，保存与恢复使用同一个比例。
    LaunchedEffect(listState, state.book, state.currentChapter, blockStartIndex) {
        snapshotFlow { listState.layoutInfo.totalItemsCount to listState.layoutInfo.viewportSize }
            .collectLatest {
                listScrollRange.value = measureScrollRange(listState, blockStartIndex)
                if (lastMeasuredBook != state.book || lastMeasuredChapter != state.currentChapter) {
                    lastMeasuredBook = state.book
                    lastMeasuredChapter = state.currentChapter
                    val fraction = state.initialChapterScrollFraction
                    if (fraction > 0f && listScrollRange.value > 0f) {
                        listState.scrollBy(fraction * listScrollRange.value)
                    }
                }
            }
    }
    LaunchedEffect(listState, state.book, state.currentChapter, blockStartIndex) {
        snapshotFlow {
            val range = listScrollRange.value
            if (range <= 0f) 0f
            else (listState.firstVisibleItemScrollOffset.toFloat() / range).coerceIn(0f, 1f)
        }.collectLatest { app.setChapterScrollFraction(state.currentChapter, it) }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        when {
            chapter == null -> CenterHint("没有章节内容", fg)
            state.currentChapter in state.loadingChapters -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("正在抓取本章正文…", color = fg)
                    }
                }
            }
            primaryBlocks.isEmpty() -> CenterHint("本章暂无正文", fg)
            else -> Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val contentWidth = (maxWidth - 48.dp).coerceAtMost(840.dp)
                    Surface(
                        modifier = Modifier.fillMaxSize().width(contentWidth).align(Alignment.TopCenter),
                        color = canvas,
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 48.dp, vertical = 36.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                    if (mode != TranslationMode.ORIGINAL &&
                        chapter.translatedContent == null &&
                        state.currentChapter !in state.translatingChapters
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = fg.copy(alpha = .06f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "本章尚未翻译，当前显示原文。打开阅读面板即可开始翻译。",
                                    color = fg.copy(alpha = .75f),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                )
                            }
                        }
                    }
                    item {
                        val title = chapter.translatedTitle
                            ?.takeIf { mode != TranslationMode.ORIGINAL && it.isNotBlank() }
                            ?: chapter.title
                        Text(
                            text = title,
                            color = fg,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = SerifReading,
                            modifier = Modifier.padding(bottom = 18.dp),
                        )
                    }
                    itemsIndexed(primaryBlocks) { _, block ->
                        RichBlockView(block, fg, state.fontScale)
                    }
                        if (secondaryBlocks.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(24.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(1.dp).height(18.dp).background(fg.copy(alpha = .3f)))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "原文对照",
                                        color = fg.copy(alpha = .6f),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        itemsIndexed(secondaryBlocks) { _, block ->
                            RichBlockView(block, fg.copy(alpha = 0.72f), state.fontScale * 0.94f)
                        }
                        }
                        }
                    }
                }
                ChapterNavBar(state = state, app = app, fg = fg)
            }
        }
        if (chromeVisible) {
            ReaderFloatingTools(
                state = state,
                onBack = onBack,
                onShowToc = onShowToc,
                onShowSettings = onShowSettings,
            )
        } else {
            Surface(
                onClick = onToggleChrome,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .75f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
            ) {
                Text("轻点显示阅读工具", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 中央空白阅读区用于切换沉浸模式；文字和按钮仍可正常点击。
        if (chromeVisible) {
            Box(Modifier.align(Alignment.Center).clickable(onClick = onToggleChrome))
        }
    }
}

@Composable
private fun ReaderFloatingTools(
    state: DesktopUiState,
    onBack: () -> Unit,
    onShowToc: () -> Unit,
    onShowSettings: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
            shadowElevation = 4.dp) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
            }
        }
        Surface(
            shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            shadowElevation = 4.dp, modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShowToc) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "打开目录")
                }
                Text(
                    state.current?.translatedTitle ?: state.current?.title.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 250.dp),
                )
                IconButton(onClick = onShowSettings) {
                    Icon(Icons.Default.Tune, contentDescription = "阅读设置")
                }
            }
        }
    }
}

@Composable
private fun ChapterNavBar(state: DesktopUiState, app: DesktopAppState, fg: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = { app.prevChapter() },
                enabled = state.currentChapter > 0,
                shape = RoundedCornerShape(10.dp),
            ) { Text("← 上一章") }

            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "第 ${state.currentChapter + 1} 章 / 共 ${state.chapterCount} 章",
                    color = fg,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { ((state.currentChapter + 1f) / state.chapterCount.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth(),
                )
            }

            OutlinedButton(
                onClick = { app.nextChapter() },
                enabled = state.currentChapter < state.chapterCount - 1,
                shape = RoundedCornerShape(10.dp),
            ) { Text("下一章 →") }
        }
    }
}

@Composable
private fun RichBlockView(block: RichBlock, color: Color, fontScale: Float) {
    val annotated: AnnotatedString = remember(block) {
        buildAnnotatedString {
            block.runs.forEach { run ->
                withStyle(
                    SpanStyle(
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                    ),
                ) { append(run.text) }
            }
        }
    }
    val baseSize = when (block.heading) {
        1 -> 22f
        2 -> 20f
        3 -> 18f
        else -> 17f
    }
    Text(
        text = annotated,
        color = color,
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(
            fontFamily = SerifReading,
            fontSize = (baseSize * fontScale).sp,
            lineHeight = (baseSize * fontScale * 1.75f).sp,
            fontWeight = if (block.heading > 0) FontWeight.SemiBold else FontWeight.Normal,
            // 正文段落首行缩进两格，贴近中文排版习惯
            textIndent = if (block.heading == 0 && !block.listItem) {
                TextIndent(firstLine = (baseSize * fontScale * 2).sp)
            } else {
                TextIndent.None
            },
        ),
    )
}

@Composable
private fun CenterHint(text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun String?.toBlocks(): List<RichBlock> =
    this?.let { htmlToRichBlocks(stripInlineImageMarkers(it)) } ?: emptyList()

/** 通过临时滚动测量完整列表，再回到原位置；不改动持久化进度。 */
private suspend fun measureScrollRange(
    listState: androidx.compose.foundation.lazy.LazyListState,
    contentStartIndex: Int,
): Float {
    val originalIndex = listState.firstVisibleItemIndex
    val originalOffset = listState.firstVisibleItemScrollOffset
    return try {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= contentStartIndex) return 0f
        listState.scrollToItem(total - 1)
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return 0f
        (last.offset + last.size - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat()
    } finally {
        listState.scrollToItem(originalIndex, originalOffset)
    }
}

private fun primaryHtml(chapter: Chapter?, mode: TranslationMode): String? {
    if (chapter == null) return null
    return when (mode) {
        TranslationMode.ORIGINAL -> chapter.content
        // 未翻译时回落到原文（翻译由 cycleTranslationMode 自动触发，
        // 未完成期间不该是一片空白），但界面上会有「尚未翻译」的提示。
        TranslationMode.TRANSLATED -> chapter.translatedContent ?: chapter.content
        TranslationMode.BILINGUAL -> chapter.translatedContent ?: chapter.content
    }
}

/** 阅读页配色：日间 / 护眼米黄 / 夜间。 */
private fun readingColors(theme: ReadingTheme, appTheme: com.zzy.llmreader.data.AppTheme): Pair<Color, Color> =
    when (theme) {
        ReadingTheme.DAY -> Color(0xFFFFFFFF) to Color(0xFF1A1A1A)
        ReadingTheme.SEPIA -> Color(0xFFF6ECD8) to Color(0xFF3B2F1F)
        ReadingTheme.NIGHT -> Color(0xFF121212) to Color(0xFFC8C8C8)
    }
