package com.zzy.llmreader.web

/**
 * 站点目录浏览（搜索 / 过滤 / 排序）的统一抽象。
 *
 * 这是一个"门面"，每个 [WebSource] 可选实现 [WebSource.discovery]：
 * - 不实现（保持 `null`）→ 该站点只支持"粘 URL 直接读"。
 * - 实现 → 可以在书库 UI 里以图书馆样式浏览。
 *
 * 不同站点的过滤项五花八门（标签 / 分类 / 语言 / 长度 / 状态…），
 * 这里用 [DiscoveryFilterSpec] 描述每个站点能给的"过滤维度"，
 * UI 据此动态渲染 chip / 下拉。
 */
interface WebDiscovery {
    /** 站点能力宣告：可用的排序、过滤项、分页方式等。 */
    val capabilities: DiscoveryCapabilities

    /**
     * 执行一次发现请求；返回当前页结果 + 是否还有下一页。
     * 实现需要尊重 [DiscoveryQuery] 中的页码 (`page`，1 起步)。
     */
    suspend fun discover(query: DiscoveryQuery): DiscoveryPage

    /**
     * 把站内 ID / 短 URL 还原为 [WebSource.canonicalize] 可消费的入口 URL。
     * 大多数情况下 `item.detailUrl` 已经是入口 URL，直接返回它即可，
     * 个别站（比如 RSS、JSON API 站）可能需要二次解析。
     */
    suspend fun resolveDetailUrl(item: DiscoveryItem): String = item.detailUrl

    /**
     * 站点原生的"相似/相关作品"接口。返回 `null` 表示站点没有此能力，
     * UI 会回退到本地基于 tag 重叠的相似度排序。
     * 抛异常视同 `null`（由调用方捕获）。
     */
    suspend fun fetchSimilar(item: DiscoveryItem, limit: Int = 12): List<DiscoveryItem>? = null

    /**
     * 站点原生的"分组相似作品"。返回 `null` 表示不实现该能力。
     *
     * 默认实现：先看 [DiscoveryItem.authorUrl]，没有就调 [resolveAuthorUrl] 现去作品详情页里挖一下；
     * 拿到 URL 后再经 [fetchAuthorWorks] 取作者其他作品，包成单组「作者同作品」。
     */
    suspend fun fetchSimilarGroups(item: DiscoveryItem, limit: Int = 12): List<SimilarGroup>? {
        val authorUrl = item.authorUrl?.takeIf { it.isNotBlank() }
            ?: runCatching { resolveAuthorUrl(item) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val raw = runCatching { fetchAuthorWorks(authorUrl, item.id, limit) }.getOrNull()
            ?: return null
        // 兜底过滤：当 item.id 不是站点真实作品 ID（例如缓存路径里我们传的是完整 URL），
        // 仅靠 fetchAuthorWorks 内部的 id 比对可能漏掉自身，再按 detailUrl 过一遍。
        val works = raw.filter { it.detailUrl != item.detailUrl }
        if (works.isEmpty()) return null
        return listOf(SimilarGroup("作者同作品", works))
    }

    /**
     * 没法从搜索结果里直接拿到 `authorUrl` 时（例如详情页是从缓存恢复出来的），
     * 实现可以现去 [DiscoveryItem.detailUrl] 抓一下页面拿到作者主页 URL。
     * 默认 null。
     */
    suspend fun resolveAuthorUrl(item: DiscoveryItem): String? = null

    /**
     * 拉作者主页上的其他作品。返回 `null` 表示该站点没实现 / 不支持。
     * - [authorUrl] 来自 [DiscoveryItem.authorUrl] 或 [resolveAuthorUrl]
     * - [excludeId] 当前作品 ID，应从结果中剔除
     */
    suspend fun fetchAuthorWorks(authorUrl: String, excludeId: String?, limit: Int = 12): List<DiscoveryItem>? = null
}

/** 相似作品分组（站点原生分块）。 */
data class SimilarGroup(
    /** UI 上显示的分组名，如「相似作品」「作者同作品」。 */
    val label: String,
    val items: List<DiscoveryItem>,
)

/** 站点支持的发现能力描述。 */
data class DiscoveryCapabilities(
    /** 支持自由文本搜索（通常映射到 `q=` / `keyword=`）。 */
    val supportsKeyword: Boolean = false,
    /** 关键字必填（关键字为空时不应自动发起搜索）。 */
    val keywordRequired: Boolean = false,
    /** 支持的排序项；空列表表示站点不支持自定义排序。 */
    val sorts: List<DiscoverySort> = emptyList(),
    /** 站点开放的过滤维度（标签 / 分类 / 状态 / 语言 / …）。 */
    val filters: List<DiscoveryFilterSpec> = emptyList(),
    /** 默认分页大小（仅供 UI 显示参考；真实分页由站点决定）。 */
    val defaultPageSize: Int = 20,
    /** 站点最早可访问的页码（1 或 0）。 */
    val firstPage: Int = 1,
)

/** 排序项，比如"按时间倒序" / "按热度"。 */
data class DiscoverySort(
    /** 内部稳定 ID，发到站点时用。 */
    val id: String,
    /** UI 显示名。 */
    val label: String,
)

/** 过滤维度（一组可选值）。 */
data class DiscoveryFilterSpec(
    /** 过滤维度 ID，比如 `tag` / `genre` / `status` / `language`。 */
    val id: String,
    /** UI 显示名，比如 "标签" / "分类" / "状态"。 */
    val label: String,
    /** 选项列表（id → 显示名）。 */
    val options: List<DiscoveryFilterOption>,
    /** 是否允许多选；默认单选。 */
    val multiSelect: Boolean = false,
    /** 多选时的语义：AND 表示"全部命中"，OR 表示"任一命中"。 */
    val multiSelectMode: MultiSelectMode = MultiSelectMode.OR,
) {
    enum class MultiSelectMode { AND, OR }
}

data class DiscoveryFilterOption(
    val id: String,
    val label: String,
    /** 可选：站点用的真实参数值（默认就是 [id]）。 */
    val value: String = id,
)

/** 一次发现请求。 */
data class DiscoveryQuery(
    val keyword: String = "",
    /** 本地 tag 过滤：命中结果项的 [DiscoveryItem.tags]。多个 tag 按 AND 处理。 */
    val tags: List<String> = emptyList(),
    /** 过滤选择：filterSpec.id → 选中的 option.id 列表。 */
    val filters: Map<String, List<String>> = emptyMap(),
    val sortId: String? = null,
    val page: Int = 1,
    val pageSize: Int = 20,
)

/** 单个发现结果项（书 / 系列 / 单篇）。 */
data class DiscoveryItem(
    /** 站内稳定 ID。 */
    val id: String,
    /** 入口 URL（用于 [WebSource.canonicalize] / [WebSource.fetchIndex]）。 */
    val detailUrl: String,
    val title: String,
    val author: String? = null,
    /**
     * 作者主页 URL（站点上能列出"作者所有作品"的页面）。
     * 为 null 表示该站点的搜索结果里没有暴露作者主页（或尚未实现）。
     * 详情页里的"作者同作品"块会用它去拉取，缺失则不显示。
     */
    val authorUrl: String? = null,
    val coverUrl: String? = null,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    /** 章节数；未知传 null。 */
    val chapterCount: Int? = null,
    /** "已完结" / "连载中" / "断更" 等站点原文标签。 */
    val status: String? = null,
    /** 字数；未知传 null。 */
    val wordCount: Long? = null,
    /** 站点显示的"最后更新时间"（毫秒；未知传 null）。 */
    val updatedAt: Long? = null,
    /** 站点显示的额外指标，比如"评分 / 收藏 / 阅读"，按 key→value 平铺。 */
    val metrics: Map<String, String> = emptyMap(),
    /** 翻译后的标题（懒填，由 ViewModel 翻译时写入）。 */
    val translatedTitle: String? = null,
    /** 翻译后的简介。 */
    val translatedSummary: String? = null,
    /** 翻译后的标签列表，与 [tags] 同长且一一对应；未翻译的位置可为空字符串。 */
    val translatedTags: List<String>? = null,
)

/** 一次发现请求的返回。 */
data class DiscoveryPage(
    val query: DiscoveryQuery,
    val items: List<DiscoveryItem>,
    val hasMore: Boolean,
    /** 站点报告的总条目数；未知传 null。 */
    val totalCount: Long? = null,
)
