package com.zzy.llmreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zzy.llmreader.web.SimilarGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 作品详情独立页：给"网络小说 + 本地导入"统一展示
 *  - 头部：封面/占位图 + 标题、作者、状态、字数、最后更新、评分等指标
 *  - 简介 + 标签
 *  - 操作：开始阅读、(网页) 打开站点链接
 *  - 目录（章节列表，懒展开）
 *  - 相似作品 / 作者同作品（站点原生 → 否则回退）
 *  - 书籍信息（来源、URL、章节数等）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    detail: BookDetailUiState,
    onBack: () -> Unit,
    onStartReading: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onOpenSimilar: (String) -> Unit,
    onOpenSiteUrl: (String) -> Unit = {},
    onTagClick: (String) -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            detail.translatedTitle?.takeIf { it.isNotBlank() && detail.translateListings }
                                ?: detail.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            DetailBody(
                detail = detail,
                padding = padding,
                onStartReading = onStartReading,
                onOpenChapter = onOpenChapter,
                onOpenSimilar = onOpenSimilar,
                onOpenSiteUrl = onOpenSiteUrl,
                onTagClick = onTagClick,
            )
        }
    }
}

@Composable
private fun DetailBody(
    detail: BookDetailUiState,
    padding: PaddingValues,
    onStartReading: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onOpenSimilar: (String) -> Unit,
    onOpenSiteUrl: (String) -> Unit,
    onTagClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { HeroBlock(detail) }
        item {
            ActionsBlock(
                detail = detail,
                onStartReading = onStartReading,
                onOpenSiteUrl = onOpenSiteUrl,
            )
        }
        item { SummaryBlock(detail, onTagClick) }
        item { TocBlock(detail = detail, onOpenChapter = onOpenChapter) }
        item {
            SimilarBlock(
                detail = detail,
                onOpenSimilar = onOpenSimilar,
            )
        }
        item { InfoBlock(detail) }
    }
}

// ----------- 头部 -----------

@Composable
private fun HeroBlock(detail: BookDetailUiState) {
    val title = if (detail.translateListings)
        detail.translatedTitle?.takeIf { it.isNotBlank() } ?: detail.title
    else detail.title
    val palette = palette(title.ifBlank { detail.key })

    // 整块带渐变背景，营造沉浸式头图。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        palette.first.copy(alpha = 0.32f),
                        palette.second.copy(alpha = 0.12f),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 封面（更大、阴影更柔和）
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.size(width = 108.dp, height = 162.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val cover = detail.coverUrl
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CoverFallback(seed = title.ifBlank { detail.key }, label = title)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 来源 pill + 中文徽章
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourcePill(
                        label = if (detail.isWeb) detail.sourceName ?: "网页" else "本地文件",
                        primary = detail.isWeb,
                    )
                    if (isLikelyChineseLite(detail.title)) {
                        Spacer(Modifier.width(6.dp))
                        SourcePill(label = "中文", primary = false, accent = true)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val author = detail.author?.takeIf { it.isNotBlank() }
                if (author != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 评分单独高亮
                val rating = detail.metrics["评分"] ?: detail.metrics["rating"]
                if (!rating.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            rating,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    // 第二行：统计 chip 排（章节数 / 字数 / 状态 / 更新）
    StatsRow(detail)
}

@Composable
private fun SourcePill(label: String, primary: Boolean, accent: Boolean = false) {
    val bg = when {
        accent -> MaterialTheme.colorScheme.tertiaryContainer
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        accent -> MaterialTheme.colorScheme.onTertiaryContainer
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun StatsRow(detail: BookDetailUiState) {
    data class Stat(val icon: androidx.compose.ui.graphics.vector.ImageVector, val text: String)
    val stats = buildList {
        detail.chapterCount?.let { add(Stat(Icons.Filled.MenuBook, "$it 章")) }
        detail.wordCount?.let { add(Stat(Icons.Filled.AutoStories, formatThousandShort(it) + " 字")) }
        detail.status?.takeIf { it.isNotBlank() }?.let { add(Stat(Icons.Filled.Bookmark, it)) }
        detail.updatedAt?.let { add(Stat(Icons.Filled.CalendarToday, formatDateShort(it))) }
    }
    if (stats.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (s in stats) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        s.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s.text,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionsBlock(
    detail: BookDetailUiState,
    onStartReading: () -> Unit,
    onOpenSiteUrl: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onStartReading,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始阅读", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (detail.isWeb && !detail.detailUrl.isNullOrBlank()) {
            OutlinedButton(
                onClick = { onOpenSiteUrl(detail.detailUrl) },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("站点", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SummaryBlock(detail: BookDetailUiState, onTagClick: (String) -> Unit) {
    val summary = if (detail.translateListings)
        detail.translatedSummary?.takeIf { it.isNotBlank() }
            ?: detail.summary?.takeIf { it.isNotBlank() }
    else detail.summary?.takeIf { it.isNotBlank() }
    val displayTags = if (detail.translateListings && detail.translatedTags != null) {
        detail.tags.mapIndexed { idx, raw ->
            detail.translatedTags.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: raw
        }
    } else detail.tags

    if (summary == null && displayTags.isEmpty()) return

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (summary != null) {
            SectionHeader("简介")
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 可选中复制：简介（尤其是机器翻译过的）经常需要拿去搜原站
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            if (displayTags.isNotEmpty()) Spacer(Modifier.height(14.dp))
        }
        if (displayTags.isNotEmpty()) {
            SectionHeader("标签", icon = Icons.Filled.Tag)
            Spacer(Modifier.height(6.dp))
            TagChips(displayTags, onTagClick = { idx ->
                onTagClick(detail.tags.getOrNull(idx) ?: displayTags[idx])
            })
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧色条 accent
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<String>, onTagClick: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEachIndexed { idx, tag ->
            SuggestionChip(
                onClick = { onTagClick(idx) },
                label = {
                    Text(
                        "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                },
                shape = RoundedCornerShape(8.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = null,
            )
        }
    }
}

// ----------- 目录 -----------

@Composable
private fun TocBlock(
    detail: BookDetailUiState,
    onOpenChapter: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        SectionHeader(
            title = "目录",
            icon = Icons.Filled.MenuBook,
            trailing = {
                if (detail.tocLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                    )
                } else if (detail.chapters.isNotEmpty()) {
                    Text(
                        "${detail.chapters.size} 章",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        when {
            detail.tocLoading && detail.chapters.isEmpty() -> {
                Text(
                    "正在加载目录…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 11.dp),
                )
            }
            detail.tocError != null && detail.chapters.isEmpty() -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "目录加载失败：${detail.tocError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            detail.chapters.isEmpty() -> {
                Text(
                    "暂无目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 11.dp),
                )
            }
            else -> {
                var expanded by rememberSaveable(detail.key) { mutableStateOf(false) }
                val total = detail.chapters.size
                val previewLimit = 8
                val showAll = expanded || total <= previewLimit
                val visibleCount = if (showAll) total else previewLimit
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        for (i in 0 until visibleCount) {
                            ChapterRow(
                                index = i,
                                title = detail.chapters[i],
                                translatedTitle = detail.translatedChapters?.getOrNull(i),
                                showTranslated = detail.translateListings,
                                onClick = { onOpenChapter(i) },
                            )
                            if (i < visibleCount - 1) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 48.dp),
                                )
                            }
                        }
                    }
                }
                if (!showAll) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("展开全部 $total 章")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    translatedTitle: String?,
    showTranslated: Boolean,
    onClick: () -> Unit,
) {
    val display = if (showTranslated) translatedTitle?.takeIf { it.isNotBlank() } ?: title else title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号小圆徽
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            display,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ----------- 相似 / 作者作品 -----------

@Composable
private fun SimilarBlock(
    detail: BookDetailUiState,
    onOpenSimilar: (String) -> Unit,
) {
    if (!detail.isWeb) return
    // 站点没有可用的"作者同作品/相似"端点时，加载完成后什么都不显示。
    if (!detail.similarLoading && detail.similarGroups.isNullOrEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        when {
            detail.similarLoading && (detail.similarGroups.isNullOrEmpty()) -> {
                SectionHeader(title = "作者同作品")
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 11.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在向站点请求作者其它作品…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                detail.similarGroups!!.forEachIndexed { gi, group ->
                    if (gi > 0) Spacer(Modifier.height(14.dp))
                    SectionHeader(title = group.label)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            group.items.forEachIndexed { idx, sim ->
                                SimilarItemRow(
                                    item = sim,
                                    showTranslated = detail.translateListings,
                                    onClick = { onOpenSimilar(sim.id) },
                                )
                                if (idx < group.items.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
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
private fun SimilarItemRow(
    item: com.zzy.llmreader.web.DiscoveryItem,
    showTranslated: Boolean,
    onClick: () -> Unit,
) {
    val title = if (showTranslated)
        item.translatedTitle?.takeIf { it.isNotBlank() } ?: item.title
    else item.title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略封面（无封面时回退到首字母方块）
        Surface(
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.size(width = 36.dp, height = 48.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val cover = item.coverUrl
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CoverFallback(seed = item.id, label = item.title.take(2))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val metaParts = buildList {
                item.author?.takeIf { it.isNotBlank() }?.let { add(it) }
                item.chapterCount?.let { add("$it 章") }
                item.wordCount?.let { add(formatThousandShort(it) + " 字") }
            }
            if (metaParts.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ----------- 书籍信息 -----------

@Composable
private fun InfoBlock(detail: BookDetailUiState) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        SectionHeader(title = "书籍信息")
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                InfoLine("来源", if (detail.isWeb) detail.sourceName ?: "网页" else "本地文件")
                if (detail.isWeb && !detail.detailUrl.isNullOrBlank()) {
                    InfoLine("链接", detail.detailUrl)
                }
                if (!detail.isWeb) {
                    InfoLine("位置", detail.key)
                }
                detail.author?.takeIf { it.isNotBlank() }?.let { InfoLine("作者", it) }
                detail.status?.takeIf { it.isNotBlank() }?.let { InfoLine("状态", it) }
                detail.chapterCount?.let { InfoLine("章节数", "$it") }
                detail.wordCount?.let { InfoLine("字数", formatThousandShort(it)) }
                detail.updatedAt?.let { InfoLine("最后更新", formatDateShort(it)) }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

// ----------- 工具 -----------

@Composable
private fun CoverFallback(seed: String, label: String) {
    val palette = palette(seed)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(palette.first, palette.second))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.take(16),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun palette(seed: String): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF6A11CB) to Color(0xFF2575FC),
        Color(0xFFEE0979) to Color(0xFFFF6A00),
        Color(0xFF2C3E50) to Color(0xFF4CA1AF),
        Color(0xFF134E5E) to Color(0xFF71B280),
        Color(0xFFFF512F) to Color(0xFFDD2476),
        Color(0xFF1F4037) to Color(0xFF99F2C8),
        Color(0xFF373B44) to Color(0xFF4286F4),
        Color(0xFF8E2DE2) to Color(0xFF4A00E0),
    )
    val idx = abs(seed.hashCode()) % palettes.size
    return palettes[idx]
}

private fun formatThousandShort(n: Long): String {
    if (n < 1000) return n.toString()
    if (n < 1_000_000) return "%.1fk".format(n / 1000.0)
    return "%.1fM".format(n / 1_000_000.0)
}

private val DETAIL_DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun formatDateShort(ms: Long): String =
    runCatching { DETAIL_DATE_FMT.format(Date(ms)) }.getOrDefault("")

private fun isLikelyChineseLite(text: String): Boolean {
    if (text.isBlank()) return false
    var cjk = 0
    for (ch in text) {
        val c = ch.code
        if ((c in 0x3040..0x30FF) || (c in 0xFF66..0xFF9D)) return false
        if (c in 0xAC00..0xD7A3) return false
        if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF) cjk++
    }
    return cjk >= 2
}
