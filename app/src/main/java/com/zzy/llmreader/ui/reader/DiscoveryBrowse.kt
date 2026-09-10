package com.zzy.llmreader.ui.reader

import androidx.lifecycle.viewModelScope
import com.zzy.llmreader.ui.ReaderViewModel
import com.zzy.llmreader.ui.ReaderUiState
import com.zzy.llmreader.web.CloudflareBlockedException
import com.zzy.llmreader.web.DiscoveryItem
import com.zzy.llmreader.web.DiscoveryPage
import com.zzy.llmreader.web.DiscoveryQuery
import com.zzy.llmreader.web.WebSource
import com.zzy.llmreader.web.toBookContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 发现页浏览：搜索 / 过滤 / 翻页 / 打开作品。
 *
 * 站点能力差异（是否支持关键字、有哪些过滤维度）由各 WebSource 的
 * WebDiscovery 声明，这里只负责把 UI 的查询转成站点请求、处理本地标签
 * 过滤、维护分页状态。
 */
    internal fun ReaderViewModel.runDiscovery(resetPage: Boolean) {
        val cur = _state.value.discovery
        val site = cur.siteId?.let { webRegistry.findById(it) } ?: return
        val discovery = site.discovery ?: return
        val caps = discovery.capabilities
        val query = if (resetPage) {
            cur.query.copy(page = caps.firstPage)
        } else {
            cur.query.copy(page = cur.query.page + 1)
        }
        // 关键字必填的站点（如 Pixiv）：没有 keyword 也没有 tag 时才给空态；
        // 只有 tag 时用第一个 tag 临时作为站点 keyword，再做本地 tag 过滤。
        if (caps.keywordRequired && query.keyword.trim().isEmpty() && query.tags.none { it.isNotBlank() }) {
            _state.update {
                it.copy(
                    discovery = it.discovery.copy(
                        query = query,
                        loading = false,
                        error = null,
                        items = emptyList(),
                        hasMore = false,
                        totalCount = null,
                    ),
                )
            }
            return
        }
        _state.update {
            it.copy(
                discovery = it.discovery.copy(
                    query = query,
                    loading = true,
                    error = null,
                    items = if (resetPage) emptyList() else it.discovery.items,
                ),
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                if (query.tags.any { it.isNotBlank() }) {
                    val fetchQuery = query.withKeywordsIncludingTags()
                    discoverWithLocalTagFill(discovery, fetchQuery, query)
                } else {
                    discovery.discover(query)
                }
            }
            result.fold(
                onSuccess = { page ->
                    _state.update {
                        val merged = if (resetPage) page.items else it.discovery.items + page.items
                        it.copy(
                            discovery = it.discovery.copy(
                                query = page.query,
                                items = merged,
                                hasMore = page.hasMore,
                                totalCount = page.totalCount,
                                loading = false,
                            ),
                        )
                    }
                    if (_state.value.discovery.translateListings) {
                        translateDiscoveryBatch(page.items)
                    }
                },
                onFailure = { t ->
                    if (t is CloudflareBlockedException) {
                        pendingDiscoveryReload = true
                        _state.update {
                            it.copy(
                                discovery = it.discovery.copy(
                                    loading = false,
                                    error = "需要重新通过 Cloudflare 验证。",
                                ),
                                error = "需要重新通过 Cloudflare 验证。",
                                pendingCloudflareUrl = t.targetUrl,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                discovery = it.discovery.copy(
                                    loading = false,
                                    error = t.message ?: t.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    internal suspend fun ReaderViewModel.discoverWithLocalTagFill(
        discovery: com.zzy.llmreader.web.WebDiscovery,
        fetchQuery: com.zzy.llmreader.web.DiscoveryQuery,
        displayQuery: com.zzy.llmreader.web.DiscoveryQuery,
    ): com.zzy.llmreader.web.DiscoveryPage {
        val targetSize = displayQuery.pageSize.coerceAtLeast(1)
        val matched = mutableListOf<com.zzy.llmreader.web.DiscoveryItem>()
        var nextQuery = fetchQuery
        var lastQuery = fetchQuery
        var hasMore = true
        var totalCount: Long? = null
        var guard = 0

        while (matched.size < targetSize && hasMore && guard < 20) {
            guard += 1
            val page = discovery.discover(nextQuery)
            lastQuery = page.query
            totalCount = page.totalCount
            matched += page.items.filter { it.matchesAllLocalTags(displayQuery.tags) }
            hasMore = page.hasMore
            if (matched.size < targetSize && hasMore) {
                nextQuery = page.query.copy(page = page.query.page + 1)
            }
        }

        return com.zzy.llmreader.web.DiscoveryPage(
            query = displayQuery.copy(page = lastQuery.page),
            items = matched,
            hasMore = hasMore,
            totalCount = totalCount,
        )
    }

    internal fun com.zzy.llmreader.web.DiscoveryQuery.withKeywordsIncludingTags():
        com.zzy.llmreader.web.DiscoveryQuery {
        val terms = (keyword.split(Regex("[\\s　]+")) + tags)
            .map { it.trim().trimStart('#').trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        return copy(keyword = terms.joinToString(" "))
    }

    internal fun com.zzy.llmreader.web.DiscoveryItem.matchesAllLocalTags(tags: List<String>): Boolean {
        val wanted = tags.mapNotNull { it.normalizedDiscoveryTag().takeIf(String::isNotBlank) }
        if (wanted.isEmpty()) return true
        val haystack = this.tags.map { it.normalizedDiscoveryTag() }
        return wanted.all { needle ->
            haystack.any { candidate -> candidate == needle || candidate.contains(needle) }
        }
    }

    internal fun String.normalizedDiscoveryTag(): String =
        trim().trimStart('#').trim().lowercase(java.util.Locale.ROOT)

    /** 选中某条 item → 走正常的"网页书"打开流程。 */
    internal fun ReaderViewModel.openDiscoveryItem(itemId: String) {
        val cur = _state.value.discovery
        val site = cur.siteId?.let { webRegistry.findById(it) } ?: return
        val discovery = site.discovery ?: return
        // 列表里没有时回退到详情弹窗当前的相似作品分组（站点原生推荐结果）。
        val item = cur.items.firstOrNull { it.id == itemId }
            ?: cur.detailSimilarGroups?.asSequence()
                ?.flatMap { it.items.asSequence() }
                ?.firstOrNull { it.id == itemId }
            ?: return
        // 关闭详情弹窗（如果是从详情按"开始阅读"进来的）
        _state.update {
            it.copy(
                discovery = it.discovery.copy(
                    detailItemId = null,
                    detailSimilarGroups = null,
                    detailSimilarLoading = false,
                ),
            )
        }
        viewModelScope.launch {
            val resolved = runCatching { discovery.resolveDetailUrl(item) }.getOrNull()
                ?: item.detailUrl
            openWebBook(resolved)
        }
    }
