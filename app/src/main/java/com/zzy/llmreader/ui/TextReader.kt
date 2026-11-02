package com.zzy.llmreader.ui

import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zzy.llmreader.ui.theme.LocalReaderFontFamily
import com.zzy.llmreader.ui.theme.toTypeface
import com.zzy.llmreader.data.ReadingTheme
import com.zzy.llmreader.data.TranslationMode
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.ui.icons.ReaderIcons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun TextReader(
    padding: PaddingValues,
    book: BookContent.Text,
    currentChapter: Int,
    fontScale: Float,
    loadingChapters: Set<Int> = emptySet(),
    translatingChapters: Set<Int> = emptySet(),
    translationProgress: String? = null,
    translationMode: TranslationMode = TranslationMode.ORIGINAL,
    readingTheme: ReadingTheme = ReadingTheme.DAY,
    chromeVisible: Boolean = true,
    failedTranslations: Map<Int, String> = emptyMap(),
    onCycleTheme: () -> Unit = {},
    onSelectChapter: (Int) -> Unit,
    /** 跳到指定章节并定位到该章末尾（翻页翻到章首再往前时用）。 */
    onSelectChapterAtEnd: (Int) -> Unit = onSelectChapter,
    onSetFontScale: (Float) -> Unit,
    onAskClicked: () -> Unit,
    onRetryTranslation: (Int) -> Unit = {},
    /** 当前章节首次显示时使用的初始滚动百分比（0..1）。 */
    initialScrollFraction: Float = 0f,
    /** 当前章节滚动时回调，参数是 (章节下标, 滚动百分比 0..1)。 */
    onScrollFractionChange: (Int, Float) -> Unit = { _, _ -> },
) {
    var showToc by remember { mutableStateOf(false) }
    var showFontMenu by remember { mutableStateOf(false) }
    val safeIndex = currentChapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
    val chapter = book.chapters.getOrNull(safeIndex)
    val isChapterLoading = safeIndex in loadingChapters
    val isTranslating = safeIndex in translatingChapters

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(padding)) {
      Column(modifier = Modifier.fillMaxSize()) {

        // 章节标题卡：左侧条纹强调 + 标题 + 右侧进度
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            Column {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(width = 3.dp, height = 22.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.extraSmall,
                                ),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                        val displayTitle = when (translationMode) {
                            TranslationMode.TRANSLATED, TranslationMode.BILINGUAL ->
                                chapter?.translatedTitle ?: chapter?.title ?: ""
                            TranslationMode.ORIGINAL -> chapter?.title ?: ""
                        }
                        Text(
                            text = displayTitle,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        androidx.compose.material3.Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                "${safeIndex + 1} / ${book.chapters.size}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }

        // 译文模式下若正在翻译：在标题栏下方占位显示进度条 + "翻译中 N/M"，避免叠在正文上
        if (isTranslating) {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
                if (translationProgress != null) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Text(
                            translationProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 只渲染当前章；翻页 = 按屏幕高度翻一屏，章末/章首自动跨章（见 ChapterPage 内的手势层）。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            ChapterPage(
                chapterKey = safeIndex,
                chapter = chapter,
                isLoading = isChapterLoading,
                isTranslating = isTranslating,
                translationMode = translationMode,
                translationProgress = translationProgress,
                translationFailure = failedTranslations[safeIndex],
                fontScale = fontScale,
                onLoad = { onSelectChapter(safeIndex) },
                onRetryTranslation = { onRetryTranslation(safeIndex) },
                initialScrollFraction = initialScrollFraction,
                onScrollFractionChange = { f -> onScrollFractionChange(safeIndex, f) },
                canPrevChapter = safeIndex > 0,
                canNextChapter = safeIndex < book.chapters.size - 1,
                onPrevChapter = { if (safeIndex > 0) onSelectChapter(safeIndex - 1) },
                onNextChapter = { if (safeIndex < book.chapters.size - 1) onSelectChapter(safeIndex + 1) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            ReaderBottomBar(
                chapterIndex = safeIndex,
                chapterTotal = book.chapters.size,
                fontScale = fontScale,
                readingTheme = readingTheme,
                onShowToc = { showToc = true },
                onAsk = onAskClicked,
                onSetFontScale = onSetFontScale,
                onCycleTheme = onCycleTheme,
            )
        }
      }

        // 章节进度浮条（始终在最底部）
        androidx.compose.material3.Surface(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth(),
            color = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            val progress = if (book.chapters.isNotEmpty())
                (safeIndex + 1f) / book.chapters.size else 0f
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
        }

        // 阅读操作集中在底部面板，避免多个悬浮球遮挡正文。
    }

    if (showFontMenu) {
        AlertDialog(
            onDismissRequest = { showFontMenu = false },
            confirmButton = { TextButton(onClick = { showFontMenu = false }) { Text("完成") } },
            title = { Text("阅读设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("调到眼睛舒服的程度，设置会自动保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(ReaderIcons.FileText, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("正文字号", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onSetFontScale(fontScale - 0.1f) }) { Text("A−") }
                        Text("${(fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { onSetFontScale(fontScale + 0.1f) }) { Text("A+") }
                    }
                    Text("阅读纸张", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ReadingTheme.entries.forEach { theme ->
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                androidx.compose.material3.Surface(
                                    onClick = { if (theme != readingTheme) onCycleTheme() },
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = when (theme) {
                                        ReadingTheme.DAY -> androidx.compose.ui.graphics.Color(0xFFFCFAF2)
                                        ReadingTheme.SEPIA -> androidx.compose.ui.graphics.Color(0xFFE9DBC1)
                                        ReadingTheme.NIGHT -> androidx.compose.ui.graphics.Color(0xFF20231F)
                                    },
                                    border = androidx.compose.foundation.BorderStroke(
                                        if (readingTheme == theme) 2.dp else 1.dp,
                                        if (readingTheme == theme) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    ),
                                    modifier = Modifier.size(42.dp),
                                ) {}
                                Text(when (theme) {
                                    ReadingTheme.DAY -> "明亮"
                                    ReadingTheme.SEPIA -> "护眼"
                                    ReadingTheme.NIGHT -> "夜间"
                                }, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
        )
    }

    if (showToc) {
        TocDialog(
            entries = book.chapters.map { ch ->
                TocEntry(
                    title = ch.translatedTitle?.takeIf { it.isNotBlank() } ?: ch.title,
                    groupTitle = ch.groupTitle,
                )
            },
            current = safeIndex,
            onSelect = {
                onSelectChapter(it)
                showToc = false
            },
            onDismiss = { showToc = false },
        )
    }
}

private data class TocEntry(val title: String, val groupTitle: String?)

@Composable
private fun ReaderBottomBar(
    chapterIndex: Int,
    chapterTotal: Int,
    fontScale: Float,
    readingTheme: ReadingTheme,
    onShowToc: () -> Unit,
    onAsk: () -> Unit,
    onSetFontScale: (Float) -> Unit,
    onCycleTheme: () -> Unit,
) {
    var showFontMenu by remember { mutableStateOf(false) }

    androidx.compose.material3.Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.safeDrawing
                        .only(WindowInsetsSides.Bottom)
                ),
        ) {
            // 章节进度条
            val progress = if (chapterTotal > 0) (chapterIndex + 1f) / chapterTotal else 0f
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                BottomBarButton(
                    icon = ReaderIcons.BookOpen,
                    label = "目录",
                    onClick = onShowToc,
                )
                BottomBarButton(
                    icon = ReaderIcons.Tune,
                    label = "阅读设置",
                    onClick = { showFontMenu = true },
                )
                BottomBarButton(
                    icon = ReaderIcons.MessageQuestion,
                    label = "问 AI",
                    onClick = onAsk,
                )
            }
        }
    }

    if (showFontMenu) {
        AlertDialog(
            onDismissRequest = { showFontMenu = false },
            confirmButton = { TextButton(onClick = { showFontMenu = false }) { Text("完成") } },
            title = { Text("阅读设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("调到眼睛舒服的程度，设置会自动保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(ReaderIcons.FileText, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("正文字号", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onSetFontScale(fontScale - 0.1f) }) { Text("A−") }
                        Text("${(fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { onSetFontScale(fontScale + 0.1f) }) { Text("A+") }
                    }
                    Text("阅读纸张", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ReadingTheme.entries.forEach { theme ->
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                androidx.compose.material3.Surface(
                                    onClick = { if (theme != readingTheme) onCycleTheme() },
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = when (theme) {
                                        ReadingTheme.DAY -> androidx.compose.ui.graphics.Color(0xFFFCFAF2)
                                        ReadingTheme.SEPIA -> androidx.compose.ui.graphics.Color(0xFFE9DBC1)
                                        ReadingTheme.NIGHT -> androidx.compose.ui.graphics.Color(0xFF20231F)
                                    },
                                    border = androidx.compose.foundation.BorderStroke(
                                        if (readingTheme == theme) 2.dp else 1.dp,
                                        if (readingTheme == theme) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    ),
                                    modifier = Modifier.size(42.dp),
                                ) {}
                                Text(when (theme) {
                                    ReadingTheme.DAY -> "明亮"
                                    ReadingTheme.SEPIA -> "护眼"
                                    ReadingTheme.NIGHT -> "夜间"
                                }, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun BottomBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Column(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp)
            .let { if (enabled) it.then(Modifier) else it }
            .clickableIfEnabled(enabled, onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun Modifier.clickableIfEnabled(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick) else this

@Composable
private fun ChapterPage(
    chapterKey: Int,
    chapter: com.zzy.llmreader.model.Chapter?,
    isLoading: Boolean,
    isTranslating: Boolean,
    translationMode: TranslationMode,
    translationProgress: String?,
    translationFailure: String?,
    fontScale: Float,
    onLoad: () -> Unit,
    onRetryTranslation: () -> Unit,
    modifier: Modifier = Modifier,
    initialScrollFraction: Float = 0f,
    onScrollFractionChange: (Float) -> Unit = {},
    canPrevChapter: Boolean = false,
    canNextChapter: Boolean = false,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
) {
    // Chapter.content 在 shared 里是 HTML 字符串；这里转成 TextView 用的 Spanned。
    val original: Spanned? = remember(chapter?.content) { htmlToSpanned(chapter?.content) }
    val translated: Spanned? = remember(chapter?.translatedContent) {
        htmlToSpanned(chapter?.translatedContent)
    }
    val primary: Spanned? = when (translationMode) {
        TranslationMode.ORIGINAL -> original
        TranslationMode.TRANSLATED -> translated ?: original
        TranslationMode.BILINGUAL -> translated ?: original
    }
    val secondary: Spanned? = when (translationMode) {
        TranslationMode.BILINGUAL -> if (translated != null) original else null
        else -> null
    }
    val showFailureBanner = translationFailure != null &&
        translationMode != TranslationMode.ORIGINAL && !isTranslating

    // "一键生成本章全部插图"按钮 + 进度浮层所需状态
    val inlineTop = LocalInlineImageHandlers.current
    val allInlinePrompts: List<String> = remember(primary, inlineTop.enabled) {
        if (!inlineTop.enabled || primary == null) emptyList()
        else parseChapterSegments(primary).mapNotNull {
            (it as? ChapterSegment.ImageMarker)?.prompt
        }
    }
    var showBatchConfirm by remember(chapterKey) { mutableStateOf(false) }
    // 章节内统一的滚动状态：每个章节用独立的 ScrollState（按下标 key，标题可能重复，不能用标题做 key），
    // 切章时自动从顶部(0)开始，避免复用上一章的滚动值。
    val scrollState = remember(chapterKey) {
        androidx.compose.foundation.ScrollState(0)
    }
    var didRestore by remember(chapterKey) { mutableStateOf(initialScrollFraction <= 0f) }
    LaunchedEffect(scrollState, primary, secondary, initialScrollFraction) {
        if (didRestore) return@LaunchedEffect
        // 等内容布局完，maxValue 才有值；用 first { it > 0 } 拿到第一帧有效高度即可。
        val mx = snapshotFlow { scrollState.maxValue }
            .first { it > 0 }
        val target = (initialScrollFraction.coerceIn(0f, 1f) * mx).toInt()
        if (target > 0) scrollState.scrollTo(target)
        didRestore = true
    }
    LaunchedEffect(scrollState, primary, secondary) {
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .collect { (v, mx) ->
                if (didRestore && mx > 0) {
                    onScrollFractionChange((v.toFloat() / mx).coerceIn(0f, 1f))
                }
            }
    }
    var batchProgress by remember(chapterKey) {
        mutableStateOf<Triple<Int, Int, String>?>(null)
    }

    // 横向滑动切章：用 awaitEachGesture 自行判定方向，只有当横向位移占主导且超过阈值时
    // 才消费并切章；纵向为主时完全不干预，交给内层正文继续滚动。
    // 用 rememberUpdatedState 保存最新回调，避免 pointerInput 协程不重启时捕获到旧的章节下标。
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    val curOnPrev by rememberUpdatedState(onPrevChapter)
    val curOnNext by rememberUpdatedState(onNextChapter)
    val curCanPrev by rememberUpdatedState(canPrevChapter)
    val curCanNext by rememberUpdatedState(canNextChapter)
    val swipeModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragX = 0f
            var dragY = 0f
            var isHorizontal = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUp()) break
                dragX += change.position.x - change.previousPosition.x
                dragY += change.position.y - change.previousPosition.y
                if (!isHorizontal &&
                    kotlin.math.abs(dragX) > swipeThresholdPx &&
                    kotlin.math.abs(dragX) > kotlin.math.abs(dragY)
                ) {
                    isHorizontal = true
                }
                if (isHorizontal) change.consume()
            }
            if (isHorizontal) {
                if (dragX <= -swipeThresholdPx && curCanNext) curOnNext()
                else if (dragX >= swipeThresholdPx && curCanPrev) curOnPrev()
            }
        }
    }

    BoxWithConstraints(modifier = modifier.then(swipeModifier)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showFailureBanner) {
                TranslationFailureBanner(
                    message = translationFailure!!,
                    onRetry = onRetryTranslation,
                )
            }
            when {
                primary != null && secondary != null -> {
                    val inline = LocalInlineImageHandlers.current
                    val segments = remember(primary, inline.enabled) {
                        if (inline.enabled) parseChapterSegments(primary)
                        else listOf(ChapterSegment.Text(stripInlineImageMarkers(primary) ?: primary))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(scrollState),
                    ) {
                        for (seg in segments) {
                            when (seg) {
                                is ChapterSegment.Text -> SpannedText(
                                    spanned = seg.spanned,
                                    fontScale = fontScale,
                                )
                                is ChapterSegment.ImageMarker -> InlineImageMarkerCard(
                                    prompt = seg.prompt,
                                    handlers = inline,
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Text(
                            "原文",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        SpannedText(spanned = secondary, fontScale = fontScale * 0.92f, dimmed = true)
                    }
                }
                primary != null -> {
                    val inline = LocalInlineImageHandlers.current
                    val segments = remember(primary, inline.enabled) {
                        if (inline.enabled) parseChapterSegments(primary)
                        else listOf(ChapterSegment.Text(stripInlineImageMarkers(primary) ?: primary))
                    }
                    if (segments.size == 1 && segments[0] is ChapterSegment.Text) {
                        SpannedTextScroll(
                            spanned = (segments[0] as ChapterSegment.Text).spanned,
                            fontScale = fontScale,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            scrollState = scrollState,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .verticalScroll(scrollState),
                        ) {
                            for (seg in segments) {
                                when (seg) {
                                    is ChapterSegment.Text -> SpannedText(
                                        spanned = seg.spanned,
                                        fontScale = fontScale,
                                    )
                                    is ChapterSegment.ImageMarker -> InlineImageMarkerCard(
                                        prompt = seg.prompt,
                                        handlers = inline,
                                    )
                                }
                            }
                        }
                    }
                }
                chapter != null && (isLoading || isTranslating) -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                    androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 12.dp))
                    Text(
                        if (isTranslating) translationProgress ?: "翻译中…" else "正在加载本章…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                chapter != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("本章尚未下载", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 12.dp))
                    Button(onClick = onLoad) { Text("点此加载") }
                }
                else -> Text(
                    "（无内容）",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
        }

        // 正文保持连续纵向滚动，不再叠加任何手势 overlay（避免吞掉滚动）。
        // 工具栏切换交由顶部/底部栏自身按钮处理。

        // "一键生成全部插图" 悬浮按钮
        if (allInlinePrompts.isNotEmpty() && batchProgress == null) {
            androidx.compose.material3.FloatingActionButton(
                onClick = { showBatchConfirm = true },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp),
            ) {
                Text("🪄 ${allInlinePrompts.size}")
            }
        }

        // 三击呼出的批量生成确认对话框
        if (showBatchConfirm) {
            val cached = remember(allInlinePrompts) {
                // 仅做粗略提示，不在这里同步查文件
                allInlinePrompts.size
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showBatchConfirm = false },
                title = { Text("一键生成本章插图") },
                text = {
                    Text(
                        "本章共解析到 $cached 个 [IMG] 标记。" +
                            "点击下方「开始生成」会按顺序生成（已缓存的会跳过）。" +
                            "生成可能较慢且消耗 API 额度，确定继续？",
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showBatchConfirm = false
                        batchProgress = Triple(0, allInlinePrompts.size, "")
                        inlineTop.generateAll(
                            allInlinePrompts,
                            { c, t, p -> batchProgress = Triple(c, t, p) },
                            { _, _ -> batchProgress = null },
                        )
                    }) { Text("开始生成") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showBatchConfirm = false }) {
                        Text("取消")
                    }
                },
            )
        }

        // 进度浮层
        batchProgress?.let { (cur, total, p) ->
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.inverseSurface,
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "生成插图 $cur / $total",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (p.isNotBlank()) {
                        Text(
                            p,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationFailureBanner(message: String, onRetry: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "翻译失败",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun TocDialog(
    entries: List<TocEntry>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val qLower = q.lowercase()
    val jumpIndex = q.toIntOrNull()?.let { it - 1 }?.takeIf { it in entries.indices }
    val filtered: List<Pair<Int, TocEntry>> = remember(q, entries) {
        if (q.isEmpty()) entries.mapIndexed { i, e -> i to e }
        else entries.mapIndexedNotNull { i, e ->
            val hit = (jumpIndex != null && i == jumpIndex) ||
                e.title.lowercase().contains(qLower) ||
                (e.groupTitle?.lowercase()?.contains(qLower) == true) ||
                (i + 1).toString() == q
            if (hit) i to e else null
        }
    }
    // 启用分组折叠（任意条目带 groupTitle）
    val hasGroups = entries.any { !it.groupTitle.isNullOrBlank() }
    // 按 groupTitle 把过滤后的条目分组（保留原索引顺序）。key 用标题本身（稳定，
    // 不随搜索过滤而错位）；无分组时用单一哨兵 key。
    data class Group(val key: String, val title: String?, val items: List<Pair<Int, TocEntry>>)
    val noGroupKey = "__flat__"
    val groups: List<Group> = remember(filtered, hasGroups) {
        if (!hasGroups) listOf(Group(noGroupKey, null, filtered))
        else {
            val out = mutableListOf<Group>()
            var curTitle: String? = Any().toString() // sentinel
            var buf = mutableListOf<Pair<Int, TocEntry>>()
            fun flush() {
                if (buf.isNotEmpty()) {
                    val t = curTitle as? String
                    out += Group(t ?: "${noGroupKey}:${out.size}", t, buf)
                }
            }
            for (pair in filtered) {
                val g = pair.second.groupTitle?.takeIf { it.isNotBlank() }
                if (g != curTitle) {
                    flush()
                    curTitle = g
                    buf = mutableListOf()
                }
                buf += pair
            }
            flush()
            out
        }
    }

    // 当前章节所在分组的 key（用来默认展开它）
    val currentGroupKey = remember(groups, current) {
        groups.firstOrNull { g -> g.items.any { it.first == current } }?.key
    }
    // 每个分组的展开态：按分组 key 记忆（稳定）。搜索时全展开。
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun isOpen(g: Group): Boolean =
        if (q.isNotEmpty()) true else expanded[g.key] ?: (g.key == currentGroupKey)

    val listState = rememberLazyListState()
    val tocScope = rememberCoroutineScope()

    // 计算某章在 LazyColumn 中的 item 下标（考虑分组头 + 已展开分组的条目）。
    fun itemIndexOfChapter(target: Int): Int? {
        var idx = 0
        for (g in groups) {
            if (g.title != null) idx++ // 分组头占一个 item
            if (isOpen(g)) {
                for ((chIdx, _) in g.items) {
                    if (chIdx == target) return idx
                    idx++
                }
            } else {
                // 收起的分组里若含目标章，定位到分组头
                if (g.items.any { it.first == target }) {
                    return (idx - if (g.title != null) 1 else 0).coerceAtLeast(0)
                }
            }
        }
        return null
    }

    fun jumpToCurrent() {
        query = ""
        currentGroupKey?.let { expanded[it] = true }
        tocScope.launch {
            // 等下一帧 expanded 生效后再滚动
            kotlinx.coroutines.delay(16)
            itemIndexOfChapter(current)?.let { listState.scrollToItem(it.coerceAtLeast(0)) }
        }
    }

    // 打开目录时自动展开当前组并滚动到当前章
    LaunchedEffect(Unit) {
        currentGroupKey?.let { expanded[it] = true }
        kotlinx.coroutines.delay(32)
        if (current in entries.indices) {
            itemIndexOfChapter(current)?.let { listState.scrollToItem(it.coerceAtLeast(0)) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                if (current in entries.indices) {
                    TextButton(onClick = { jumpToCurrent() }) { Text("回到当前") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        title = { Text("目录（${entries.size} 章）") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索标题或输入章节号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                if (filtered.isEmpty()) {
                    Text(
                        "没有匹配的章节",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                    ) {
                        groups.forEachIndexed { gi, group ->
                            val open = isOpen(group)
                            if (group.title != null) {
                                item(key = "g:${group.key}") {
                                    val containsCurrent = group.items.any { it.first == current }
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expanded[group.key] = !(expanded[group.key] ?: open)
                                            }
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (open) Icons.Filled.ExpandLess
                                            else Icons.Filled.ExpandMore,
                                            contentDescription = if (open) "收起" else "展开",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                                        Text(
                                            group.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${group.items.size} 话",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (containsCurrent && !open) {
                                            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                                            Text(
                                                "当前",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                            if (open) {
                                items(group.items, key = { "ch:${it.first}" }) { (index, entry) ->
                                    val isCurrent = index == current
                                    val bg = if (isCurrent)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(bg)
                                            .clickable { onSelect(index) }
                                            .padding(
                                                start = if (group.title != null) 28.dp else 12.dp,
                                                end = 12.dp,
                                                top = 10.dp,
                                                bottom = 10.dp,
                                            ),
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(40.dp),
                                        )
                                        Text(
                                            entry.title,
                                            style = if (isCurrent) MaterialTheme.typography.titleSmall
                                            else MaterialTheme.typography.bodyMedium,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (isCurrent) {
                                            Text(
                                                "当前",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SpannedTextScroll(
    spanned: Spanned,
    fontScale: Float,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val baseTextColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val typeface = LocalReaderFontFamily.current.toTypeface()
    AndroidView(
        modifier = modifier.verticalScroll(scrollState),
        factory = { ctx ->
            TextView(ctx).apply {
                setLineSpacing(6f, 1.55f)
                setPadding(0, 0, 0, 0)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                // 首行缩进 2 字符 + 段间距
                val density = resources.displayMetrics.density
                setPaddingRelative(0, 0, 0, (24 * density).toInt())
            }
        },
        update = { view ->
            view.setTextColor(baseTextColor)
            view.setLinkTextColor(linkColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * fontScale)
            // 「阅读字体」设置必须落到正文上 —— TextView 不继承 Compose typography
            view.typeface = typeface
            view.text = applyParagraphStyle(spanned, view.textSize)
        },
    )
}

/** 不带滚动的 Spanned 渲染（嵌入到外层 Column 时用）。 */
@Composable
private fun SpannedText(
    spanned: Spanned,
    fontScale: Float,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val color =
        if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        else MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val typeface = LocalReaderFontFamily.current.toTypeface()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setLineSpacing(4f, 1.5f)
                setPadding(0, 0, 0, 0)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { view ->
            view.setTextColor(color)
            view.setLinkTextColor(linkColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
            view.typeface = typeface
            view.text = applyParagraphStyle(spanned, view.textSize)
        },
    )
}

/**
 * 给 Spanned 的每个段落加首行缩进（2em）和段后空白，模拟印刷书排版。
 * 这是按字符位置插入 LeadingMarginSpan 与 line-height span，不破坏原 Spanned 内容。
 */
private fun applyParagraphStyle(src: Spanned, textSizePx: Float): CharSequence {
    val ssb = android.text.SpannableStringBuilder(src)
    val indent = (textSizePx * 2).toInt() // 2em
    var lineStart = 0
    val len = ssb.length
    var i = 0
    while (i <= len) {
        if (i == len || ssb[i] == '\n') {
            if (i > lineStart) {
                ssb.setSpan(
                    android.text.style.LeadingMarginSpan.Standard(indent, 0),
                    lineStart, i,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                )
            }
            lineStart = i + 1
        }
        i++
    }
    return ssb
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
