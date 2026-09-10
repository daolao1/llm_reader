package com.zzy.llmreader.image

import com.zzy.llmreader.data.ImageBackend
import com.zzy.llmreader.platform.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 章节级生成图片缓存。
 *
 * 目录结构：
 *   filesDir/gen_images/<bookId>/<chapterIndex>/<imageId>.png
 *   filesDir/gen_images/<bookId>/<chapterIndex>/index.json
 *
 * - bookId 为书的 canonicalUrl 或 ID 的 SHA-256（避免文件名问题）。
 * - index.json 是该章节的图片清单：filename、prompt、宽高、时间戳、后端类型。
 */
class ImageCache(private val dirs: AppDirs) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val rootDir: File
        get() = File(dirs.dataDir, "gen_images").apply { if (!exists()) mkdirs() }

    @Serializable
    data class GeneratedImage(
        val id: String,
        val filename: String,
        val prompt: String,
        val negative: String,
        val backend: String,
        val width: Int,
        val height: Int,
        val createdAt: Long,
    )

    @Serializable
    private data class ChapterIndex(
        val images: List<GeneratedImage> = emptyList(),
    )

    private fun chapterDir(bookId: String, chapterIndex: Int): File {
        val safeBook = sha1(bookId).take(16)
        return File(rootDir, "$safeBook/$chapterIndex").apply { if (!exists()) mkdirs() }
    }

    private fun indexFile(bookId: String, chapterIndex: Int): File =
        File(chapterDir(bookId, chapterIndex), "index.json")

    /**
     * 读章节索引。
     *
     * 索引损坏时**不能**静默返回空列表：`save()` 会拿 `list()` 的结果做
     * `current + entry` 再整体覆盖，于是一份坏索引会让此前生成的所有图片
     * 从索引里永久消失（PNG 还在磁盘上，但 list / delete / findByPrompt
     * 都看不到，prompt 缓存也失效、重复生成要再次付费）。
     *
     * 这里把坏文件改名留档，并打日志，让问题可见、内容可人工捞回。
     */
    private fun readIndex(f: File): List<GeneratedImage> {
        if (!f.exists()) return emptyList()
        val raw = runCatching { f.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString(ChapterIndex.serializer(), raw).images
        }.getOrElse { err ->
            val kept = File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}")
            runCatching { f.renameTo(kept) }
            com.zzy.llmreader.platform.PlatformLog.e(
                "ImageCache",
                "章节索引损坏，已留档到 ${kept.name}（图片文件仍在，可人工恢复）：${err.message}",
            )
            emptyList()
        }
    }

    suspend fun list(bookId: String, chapterIndex: Int): List<GeneratedImage> =
        withContext(Dispatchers.IO) {
            readIndex(indexFile(bookId, chapterIndex))
        }

    suspend fun save(
        bookId: String,
        chapterIndex: Int,
        bytes: ByteArray,
        prompt: String,
        negative: String,
        backend: ImageBackend,
        width: Int,
        height: Int,
    ): GeneratedImage = withContext(Dispatchers.IO) {
        val dir = chapterDir(bookId, chapterIndex)
        val id = System.currentTimeMillis().toString() + "_" +
            (1..6).map { ('a'..'z').random() }.joinToString("")
        val filename = "$id.png"
        File(dir, filename).writeBytes(bytes)
        val entry = GeneratedImage(
            id = id,
            filename = filename,
            prompt = prompt,
            negative = negative,
            backend = backend.name,
            width = width,
            height = height,
            createdAt = System.currentTimeMillis(),
        )
        val current = list(bookId, chapterIndex)
        val updated = ChapterIndex(images = current + entry)
        com.zzy.llmreader.platform.writeTextAtomically(
            indexFile(bookId, chapterIndex),
            json.encodeToString(ChapterIndex.serializer(), updated),
        )
        entry
    }

    suspend fun delete(bookId: String, chapterIndex: Int, id: String) =
        withContext(Dispatchers.IO) {
            val current = list(bookId, chapterIndex)
            val match = current.find { it.id == id } ?: return@withContext
            File(chapterDir(bookId, chapterIndex), match.filename).delete()
            val updated = ChapterIndex(images = current.filter { it.id != id })
            com.zzy.llmreader.platform.writeTextAtomically(
                indexFile(bookId, chapterIndex),
                json.encodeToString(ChapterIndex.serializer(), updated),
            )
        }

    fun fileFor(bookId: String, chapterIndex: Int, filename: String): File =
        File(chapterDir(bookId, chapterIndex), filename)

    /**
     * 在某本书内（任意章节）按 prompt 精确匹配查找一张已生成的图片。
     * 用于"在文中插入插图"：同一段插图脚本（prompt）已经生成过就直接复用，避免重复消耗。
     */
    suspend fun findByPrompt(bookId: String, prompt: String): GeneratedImage? =
        withContext(Dispatchers.IO) {
            val safeBook = sha1(bookId).take(16)
            val bookDir = File(rootDir, safeBook)
            if (!bookDir.isDirectory) return@withContext null
            val target = prompt.trim()
            for (chapter in bookDir.listFiles().orEmpty()) {
                val idxFile = File(chapter, "index.json")
                if (!idxFile.exists()) continue
                val list = runCatching {
                    json.decodeFromString(ChapterIndex.serializer(), idxFile.readText()).images
                }.getOrDefault(emptyList())
                list.firstOrNull { it.prompt.trim() == target }?.let { return@withContext it }
            }
            null
        }

    /**
     * 给定章节内 prompt 列表，返回 prompt → 已缓存图片对象（找不到的不在结果里）。
     */
    suspend fun lookupBatch(
        bookId: String,
        prompts: List<String>,
    ): Map<String, GeneratedImage> = withContext(Dispatchers.IO) {
        if (prompts.isEmpty()) return@withContext emptyMap()
        val safeBook = sha1(bookId).take(16)
        val bookDir = File(rootDir, safeBook)
        if (!bookDir.isDirectory) return@withContext emptyMap()
        val want = prompts.map { it.trim() }.toSet()
        val out = mutableMapOf<String, GeneratedImage>()
        for (chapter in bookDir.listFiles().orEmpty()) {
            val idxFile = File(chapter, "index.json")
            if (!idxFile.exists()) continue
            val list = runCatching {
                json.decodeFromString(ChapterIndex.serializer(), idxFile.readText()).images
            }.getOrDefault(emptyList())
            for (entry in list) {
                val p = entry.prompt.trim()
                if (p in want && p !in out) out[p] = entry
            }
        }
        out
    }

    /** 按 prompt 在某本书内查找文件实际路径。 */
    suspend fun fileForPrompt(bookId: String, prompt: String): File? =
        withContext(Dispatchers.IO) {
            val entry = findByPrompt(bookId, prompt) ?: return@withContext null
            val safeBook = sha1(bookId).take(16)
            // 找到所属章节目录
            val bookDir = File(rootDir, safeBook)
            for (chapter in bookDir.listFiles().orEmpty()) {
                val f = File(chapter, entry.filename)
                if (f.exists()) return@withContext f
            }
            null
        }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
