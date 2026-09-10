package com.zzy.llmreader.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子地把 [tmp] 的内容替换到 [target]。
 *
 * 为什么需要它：缓存里存的是「整本书的章节正文 + LLM 译文 + 术语表」，
 * 旧实现是
 * ```
 * if (target.exists()) target.delete()      // ← 这里之后文件就不存在了
 * if (!tmp.renameTo(target)) { ...拷贝兜底... }
 * ```
 * 那个 `delete()` 制造了一个**文件不存在的窗口**：进程在这期间被杀
 * （Android 上很常见），整本书的缓存就没了 —— 而 `renameTo` 在 POSIX 上
 * 本来就能原子覆盖，那次删除纯属多余。
 *
 * 这里的做法：优先 `Files.move(ATOMIC_MOVE + REPLACE_EXISTING)`；
 * 文件系统不支持原子移动时（部分 Windows 场景）退回 `REPLACE_EXISTING`；
 * 再不行才做拷贝。
 *
 * @return true 表示已经落盘。
 */
internal fun atomicReplace(tmp: File, target: File): Boolean {
    // 优先原子替换
    runCatching {
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }.onSuccess { return true }

    // 退回非原子替换（仍是「先写完整内容再顶替」，比拷贝安全）
    runCatching {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.onSuccess { return true }

    // 最后兜底：直接拷贝
    return runCatching {
        target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
        tmp.delete()
        true
    }.getOrDefault(false)
}

/**
 * 把 [content] 原子地写入 [target]：先写同目录下的唯一临时文件，再整体替换。
 *
 * 临时文件名带纳秒后缀 —— 旧实现用的是固定的 `<文件名>.tmp`，
 * 同一本书的两个写者会互相覆盖对方的临时文件。
 */
internal fun writeTextAtomically(target: File, content: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
    tmp.writeText(content, Charsets.UTF_8)
    if (!atomicReplace(tmp, target)) {
        tmp.delete()
        error("写入失败：${target.absolutePath}")
    }
}
