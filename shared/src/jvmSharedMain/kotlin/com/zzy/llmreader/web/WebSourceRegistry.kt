package com.zzy.llmreader.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 简单的网页来源注册表：根据 URL 派发到对应解析器。 */
class WebSourceRegistry(
    pixivCookieProvider: () -> String? = { null },
    hamelnCookieProvider: () -> String? = { null },
    hamelnUserAgentProvider: () -> String? = { null },
    scribblehubCookieProvider: () -> String? = { null },
    scribblehubUserAgentProvider: () -> String? = { null },
    ao3CookieProvider: () -> String? = { null },
    ao3UserAgentProvider: () -> String? = { null },
    val sources: List<WebSource> = listOf(
        LiteroticaSource(),
        SyosetuSource(),
        Ao3Source(ao3CookieProvider, ao3UserAgentProvider),
        PixivSource(pixivCookieProvider),
        HamelnSource(hamelnCookieProvider, hamelnUserAgentProvider),
        KakuyomuSource(),
        ScribbleHubSource(scribblehubCookieProvider, scribblehubUserAgentProvider),
        RoyalRoadSource(),
    ),
) {
    fun find(input: String): WebSource? {
        val url = parseUrl(input) ?: return null
        return sources.firstOrNull { it.matches(url) }
    }

    fun findById(id: String): WebSource? = sources.firstOrNull { it.id == id }

    private fun parseUrl(input: String): HttpUrl? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return candidate.toHttpUrlOrNull()
    }
}
