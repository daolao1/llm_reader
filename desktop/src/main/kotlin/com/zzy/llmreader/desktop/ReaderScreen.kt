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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.zzy.llmreader.model.Chapter

@Composable
fun ReaderScreen(state: DesktopUiState, app: DesktopAppState, modifier: Modifier = Modifier) {
    val chapter = state.current
    val listState = rememberLazyListState()

    // 网页小说章节懒加载：切到未抓取的章节时按需拉取
    LaunchedEffect(state.currentChapter, state.webCanonicalUrl, chapter?.content == null) {
        if (chapter?.content == null) app.ensureChapterLoaded(state.currentChapter)
    }
    // 切章回到顶部
    LaunchedEffect(state.currentChapter) { listState.scrollToItem(0) }

    val (bg, fg) = readingColors(state.prefs.readingTheme, state.prefs.appTheme)
    val mode = state.translationMode

    val primaryHtml = remember(chapter, mode) { primaryHtml(chapter, mode) }
    val secondaryHtml = remember(chapter, mode) {
        if (mode == TranslationMode.BILINGUAL) chapter?.content else null
    }
    val primaryBlocks = remember(primaryHtml) { primaryHtml.toBlocks() }
    val secondaryBlocks = remember(secondaryHtml) { secondaryHtml.toBlocks() }

    Box(modifier = modifier.background(bg)) {
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 40.dp,
                        vertical = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        val title = chapter.translatedTitle
                            ?.takeIf { mode != TranslationMode.ORIGINAL && it.isNotBlank() }
                            ?: chapter.title
                        Text(
                            text = title,
                            color = fg,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    itemsIndexed(primaryBlocks) { _, block ->
                        RichBlockView(block, fg, state.fontScale)
                    }
                    if (secondaryBlocks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                text = "—— 原文 ——",
                                color = fg.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        itemsIndexed(secondaryBlocks) { _, block ->
                            RichBlockView(block, fg.copy(alpha = 0.72f), state.fontScale * 0.94f)
                        }
                    }
                }
                ChapterNavBar(state = state, app = app, fg = fg)
            }
        }
    }
}

@Composable
private fun ChapterNavBar(state: DesktopUiState, app: DesktopAppState, fg: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = { app.prevChapter() },
            enabled = state.currentChapter > 0,
        ) { Text("上一章") }

        Text(
            text = "${state.currentChapter + 1} / ${state.chapterCount}",
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )

        OutlinedButton(
            onClick = { app.nextChapter() },
            enabled = state.currentChapter < state.chapterCount - 1,
        ) { Text("下一章") }
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
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
        style = TextStyle(
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

private fun primaryHtml(chapter: Chapter?, mode: TranslationMode): String? {
    if (chapter == null) return null
    return when (mode) {
        TranslationMode.ORIGINAL -> chapter.content
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
