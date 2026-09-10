package com.zzy.llmreader.platform

import java.io.File

/**
 * 平台提供的可写目录。Android 传 `filesDir/cacheDir`，桌面端传 `~/.llm_reader` 等。
 *
 * 这是 shared 模块与平台之间唯一的"文件系统缝隙"：所有仓储、缓存都只依赖它，
 * 不再直接持有 `android.content.Context`。
 */
data class AppDirs(
    /** 私有数据目录（设置、网页缓存、术语表、生成的图片）。 */
    val dataDir: File,
    /** 可被系统清理的缓存目录。 */
    val cacheDir: File,
) {
    init {
        dataDir.mkdirs()
        cacheDir.mkdirs()
    }

    /** 取 dataDir 下的子目录，不存在则创建。 */
    fun dataSub(vararg segments: String): File =
        segments.fold(dataDir) { acc, s -> File(acc, s) }.apply { mkdirs() }
}
