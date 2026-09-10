package com.zzy.llmreader.data

import com.zzy.llmreader.platform.AppDirs
import com.zzy.llmreader.web.GlossaryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 给本地书（无 canonical URL）持久化术语表。按 book.title 做 key，存到 filesDir/local_glossary/<sha>.json。
 * 文件结构与网页书的 glossary 字段一致，便于翻译流程统一读取。
 */
class LocalGlossaryRepository(private val dirs: AppDirs) {

    @Serializable
    private data class Stored(val entries: List<GlossaryEntry> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(bookKey: String): List<GlossaryEntry> {
        val f = file(bookKey)
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString<Stored>(f.readText(Charsets.UTF_8)).entries }
            .getOrElse { emptyList() }
    }

    fun write(bookKey: String, entries: List<GlossaryEntry>) {
        val f = file(bookKey)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored(entries)), Charsets.UTF_8)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun file(bookKey: String): File = File(dir, "${sha256(bookKey)}.json")

    private val dir: File
        get() = File(dirs.dataDir, "local_glossary")

    private fun sha256(v: String): String =
        MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
