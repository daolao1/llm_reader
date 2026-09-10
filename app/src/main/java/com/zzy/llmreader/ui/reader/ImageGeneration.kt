package com.zzy.llmreader.ui.reader

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.zzy.llmreader.data.ImageGenSettings
import com.zzy.llmreader.image.CharacterLibrary
import com.zzy.llmreader.image.ImageCache
import com.zzy.llmreader.llm.ChatMessage
import com.zzy.llmreader.ui.ReaderViewModel
import com.zzy.llmreader.ui.ReaderUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片生成相关的 ViewModel 能力。
 *
 * 从 3144 行的 ReaderViewModel 里按职责拆出来：这里只处理
 * "抽场景 prompt → 调 NovelAI/ComfyUI → 落盘缓存 → 刷新 state" 这条链路，
 * 与翻译、发现页完全解耦。
 *
 * 用扩展函数而非独立类：方法体原样搬移，行为零改动；
 * 代价是这些成员在模块内可见（`internal`）。
 */
    /** 加载当前章节已生成的图片列表，刷新到 state。 */
    internal fun ReaderViewModel.refreshChapterImages() {
        val bookId = currentBookId() ?: return
        val idx = _state.value.currentChapter
        viewModelScope.launch {
            val list = imageCache.list(bookId, idx)
            _state.update { it.copy(chapterImages = list) }
        }
    }

    internal fun ReaderViewModel.saveImageGenSettings(s: com.zzy.llmreader.data.ImageGenSettings) {
        viewModelScope.launch { imageGenRepo.update(s) }
    }

    internal fun ReaderViewModel.saveCharacterLibrary(lib: com.zzy.llmreader.image.CharacterLibrary) {
        viewModelScope.launch { characterRepo.update(lib) }
    }

    internal fun ReaderViewModel.clearImageGenError() {
        if (_state.value.imageGenError != null) {
            _state.update { it.copy(imageGenError = null) }
        }
    }

    /**
     * 用 LLM 从当前章节抽取场景，输出 Danbooru 风格的英文 tag。
     */
    internal fun ReaderViewModel.extractScenePrompt(callback: (Result<String>) -> Unit) {
        val cur = _state.value
        val ctx = currentChapterText(cur).orEmpty()
        if (ctx.isBlank()) {
            callback(Result.failure(IllegalStateException("当前章节为空")))
            return
        }
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            val excerpt = ctx.take(4000)
            val msgs = listOf(
                ChatMessage(
                    role = "system",
                    content = "你是 Danbooru 风格的图像生成 prompt 工程师。" +
                        "用户给你一段小说章节正文，你要选出最具画面感的一个场景（人物、动作、环境、氛围），" +
                        "输出英文逗号分隔的 tag 序列；不要任何中文，不要句子，不要解释，不要 NSFW 触发词；" +
                        "20-40 个 tag 之间。",
                ),
                ChatMessage(role = "user", content = "章节正文：\n$excerpt\n\n请输出 tag 序列。"),
            )
            client.chat(settings, msgs)
                .map { raw ->
                    raw.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .joinToString(", ")
                        .replace(Regex("\\s*,\\s*"), ", ")
                        .trim(',', ' ')
                }
                .let(callback)
        }
    }

    /** 触发一次图片生成，结果（成功时）已经保存进 ImageCache，会自动刷新 chapterImages。 */
    internal fun ReaderViewModel.generateImage(positivePrompt: String, callback: (Result<com.zzy.llmreader.image.ImageCache.GeneratedImage>) -> Unit) {
        val cur = _state.value
        val bookId = currentBookId()
        if (bookId == null) {
            callback(Result.failure(IllegalStateException("请先打开一本书")))
            return
        }
        val backend = cur.imageGenSettings.backend
        if (backend == com.zzy.llmreader.data.ImageBackend.NONE) {
            callback(Result.failure(IllegalStateException("请先在「图片生成 → 设置」选择后端 (NovelAI / ComfyUI)")))
            return
        }
        val idx = cur.currentChapter
        val sceneText = currentChapterText(cur).orEmpty()
        // 自动追加角色相关的外观/穿着
        val charAddon = characterRepo.buildAutoPromptAddon(cur.characterLibrary, sceneText)
        val finalPrompt = listOf(positivePrompt, charAddon).filter { it.isNotBlank() }.joinToString(", ")

        _state.update { it.copy(generatingImage = true, imageGenError = null) }
        viewModelScope.launch {
            val result = runCatching {
                val s = cur.imageGenSettings
                when (backend) {
                    com.zzy.llmreader.data.ImageBackend.NOVELAI ->
                        novelAi.generate(s, finalPrompt) to (s.novelaiWidth to s.novelaiHeight)
                    com.zzy.llmreader.data.ImageBackend.COMFYUI ->
                        comfyUi.generate(s, finalPrompt) to (s.comfyuiWidth to s.comfyuiHeight)
                    com.zzy.llmreader.data.ImageBackend.NONE -> error("unreachable")
                }
            }.mapCatching { (bytes, size) ->
                imageCache.save(
                    bookId = bookId,
                    chapterIndex = idx,
                    bytes = bytes,
                    prompt = finalPrompt,
                    negative = when (backend) {
                        com.zzy.llmreader.data.ImageBackend.NOVELAI -> cur.imageGenSettings.novelaiNegativePrompt
                        com.zzy.llmreader.data.ImageBackend.COMFYUI -> cur.imageGenSettings.comfyuiNegativePrompt
                        else -> ""
                    },
                    backend = backend,
                    width = size.first,
                    height = size.second,
                )
            }
            _state.update { it.copy(generatingImage = false) }
            result.onSuccess { entry ->
                val list = imageCache.list(bookId, idx)
                _state.update { it.copy(chapterImages = list) }
                callback(Result.success(entry))
            }.onFailure { err ->
                Log.e(ReaderViewModel.TAG, "image generation failed", err)
                _state.update { it.copy(imageGenError = err.message) }
                callback(Result.failure(err))
            }
        }
    }

    internal fun ReaderViewModel.deleteGeneratedImage(id: String) {
        val bookId = currentBookId() ?: return
        val idx = _state.value.currentChapter
        viewModelScope.launch {
            imageCache.delete(bookId, idx, id)
            val list = imageCache.list(bookId, idx)
            _state.update { it.copy(chapterImages = list) }
        }
    }

    /** 给定一个 prompt，查找该 prompt 在当前书内是否已生成过图片，返回本地文件。 */
    internal suspend fun ReaderViewModel.findCachedImageByPrompt(prompt: String): java.io.File? {
        val bookId = currentBookId() ?: return null
        return imageCache.fileForPrompt(bookId, prompt)
    }

    /**
     * 用于"在文中插入插图"：根据 prompt 直接生成一张，不依赖当前章节已生成列表。
     * 完成后会刷新当前章节的图片列表（如果生成的是同一章），方便面板/按 prompt 查找。
     */
    internal fun ReaderViewModel.generateInlineImage(prompt: String, callback: (Result<com.zzy.llmreader.image.ImageCache.GeneratedImage>) -> Unit) {
        // 直接复用 generateImage 即可：它已经把图片写到当前章节目录下，
        // findCachedImageByPrompt 跨章节查找时也能命中。
        generateImage(prompt, callback)
    }

    /**
     * 批量生成本章所有 [IMG: ...] 标记对应的插图。
     * - 已缓存（按 prompt 命中）的会跳过
     * - 串行执行，避免后端被打爆
     * - 通过 [onProgress] 上报 (current, total, currentPrompt)
     * - 通过 [onDone] 上报 (success, failed)
     */
    internal fun ReaderViewModel.generateAllInlinePrompts(
        prompts: List<String>,
        onProgress: (current: Int, total: Int, prompt: String) -> Unit = { _, _, _ -> },
        onDone: (success: Int, failed: Int) -> Unit = { _, _ -> },
    ) {
        val bookId = currentBookId()
        if (bookId == null) {
            onDone(0, prompts.size)
            return
        }
        val backend = _state.value.imageGenSettings.backend
        if (backend == com.zzy.llmreader.data.ImageBackend.NONE) {
            _state.update { it.copy(imageGenError = "请先在「图片生成 → 设置」选择后端") }
            onDone(0, prompts.size)
            return
        }
        viewModelScope.launch {
            val unique = prompts.distinct()
            var ok = 0
            var fail = 0
            for ((i, p) in unique.withIndex()) {
                onProgress(i + 1, unique.size, p)
                // 已缓存则跳过
                val existing = imageCache.fileForPrompt(bookId, p)
                if (existing != null) {
                    ok++
                    continue
                }
                val result = kotlinx.coroutines.suspendCancellableCoroutine<Result<com.zzy.llmreader.image.ImageCache.GeneratedImage>> { cont ->
                    generateImage(p) { r -> if (cont.isActive) cont.resumeWith(Result.success(r)) }
                }
                if (result.isSuccess) ok++ else fail++
            }
            onDone(ok, fail)
        }
    }

    /** 获取一张已保存图片的本地文件路径。 */
    internal fun ReaderViewModel.imageFilePath(filename: String): String? {
        val bookId = currentBookId() ?: return null
        val idx = _state.value.currentChapter
        return imageCache.fileFor(bookId, idx, filename).absolutePath
    }
