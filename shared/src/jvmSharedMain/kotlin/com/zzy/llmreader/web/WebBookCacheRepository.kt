package com.zzy.llmreader.web

import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.model.Chapter
import com.zzy.llmreader.platform.AppDirs
import com.zzy.llmreader.platform.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class CachedWebChapter(
    val title: String,
    /** 章节正文 HTML；懒加载下未下载时为 `null`。 */
    val html: String? = null,
    /** 单章 URL，懒加载抓取时使用。 */
    val chapterUrl: String? = null,
    /** 翻译后的 HTML；尚未翻译为 `null`。 */
    val translatedHtml: String? = null,
    /** 翻译后的标题；用于目录显示译名。 */
    val translatedTitle: String? = null,
    /** 翻译目标语言 tag（如 "zh-CN"），便于切换语言时判断是否需要重译。 */
    val translationLanguage: String? = null,
    /** 所属上层分组（如 kakuyomu 的「X章」），目录里用作分节标题。 */
    val groupTitle: String? = null,
)

@Serializable
data class GlossaryEntry(
    val original: String,
    val translated: String,
    /** 最近一次被翻译命中的时间戳（ms），用于 LRU 淘汰。 */
    val lastUsed: Long = System.currentTimeMillis(),
)

@Serializable
data class CachedWebBook(
    val source: String,
    val url: String,
    val title: String,
    val fetchedAt: Long,
    val chapters: List<CachedWebChapter>,
    /** 跨章节术语表（人名/专有名词 → 译名对照），用户可手动编辑。 */
    val glossary: List<GlossaryEntry> = emptyList(),
)

class WebBookCacheRepository(private val dirs: AppDirs) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun read(url: String): CachedWebBook? {
        val file = cacheFile(url)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<CachedWebBook>(file.readText(Charsets.UTF_8)) }
            .getOrNull()
    }

    /** 同步读取（主线程安全时使用，如在 ViewModel 中已确认是初始化读取）。 */
    fun readSync(url: String): CachedWebBook? = read(url)

    fun write(book: CachedWebBook): CachedWebBook {
        // 原子写：这里存的是整本书的章节正文 + LLM 译文 + 术语表，
        // 「先 delete 再 rename」会留出文件不存在的窗口，进程被杀就全丢。
        writeTextAtomically(cacheFile(book.url), json.encodeToString(book))
        return book
    }

    fun delete(url: String) {
        cacheFile(url).delete()
    }

    private fun cacheFile(url: String): File = File(cacheDir, "${sha256(url)}.json")

    private val cacheDir: File
        get() = dirs.dataSub("web_books")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

fun CachedWebBook.toBookContent(): BookContent.Text = BookContent.Text(
    title = title,
    chapters = chapters.map { chapter ->
        Chapter(
            title = chapter.title,
            content = chapter.html,
            translatedTitle = chapter.translatedTitle,
            translatedContent = chapter.translatedHtml,
            groupTitle = chapter.groupTitle,
        )
    },
)