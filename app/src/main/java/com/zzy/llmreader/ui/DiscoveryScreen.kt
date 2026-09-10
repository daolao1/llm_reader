package com.zzy.llmreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.zzy.llmreader.web.DiscoveryFilterSpec
import com.zzy.llmreader.web.DiscoveryItem
import com.zzy.llmreader.web.DiscoveryQuery
import com.zzy.llmreader.web.DiscoverySort
import com.zzy.llmreader.web.WebSource
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 图书馆式发现页：站点选择 → 顶部站点风格 chip 横滑（分类 / 排序）→ 封面网格 → 翻译开关。
 *
 * 设计目标：尽量像「番茄/起点/Webnovel」一类阅读 app：
 *  - 顶部：站点 logo + 搜索框（站点支持时）；
 *  - 第一条 sticky chip 行：排序（segmented-style，例如 全部时间 / 30 天 / 7 天）；
 *  - 第二条 sticky chip 行：当前主过滤维度（站点只有 1 个 filter 时直接平铺为分类 tabs；
 *    多于 1 个时把多余的塞到 ⚙ 抽屉）；
 *  - 主体：2 列封面网格（无封面时回退到带渐变占位图的卡片，露出标题+作者+评分）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    state: DiscoveryUiState,
    sites: List<WebSource>,
    onDismiss: () -> Unit,
    onSelectSite: (String) -> Unit,
    onUpdateQuery: (DiscoveryQuery.() -> DiscoveryQuery) -> Unit,
    onLoadMore: () -> Unit,
    onTranslateToggle: (Boolean) -> Unit,
    onRetranslateAll: () -> Unit,
    onOpenItem: (String) -> Unit,
    onBackToSitePicker: () -> Unit,
    onScrollChange: (index: Int, offset: Int) -> Unit = { _, _ -> },
    onShowDetail: (String) -> Unit = {},
    onDismissDetail: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            val site = sites.firstOrNull { it.id == state.siteId }
                            Text(
                                site?.displayName ?: "发现",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (state.siteId != null) onBackToSitePicker() else onDismiss()
                            }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            if (state.siteId != null) {
                                IconButton(
                                    onClick = onRetranslateAll,
                                    enabled = state.items.isNotEmpty(),
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "重新翻译当前页",
                                    )
                                }
                                TranslateToggle(
                                    on = state.translateListings,
                                    onChange = onTranslateToggle,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (state.siteId == null) {
                        SitePicker(sites = sites, onSelect = onSelectSite)
                    } else {
                        val site = sites.firstOrNull { it.id == state.siteId }
                        if (site == null) {
                            ErrorBlock("站点未找到")
                        } else {
                            DiscoveryListPane(
                                site = site,
                                state = state,
                                onUpdateQuery = onUpdateQuery,
                                onLoadMore = onLoadMore,
                                onOpenItem = onOpenItem,
                                onScrollChange = onScrollChange,
                                onShowDetail = onShowDetail,
                            )
                        }
                    }
                }
            }

            // 作品详情弹窗（含相似作品推荐）
            val detailItem = state.detailItemId?.let { id -> state.items.firstOrNull { it.id == id } }
            if (detailItem != null) {
                // 优先用站点原生分组的相似作品；没有就回退到本地相似度。
                val localSimilar = remember(detailItem.id, state.items) {
                    computeSimilarItems(detailItem, state.items, limit = 12)
                }
                val remoteGroups = state.detailSimilarGroups
                val groups: List<com.zzy.llmreader.web.SimilarGroup> = when {
                    !remoteGroups.isNullOrEmpty() -> remoteGroups
                    state.detailSimilarLoading -> emptyList()
                    localSimilar.isNotEmpty() -> listOf(
                        com.zzy.llmreader.web.SimilarGroup("相似作品（本地相似度）", localSimilar),
                    )
                    else -> emptyList()
                }
                val isLoading = state.detailSimilarLoading
                val isRemote = !remoteGroups.isNullOrEmpty()
                val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = onDismissDetail,
                    sheetState = detailSheetState,
                ) {
                    DetailSheet(
                        item = detailItem,
                        groups = groups,
                        isLoading = isLoading,
                        isRemote = isRemote,
                        showTranslated = state.translateListings,
                        onOpen = { onOpenItem(detailItem.id) },
                        onSelectSimilar = { id ->
                            // 站点原生推荐里的 id 可能不在当前列表里。这种情况下直接用 detailUrl 打开。
                            val inList = state.items.any { it.id == id }
                            if (inList) onShowDetail(id)
                            else {
                                val target = groups.asSequence()
                                    .flatMap { it.items.asSequence() }
                                    .firstOrNull { it.id == id }
                                if (target != null) onOpenItem(target.id)
                            }
                        },
                        onTagClick = { tag ->
                            onDismissDetail()
                            onUpdateQuery { withTag(tag) }
                        },
                    )
                }
            }
        }
    }

@Composable
private fun TranslateToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Icon(
            Icons.Filled.Translate,
            contentDescription = "标题/简介翻译",
            modifier = Modifier.size(18.dp),
            tint = if (on) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = on,
            onCheckedChange = onChange,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SitePicker(
    sites: List<WebSource>,
    onSelect: (String) -> Unit,
) {
    if (sites.isEmpty()) {
        EmptyBlock(
            title = "暂无可浏览站点",
            message = "各站点的发现页适配中，敬请期待。\n" +
                "你可以继续用顶栏链接图标粘贴 URL 直接读。",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "选择一个站点",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "支持图书馆式浏览：分类、排序、搜索（部分站点）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(sites, key = { it.id }) { site ->
            ElevatedCard(
                onClick = { onSelect(site.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SiteAvatar(site.displayName, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            site.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            site.sampleUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteAvatar(displayName: String, modifier: Modifier = Modifier) {
    val initial = displayName.trim().firstOrNull()?.uppercase() ?: "?"
    val palette = palette(displayName)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(listOf(palette.first, palette.second)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveryListPane(
    site: WebSource,
    state: DiscoveryUiState,
    onUpdateQuery: (DiscoveryQuery.() -> DiscoveryQuery) -> Unit,
    onLoadMore: () -> Unit,
    onOpenItem: (String) -> Unit,
    onScrollChange: (index: Int, offset: Int) -> Unit = { _, _ -> },
    onShowDetail: (String) -> Unit = {},
) {
    val caps = site.discovery!!.capabilities
    val gridState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.scrollIndex,
        initialFirstVisibleItemScrollOffset = state.scrollOffset,
    )
    var showFilters by remember(state.siteId) { mutableStateOf(false) }
    var keywordDraft by remember(state.siteId) { mutableStateOf(state.query.keyword) }

    // 所有 filter 都放到过滤抽屉里。
    val extraFilters = caps.filters

    LaunchedEffect(gridState, state.hasMore, state.loading) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                val total = gridState.layoutInfo.totalItemsCount
                if (state.hasMore && !state.loading && total > 0 && last >= total - 4) {
                    onLoadMore()
                }
            }
    }

    // 上报滚动位置以便离开后再回到 Discovery 时恢复
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (idx, off) -> onScrollChange(idx, off) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 搜索栏 ----（始终显示；不支持关键字的站点禁用并提示）
        SearchBar(
            value = keywordDraft,
            enabled = caps.supportsKeyword,
            placeholder = if (caps.supportsKeyword) "关键词，空格分隔" else "该站点暂不支持搜索",
            selectedTags = state.query.tags,
            onChange = { if (caps.supportsKeyword) keywordDraft = it },
            onSubmit = {
                if (caps.supportsKeyword) {
                    val parsed = parseSearchDraft(keywordDraft)
                    onUpdateQuery {
                        val mergedTags = (tags + parsed.tags)
                            .distinctBy { normalizeTag(it) }
                            .take(8)
                        val normalizedTags = mergedTags.map { normalizeTag(it) }.toSet()
                        val cleanKeyword = parsed.keyword
                            .split(Regex("[\\s　]+"))
                            .filter { it.isNotBlank() && normalizeTag(it) !in normalizedTags }
                            .joinToString(" ")
                        keywordDraft = cleanKeyword
                        copy(
                            keyword = cleanKeyword,
                            tags = mergedTags,
                        )
                    }
                }
            },
            onClear = {
                if (caps.supportsKeyword) {
                    keywordDraft = ""
                    onUpdateQuery { copy(keyword = "") }
                }
            },
            onRemoveTag = { tag ->
                onUpdateQuery { copy(tags = tags.filterNot { sameTag(it, tag) }) }
            },
            hasFilters = extraFilters.isNotEmpty(),
            onOpenFilters = { showFilters = true },
        )

        // ---- 排序行（纯文字 tab 风格，不用方块 chip）----
        if (caps.sorts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (sort in caps.sorts) {
                    val selected = state.query.sortId == sort.id ||
                        (state.query.sortId == null && sort == caps.sorts.first())
                    Text(
                        sort.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (!selected) onUpdateQuery { copy(sortId = sort.id) }
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        // ---- 主过滤维度 ----
        // 全部过滤项（包括分类）都靠右上角的 ⏬ 抽屉调出。

        ActiveExtraFilterChips(
            extraFilters = extraFilters,
            query = state.query,
            onClearFilter = { fid, vid ->
                onUpdateQuery {
                    val cur = filters[fid].orEmpty().filter { it != vid }
                    copy(filters = filters.toMutableMap().apply {
                        if (cur.isEmpty()) remove(fid) else put(fid, cur)
                    })
                }
            },
        )

        HorizontalDivider(thickness = 0.5.dp)

        // ---- 主体网格 ----
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading && state.items.isEmpty() -> CenteredSpinner()
                state.error != null && state.items.isEmpty() -> ErrorBlock(state.error)
                caps.keywordRequired && state.query.keyword.isBlank() && state.query.tags.isEmpty() -> EmptyBlock(
                    title = "请输入关键字",
                    message = "${site.displayName} 的搜索需要先填写关键字或 #tag。",
                )
                state.items.isEmpty() -> EmptyBlock(
                    title = "没有结果",
                    message = "换个分类、排序，或清掉关键字试试。",
                )
                else -> LazyColumn(
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        BookCard(
                            item = item,
                            showTranslated = state.translateListings,
                            translating = item.id in state.translating,
                            onClick = { onShowDetail(item.id) },
                            onTagClick = { tag ->
                                onUpdateQuery { withTag(tag).copy(page = caps.firstPage) }
                            },
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (state.loading) {
                        item { CenteredSpinner() }
                    } else if (!state.hasMore && state.items.isNotEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "— 已到底 —",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters && extraFilters.isNotEmpty()) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = sheetState,
        ) {
            FilterSheet(
                specs = extraFilters,
                query = state.query,
                onApply = { newQuery ->
                    showFilters = false
                    onUpdateQuery { newQuery }
                },
                onClose = { showFilters = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    value: String,
    enabled: Boolean,
    placeholder: String,
    selectedTags: List<String>,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onRemoveTag: (String) -> Unit,
    hasFilters: Boolean,
    onOpenFilters: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (enabled && value.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                placeholder = { Text(placeholder) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            FilledTonalButton(onClick = onSubmit, enabled = enabled) { Text("搜索") }
            if (hasFilters) {
                IconButton(onClick = onOpenFilters) {
                    Icon(Icons.Filled.FilterList, contentDescription = "过滤")
                }
            }
        }
        if (selectedTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (tag in selectedTags) {
                    AssistChip(
                        onClick = { onRemoveTag(tag) },
                        label = { Text("#${tag.trim().trimStart('#')}") },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(14.dp))
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            trailingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipScrollRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun ActiveExtraFilterChips(
    extraFilters: List<DiscoveryFilterSpec>,
    query: DiscoveryQuery,
    onClearFilter: (String, String) -> Unit,
) {
    val active = remember(query, extraFilters) {
        buildList {
            for (spec in extraFilters) {
                val selected = query.filters[spec.id].orEmpty()
                for (vid in selected) {
                    val opt = spec.options.firstOrNull { it.id == vid } ?: continue
                    add(Triple(spec.id, vid, "${spec.label}: ${opt.label}"))
                }
            }
        }
    }
    if (active.isEmpty()) return
    ChipScrollRow {
        for ((fid, vid, label) in active) {
            AssistChip(
                onClick = { onClearFilter(fid, vid) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                trailingIcon = {
                    Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(14.dp))
                },
            )
        }
    }
}

private fun DiscoveryQuery.withTag(tag: String): DiscoveryQuery {
    val clean = tag.trim().trimStart('#').trim()
    if (clean.isBlank()) return this
    val merged = (tags + clean)
        .distinctBy { normalizeTag(it) }
        .take(8)
    val normalizedTags = merged.map { normalizeTag(it) }.toSet()
    val cleanKeyword = keyword
        .split(Regex("[\\s　]+"))
        .filter { it.isNotBlank() && normalizeTag(it) !in normalizedTags }
        .joinToString(" ")
    return copy(keyword = cleanKeyword, tags = merged)
}

private fun sameTag(a: String, b: String): Boolean = normalizeTag(a) == normalizeTag(b)

private fun normalizeTag(tag: String): String =
    tag.trim().trimStart('#').lowercase(Locale.ROOT)

private data class ParsedSearchDraft(
    val keyword: String,
    val tags: List<String>,
)

private fun parseSearchDraft(input: String): ParsedSearchDraft {
    val keywords = mutableListOf<String>()
    val tags = mutableListOf<String>()
    input.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .forEach { token ->
            if (token.startsWith("#") && token.trimStart('#').isNotBlank()) {
                tags += token.trimStart('#')
            } else {
                keywords += token
            }
        }
    return ParsedSearchDraft(
        keyword = keywords.joinToString(" "),
        tags = tags,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    specs: List<DiscoveryFilterSpec>,
    query: DiscoveryQuery,
    onApply: (DiscoveryQuery) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember(query) { mutableStateOf(query) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("过滤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        for (spec in specs) {
            val selectedIds = draft.filters[spec.id].orEmpty().toSet()
            if (!spec.multiSelect) {
                // 单选 → 下拉
                var expanded by remember(spec.id) { mutableStateOf(false) }
                val current = spec.options.firstOrNull { it.id in selectedIds }
                    ?: spec.options.firstOrNull()
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = current?.label.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(spec.label) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        for (opt in spec.options) {
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = {
                                    expanded = false
                                    draft = draft.copy(
                                        filters = draft.filters.toMutableMap().apply {
                                            put(spec.id, listOf(opt.id))
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            } else {
                // 多选 → 折叠的下拉勾选
                var expanded by remember(spec.id) { mutableStateOf(false) }
                val labelText = spec.options
                    .filter { it.id in selectedIds }
                    .joinToString("、") { it.label }
                    .ifBlank { "全部" }
                Box {
                    OutlinedTextField(
                        value = labelText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(spec.label) },
                        trailingIcon = {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.FilterList, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        for (opt in spec.options) {
                            val on = opt.id in selectedIds
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.Checkbox(
                                            checked = on,
                                            onCheckedChange = null,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(opt.label)
                                    }
                                },
                                onClick = {
                                    val newSel = if (on) selectedIds - opt.id else selectedIds + opt.id
                                    draft = draft.copy(
                                        filters = draft.filters.toMutableMap().apply {
                                            if (newSel.isEmpty()) remove(spec.id)
                                            else put(spec.id, newSel.toList())
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { onApply(draft) }) { Text("应用") }
        }
    }
}

// ---- 卡片 / 占位 ----

@Composable
private fun BookCard(
    item: DiscoveryItem,
    showTranslated: Boolean,
    translating: Boolean,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val title = if (showTranslated)
                item.translatedTitle?.takeIf { it.isNotBlank() } ?: item.title
            else item.title
            val isChinese = remember(item.title, item.summary, item.tags) {
                isLikelyChinese(item.title) &&
                    (item.summary.isNullOrBlank() || isLikelyChinese(item.summary!!))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isChinese) {
                    ChineseBadge()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            val author = item.author?.takeIf { it.isNotBlank() }
            val metaParts = buildList {
                author?.let { add(it) }
                item.status?.takeIf { it.isNotBlank() }?.let { add(it) }
                item.chapterCount?.let { add("$it 章") }
                item.wordCount?.let { add(formatThousand(it) + " 字") }
                item.updatedAt?.let { add(formatDate(it)) }
                item.metrics["评分"]?.let { add("★ $it") }
                item.metrics["rating"]?.let { add("★ $it") }
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

            val summary = if (showTranslated)
                item.translatedSummary?.takeIf { it.isNotBlank() }
                    ?: item.summary?.takeIf { it.isNotBlank() }
            else item.summary?.takeIf { it.isNotBlank() }
            if (summary != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val translatedTags = item.translatedTags
                val displayTags = if (showTranslated && translatedTags != null) {
                    item.tags.mapIndexed { idx, raw ->
                        translatedTags.getOrNull(idx)
                            ?.takeIf { it.isNotBlank() } ?: raw
                    }
                } else item.tags
                InlineTagText(
                    tags = displayTags,
                    onTagClick = { idx ->
                        onTagClick(item.tags.getOrNull(idx) ?: displayTags[idx])
                    },
                )
            }

            if (translating) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("翻译中…", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * 行内 tag 列表：用 AnnotatedString 把每个 tag 渲染成可点击的 "#tag" 文字片段，
 * 不用 Chip 方块，更省空间也更像主流阅读 app 的样式。回调参数是被点击 tag 的索引。
 */
@Composable
private fun InlineTagText(tags: List<String>, onTagClick: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        tags.forEachIndexed { index, tag ->
            if (index > 0) append("  ")
            val start = length
            append("#")
            append(tag)
            addStyle(
                androidx.compose.ui.text.SpanStyle(color = primary),
                start,
                length,
            )
            addStringAnnotation(
                tag = "TAG",
                annotation = index.toString(),
                start = start,
                end = length,
            )
        }
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "TAG", start = offset, end = offset)
                .firstOrNull()
                ?.let { onTagClick(it.item.toIntOrNull() ?: 0) }
        },
    )
}

@Composable
private fun CoverImage(item: DiscoveryItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
    ) {
        val cover = item.coverUrl
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CoverPlaceholder(item)
        }
        // 评分等指标在右上角小徽章
        val rating = item.metrics["评分"] ?: item.metrics["rating"]
        if (!rating.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFB300),
                    modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text(rating, color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CoverPlaceholder(item: DiscoveryItem) {
    val palette = palette(item.title.ifBlank { item.id })
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(palette.first, palette.second))),
        contentAlignment = Alignment.Center,
    ) {
        // 标题大字 + 第一个标签做副标
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                item.title.take(28),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            val tag = item.tags.firstOrNull()
            if (!tag.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tag,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MetaRow(item: DiscoveryItem) {
    val parts = buildList {
        item.status?.takeIf { it.isNotBlank() }?.let { add(it) }
        item.chapterCount?.let { add("$it 章") }
        item.wordCount?.let { add(formatThousand(it) + " 字") }
        item.updatedAt?.let { add(formatDate(it)) }
    }
    if (parts.isEmpty() && item.tags.isEmpty()) return
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 通用块 ----

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyBlock(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "加载失败",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---- 工具 ----

private fun palette(seed: String): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF6A11CB) to Color(0xFF2575FC),
        Color(0xFFEE0979) to Color(0xFFFF6A00),
        Color(0xFF2C3E50) to Color(0xFF4CA1AF),
        Color(0xFF134E5E) to Color(0xFF71B280),
        Color(0xFFFF512F) to Color(0xFFDD2476),
        Color(0xFF1F4037) to Color(0xFF99F2C8),
        Color(0xFFCB356B) to Color(0xFFBD3F32),
        Color(0xFF373B44) to Color(0xFF4286F4),
        Color(0xFF8E2DE2) to Color(0xFF4A00E0),
        Color(0xFF1565C0) to Color(0xFFB06AB3),
    )
    val idx = abs(seed.hashCode()) % palettes.size
    return palettes[idx]
}

private fun formatThousand(n: Long): String {
    if (n < 1000) return n.toString()
    if (n < 1_000_000) return "%.1fk".format(n / 1000.0)
    return "%.1fM".format(n / 1_000_000.0)
}

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun formatDate(ms: Long): String = runCatching { DATE_FMT.format(Date(ms)) }.getOrDefault("")

/**
 * 文本是否更像中文：包含 ≥2 个 CJK 表意文字，且不含日文假名或韩文音节。
 * 用于 Discovery 卡片上加 "中文" 标签，避免给已是中文的书做无意义翻译。
 */
private fun isLikelyChinese(text: String): Boolean {
    if (text.isBlank()) return false
    var cjk = 0
    for (ch in text) {
        val c = ch.code
        if ((c in 0x3040..0x30FF) || (c in 0xFF66..0xFF9D)) return false // 假名
        if (c in 0xAC00..0xD7A3) return false // 韩文
        if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF) cjk++
    }
    return cjk >= 2
}

@Composable
private fun ChineseBadge() {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            "中文",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

// ---- 作品详情 + 相似作品推荐 ----

/**
 * 基于已加载列表的本地相似度排序：
 * - 优先 tag 重叠数（Jaccard 思路，分母用 max(curTags, otherTags) 防止"高 tag 总数 → 容易匹配"偏置）；
 * - 其次同作者加 0.1 加权；
 * - 完全没有重叠的不返回，避免噪音；
 * - 始终排除自身。
 */
private fun computeSimilarItems(
    cur: DiscoveryItem,
    all: List<DiscoveryItem>,
    limit: Int,
): List<DiscoveryItem> {
    val curTags = cur.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (all.size <= 1) return emptyList()
    return all.asSequence()
        .filter { it.id != cur.id }
        .map { other ->
            val otherTags = other.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val inter = if (curTags.isEmpty() || otherTags.isEmpty()) 0
                else (curTags intersect otherTags).size
            val denom = maxOf(curTags.size, otherTags.size).coerceAtLeast(1)
            val tagScore = inter.toFloat() / denom
            val authorBonus = if (
                !cur.author.isNullOrBlank() &&
                cur.author.equals(other.author, ignoreCase = true)
            ) 0.1f else 0f
            other to (tagScore + authorBonus)
        }
        .filter { it.second > 0f }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(limit)
        .toList()
}

@Composable
private fun DetailSheet(
    item: DiscoveryItem,
    groups: List<com.zzy.llmreader.web.SimilarGroup>,
    isLoading: Boolean,
    isRemote: Boolean,
    showTranslated: Boolean,
    onOpen: () -> Unit,
    onSelectSimilar: (String) -> Unit,
    onTagClick: (String) -> Unit,
) {
    val title = if (showTranslated)
        item.translatedTitle?.takeIf { it.isNotBlank() } ?: item.title
    else item.title
    val summary = if (showTranslated)
        item.translatedSummary?.takeIf { it.isNotBlank() }
            ?: item.summary?.takeIf { it.isNotBlank() }
    else item.summary?.takeIf { it.isNotBlank() }
    val translatedTags = item.translatedTags
    val displayTags = if (showTranslated && translatedTags != null) {
        item.tags.mapIndexed { idx, raw ->
            translatedTags.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: raw
        }
    } else item.tags

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isChinese = isLikelyChinese(item.title) &&
                (item.summary.isNullOrBlank() || isLikelyChinese(item.summary!!))
            if (isChinese) {
                ChineseBadge()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        val metaParts = buildList {
            item.author?.takeIf { it.isNotBlank() }?.let { add(it) }
            item.status?.takeIf { it.isNotBlank() }?.let { add(it) }
            item.chapterCount?.let { add("$it 章") }
            item.wordCount?.let { add(formatThousand(it) + " 字") }
            item.updatedAt?.let { add(formatDate(it)) }
            item.metrics["评分"]?.let { add("★ $it") }
            item.metrics["rating"]?.let { add("★ $it") }
        }
        if (metaParts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                metaParts.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开始阅读")
        }

        if (summary != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "简介",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }

        if (displayTags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "标签",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            InlineTagText(
                tags = displayTags,
                onTagClick = { idx ->
                    onTagClick(item.tags.getOrNull(idx) ?: displayTags[idx])
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        if (isLoading && groups.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在向站点请求相似作品…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (groups.isEmpty()) {
            Text(
                "相似作品",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "暂无可推荐的相似作品（标签信息太少或当前页结果不足）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            groups.forEachIndexed { gi, group ->
                if (gi > 0) {
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isRemote) "站点推荐" else "本地相似度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                for (sim in group.items) {
                    SimilarRow(
                        item = sim,
                        showTranslated = showTranslated,
                        onClick = { onSelectSimilar(sim.id) },
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SimilarRow(
    item: DiscoveryItem,
    showTranslated: Boolean,
    onClick: () -> Unit,
) {
    val title = if (showTranslated)
        item.translatedTitle?.takeIf { it.isNotBlank() } ?: item.title
    else item.title
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isChinese = isLikelyChinese(item.title) &&
                (item.summary.isNullOrBlank() || isLikelyChinese(item.summary!!))
            if (isChinese) {
                ChineseBadge()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val metaParts = buildList {
            item.author?.takeIf { it.isNotBlank() }?.let { add(it) }
            item.chapterCount?.let { add("$it 章") }
            item.wordCount?.let { add(formatThousand(it) + " 字") }
        }
        if (metaParts.isNotEmpty()) {
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
