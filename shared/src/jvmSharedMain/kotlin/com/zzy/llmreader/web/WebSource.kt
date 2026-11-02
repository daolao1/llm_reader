package com.zzy.llmreader.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 抓取进度回调；total 为 -1 表示总数未知。 */
typealias FetchProgress = (current: Int, total: Int, label: String) -> Unit

private const val CONTENT_BLOCKS = "p, h1, h2, h3, h4, blockquote, pre"

/**
 * 保留正文顶层块，丢弃已被某个选中祖先包含的块。
 * 例如 blockquote 内含 p 时，若两者都 outerHtml 后拼接，引用会被翻译两遍。
 */
internal fun topLevelContentBlocks(root: Element): List<Element> {
    val selected = root.select(CONTENT_BLOCKS)
    val selectedSet = selected.toSet()
    return selected.filter { element ->
        element.parents().none { it in selectedSet } &&
            (element.text().isNotBlank() || element.html().contains("<br", ignoreCase = true))
    }
}

/** 调用方可以识别它 → 弹出 CF 验证 Activity。 */
class CloudflareBlockedException(
    val targetUrl: String,
    val httpStatus: Int,
) : RuntimeException("Cloudflare 拦截（HTTP $httpStatus）：$targetUrl")

/** 统一的网页小说来源接口：每个站点实现一份解析+抓取。 */
interface WebSource {
    /** 站点稳定 ID，写到缓存里用于识别。 */
    val id: String

    /** 站点显示名，给 UI 用。 */
    val displayName: String

    /** 站点示例 URL，给 UI 提示用。 */
    val sampleUrl: String

    /**
     * 可选的"图书馆式"目录浏览能力（搜索 / 过滤 / 排序）。
     * 实现 → UI 里站点会出现在发现页 site picker 中；
     * `null`（默认）→ 该站点只支持粘 URL 直接读。
     */
    val discovery: WebDiscovery? get() = null

    /** 是否支持给定的 URL（host + 路径）。 */
    fun matches(url: HttpUrl): Boolean

    /** 把任意输入归一化成稳定的入口 URL（用作缓存 key）。 */
    fun canonicalize(input: String): String

    /**
     * 只抓取目录（章节列表），不下载每章正文。返回的 `chapters` 中
     * `html` 通常为 `null`，`chapterUrl` 用于后续按需抓单章。
     * 对于本身就只有一篇的"短编"，此处可以一并返回正文。
     */
    suspend fun fetchIndex(canonicalUrl: String, onProgress: FetchProgress = { _, _, _ -> }): CachedWebBook

    /**
     * 抓取单个章节正文。`chapter` 是从缓存中取出的占位（含 `chapterUrl`/`title`），
     * 实现需要返回填好 `html` 的副本。
     */
    suspend fun fetchChapter(canonicalUrl: String, chapterIndex: Int, chapter: CachedWebChapter): CachedWebChapter
}

/** 共享 HTTP/Jsoup 工具，避免每个 source 自己造轮子。 */
abstract class HtmlWebSource : WebSource {

    protected open val userAgent: String = DEFAULT_USER_AGENT

    /** 站点级别的额外请求头（比如成人确认 cookie）。 */
    protected open val extraHeaders: Map<String, String> = emptyMap()

    private val client: OkHttpClient by lazy {
        val browserSpec = browserConnectionSpec()
        OkHttpClient.Builder()
            .connectionSpecs(listOf(browserSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(safeDns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 抓页面并解析成 Jsoup Document。 */
    protected suspend fun fetchDocument(url: String): Document {
        val (body, finalUrl) = executeWithRetry(url, accept = DEFAULT_ACCEPT)
        return Jsoup.parse(body, finalUrl)
    }

    /** 抓原始响应体（站点 JSON / XML 接口用）。 */
    protected suspend fun fetchString(
        url: String,
        accept: String = DEFAULT_ACCEPT,
        extra: Map<String, String> = emptyMap(),
    ): String = executeWithRetry(url, accept = accept, extra = extra).first

    /**
     * 带重试的 GET，两个 fetch 的唯一实现。
     *
     * 合并前 `fetchDocument` 与 `fetchString` 各自抄了一份几乎相同的重试 /
     * Cloudflare 识别 / 连接池清理逻辑（约 80 行 ×2），改一处很容易漏另一处。
     *
     * @return Pair(响应体, 最终 URL)。最终 URL 会因为重定向而变化，
     *         Jsoup 解析相对链接时必须用它而不是原始 URL。
     */
    private suspend fun executeWithRetry(
        url: String,
        accept: String,
        extra: Map<String, String> = emptyMap(),
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url.toHttpUrl())
            .header("Accept", accept)
            .header("Accept-Language", "ja,en-US;q=0.7,en;q=0.3")
            .header("User-Agent", userAgent)
            .get()
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        extra.forEach { (k, v) -> builder.header(k, v) }
        val req = builder.build()

        // 对 5xx（尤其 Cloudflare 520/521/522/523/524/525/526/530）做有限次重试。
        var lastError: String? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val response = try {
                client.newCall(req).execute()
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
                // 关闭连接池中可能死掉的连接，下一次重试拿新连接
                runCatching { client.connectionPool.evictAll() }
                if (attempt < MAX_ATTEMPTS) {
                    delay(minOf(1500L * attempt, 6000L))
                    continue
                }
                throw t
            }
            response.use { r ->
                val body = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    require(body.isNotBlank()) { "网页内容为空" }
                    return@withContext body to r.request.url.toString()
                }
                lastError = "HTTP ${r.code}"
                val retriable = r.code in 500..599 || r.code == 408 || r.code == 429

                // ⚠️ 顺序很重要：**先给瞬时错误重试机会，再判断是不是 CF 挑战**。
                //
                // Cloudflare 的 520~527/530 属于边缘节点抖动（实测同一 URL 连续请求
                // 会出现 525 → 525 → 200），全都在 500..599 里、本该走重试。
                // 但旧实现把 CF 判断放在了重试判断**前面**，这些码一进来就抛
                // CloudflareBlockedException，8 次重试形同虚设；更糟的是
                // CF 前置站点（AO3 / ScribbleHub）连正常响应都带 cf-ray 头，
                // 于是任何 5xx 都会被弹去走一个根本过不了的验证页。
                if (retriable && attempt < MAX_ATTEMPTS) {
                    // 交给循环末尾的退避重试
                } else if (r.code in CLOUDFLARE_STATUS && looksLikeCloudflare(body, r.headers)) {
                    // 重试耗尽后仍然像是挑战页，才让 UI 引导用户走 WebView 拿 cookie
                    throw CloudflareBlockedException(url, r.code)
                } else {
                    error("网页请求失败：HTTP ${r.code}")
                }
            }
            // CF 边缘异常时把可能粘住的连接也踢掉
            runCatching { client.connectionPool.evictAll() }
            delay(minOf(1500L * attempt, 6000L))
        }
        error("网页请求失败（已重试 $MAX_ATTEMPTS 次）：$lastError")
    }

    /**
     * 判断响应是不是 Cloudflare 的**挑战/拦截页**。
     *
     * ⚠️ 只认真正的挑战信号。
     * 旧实现把「`Server: cloudflare`」和「存在任意 `cf-*` 响应头」也算作挑战 ——
     * 但 AO3 / ScribbleHub 这类 CF 前置站点的**正常 200 响应**同样带 `cf-ray`，
     * 于是任何 403/503 都会被误判成挑战，把用户送去过一个永远过不了的验证页。
     */
    private fun looksLikeCloudflare(body: String, headers: okhttp3.Headers): Boolean {
        if (body.contains("Just a moment", ignoreCase = true)) return true
        if (body.contains("challenges.cloudflare.com", ignoreCase = true)) return true
        if (body.contains("cf-challenge", ignoreCase = true)) return true
        // CF 在托管挑战/拦截时会显式加这个头
        if (headers["cf-mitigated"]?.isNotBlank() == true) return true
        return false
    }

    protected fun parseInputUrl(input: String): HttpUrl {
        val trimmed = input.trim()
        require(trimmed.isNotBlank()) { "请输入小说 URL" }
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching { candidate.toHttpUrl() }.getOrElse { error("URL 无效") }
    }

    companion object {
        private const val DEFAULT_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        private const val MAX_ATTEMPTS = 8

        /** 视为 Cloudflare 拦截、需要人工过验证的状态码。 */
        private val CLOUDFLARE_STATUS =
            setOf(403, 503, 520, 521, 522, 523, 524, 525, 526, 527, 530)

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; LLMReader) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 一些模拟器/路由器会污染 DNS（比如把 syosetu.com 解析成 Facebook 的 IP），
         * 这里用 Cloudflare DoH 做兜底；如果系统 DNS 解析结果可疑，就走 DoH。
         */
        private val dohClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
        private val cloudflareDoh: DnsOverHttps by lazy {
            DnsOverHttps.Builder()
                .client(dohClient)
                .url("https://1.1.1.1/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                )
                .build()
        }

        /**
         * 对已知会被劫持的域名直接走 DoH，避免系统 DNS 反复返回不同的错误 IP；
         * 其它域名仍走系统 DNS，命中 Facebook 段时再回退到 DoH。
         */
        private val DOH_FORCED_HOSTS = setOf(
            "syosetu.com",
            "novel18.syosetu.com",
            "ncode.syosetu.com",
            "yomou.syosetu.com",
            "mid.syosetu.com",
            "archiveofourown.org",
            "www.archiveofourown.org",
            "pixiv.net",
            "www.pixiv.net",
            "syosetu.org",
            "www.syosetu.org",
            "kakuyomu.jp",
            "www.kakuyomu.jp",
            "scribblehub.com",
            "www.scribblehub.com",
            "royalroad.com",
            "www.royalroad.com",
        )

        private fun shouldForceDoh(hostname: String): Boolean {
            val h = hostname.lowercase()
            return DOH_FORCED_HOSTS.any { h == it || h.endsWith(".$it") }
        }

        private val safeDns: Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (shouldForceDoh(hostname)) {
                    val doh = runCatching { cloudflareDoh.lookup(hostname) }.getOrElse {
                        com.zzy.llmreader.platform.PlatformLog.w("WebSourceDns", "forced DoH failed for $hostname", it)
                        return Dns.SYSTEM.lookup(hostname)
                    }
                    com.zzy.llmreader.platform.PlatformLog.i("WebSourceDns", "$hostname (forced-doh) -> ${doh.joinToString { it.hostAddress.orEmpty() }}")
                    return doh
                }
                val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
                val poisoned = system.any { it is java.net.Inet4Address && isPoisoned(it) }
                if (system.isNotEmpty() && !poisoned) {
                    com.zzy.llmreader.platform.PlatformLog.i("WebSourceDns", "$hostname (system) -> ${system.joinToString { it.hostAddress.orEmpty() }}")
                    return system
                }
                val doh = runCatching { cloudflareDoh.lookup(hostname) }
                    .getOrElse {
                        com.zzy.llmreader.platform.PlatformLog.w("WebSourceDns", "DoH lookup failed for $hostname", it)
                        if (system.isNotEmpty()) system else throw it
                    }
                com.zzy.llmreader.platform.PlatformLog.i("WebSourceDns", "$hostname (doh) -> ${doh.joinToString { it.hostAddress.orEmpty() }}")
                return doh
            }
        }

        /** 已知被污染的目标 IP 段（主要是 Facebook 的 ASN）。 */
        private val POISONED_CIDRS = listOf(
            // Facebook / Meta blocks
            "31.13.0.0/16",
            "66.220.144.0/20",
            "69.63.176.0/20",
            "69.171.224.0/19",
            "74.119.76.0/22",
            "102.132.96.0/20",
            "157.240.0.0/16",
            "173.252.64.0/18",
            "179.60.192.0/22",
            "185.60.216.0/22",
        ).map { parseCidr(it) }

        private fun parseCidr(cidr: String): Pair<Int, Int> {
            val (ip, prefix) = cidr.split('/')
            val parts = ip.split('.').map { it.toInt() }
            val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
            val mask = if (prefix.toInt() == 0) 0 else (-1 shl (32 - prefix.toInt()))
            return ipInt and mask to mask
        }

        private fun isPoisoned(addr: java.net.Inet4Address): Boolean {
            val raw = addr.address
            val v = ((raw[0].toInt() and 0xFF) shl 24) or
                ((raw[1].toInt() and 0xFF) shl 16) or
                ((raw[2].toInt() and 0xFF) shl 8) or
                (raw[3].toInt() and 0xFF)
            return POISONED_CIDRS.any { (net, mask) -> (v and mask) == net }
        }
    }
}

/**
 * 站点连接允许的 TLS 版本。
 *
 * ⚠️ **TLS 1.2 不能删**。本工程已移除 Conscrypt（四个 ABI 原生库占了 APK 的 80%），
 * Android 7~9（API 24~28）没有 TLS 1.3，只能靠这条回退：
 * OkHttp 的 ConnectionSpec.apply 会把这里声明的协议与**平台实际支持的**取交集，
 * 老设备上交集就只剩 TLS 1.2。删掉它那些设备会直接连不上站点。
 * 有单元测试锁住这个不变量（DesktopTlsConfigTest）。
 */
internal val BROWSER_TLS_VERSIONS = arrayOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)

/** 站点连接使用的 ConnectionSpec（协议清单见 [BROWSER_TLS_VERSIONS]）。 */
internal fun browserConnectionSpec(): ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(*BROWSER_TLS_VERSIONS)
    .cipherSuites(
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
        )
        .build()
