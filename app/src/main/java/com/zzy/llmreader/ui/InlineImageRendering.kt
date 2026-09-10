package com.zzy.llmreader.ui

import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zzy.llmreader.image.ImageCache
import java.io.File

/**
 * 章节文本里被解析出的"段"。
 *  - [Text]：普通文本
 *  - [ImageMarker]：`[IMG: tags]` 形式的插图标记，渲染成图片卡片
 */
sealed class ChapterSegment {
    data class Text(val spanned: Spanned) : ChapterSegment()
    data class ImageMarker(val prompt: String) : ChapterSegment()
}

/**
 * 解析章节文本，按 `[IMG: ...]` 标记切分。匹配规则：
 *   `[IMG: 任意非]字符]` （大小写不敏感、忽略 IMG 与冒号之间的空白）
 * 为了同时兼容 LLM 偶尔输出的中文方括号 `【IMG: ...】`，也一并支持。
 */
val INLINE_IMAGE_REGEX: Regex = Regex(
    pattern = """[\[【]\s*IMG\s*[:：]\s*([^\]】\n]+?)\s*[\]】]""",
    option = RegexOption.IGNORE_CASE,
)

fun parseChapterSegments(content: Spanned?): List<ChapterSegment> {
    if (content == null) return emptyList()
    val text = content.toString()
    val matches = INLINE_IMAGE_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return listOf(ChapterSegment.Text(content))

    val out = mutableListOf<ChapterSegment>()
    var cursor = 0
    for (m in matches) {
        if (m.range.first > cursor) {
            val sub = subSpanned(content, cursor, m.range.first)
            if (sub.isNotBlank()) out += ChapterSegment.Text(sub)
        }
        val prompt = m.groupValues[1].trim()
        if (prompt.isNotBlank()) out += ChapterSegment.ImageMarker(prompt)
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        val sub = subSpanned(content, cursor, text.length)
        if (sub.isNotBlank()) out += ChapterSegment.Text(sub)
    }
    return out
}

private fun subSpanned(src: Spanned, start: Int, end: Int): Spanned {
    if (start >= end) return SpannedString("")
    return when (src) {
        is Spannable -> SpannableString(src.subSequence(start, end))
        else -> SpannedString(src.subSequence(start, end))
    }
}

/**
 * 把章节文本中的 `[IMG: ...]` / `【IMG: ...】` 标记整段移除，返回不含这些标记的 Spanned。
 * 关闭"在文中插入插图"时使用，避免读者看到原始 tag 噪声。
 */
fun stripInlineImageMarkers(content: Spanned?): Spanned? {
    if (content == null) return null
    val text = content.toString()
    val matches = INLINE_IMAGE_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return content
    val sb = android.text.SpannableStringBuilder()
    var cursor = 0
    for (m in matches) {
        if (m.range.first > cursor) {
            sb.append(content.subSequence(cursor, m.range.first))
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        sb.append(content.subSequence(cursor, text.length))
    }
    // 顺手把因移除标记而残留的孤立空白行压缩
    val cleaned = sb.toString()
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
    return if (cleaned == sb.toString()) sb else SpannableString(cleaned)
}

/** 用于把"插图查询/生成/缓存路径"传给 ChapterPage 而不引入大量参数。 */
data class InlineImageHandlers(
    val enabled: Boolean,
    /** 给定 prompt → 返回该 prompt 对应已缓存图片的本地文件，或 null。 */
    val findByPrompt: suspend (String) -> File?,
    /** 触发生成。callback 收到生成结果（成功的 ImageCache.GeneratedImage 或失败原因）。 */
    val generate: (String, (Result<ImageCache.GeneratedImage>) -> Unit) -> Unit,
    /** 当前是否有任意图片正在生成中（用于禁用按钮）。 */
    val generating: Boolean,
    /** 一键生成本章所有插图（按 prompt 列表）。onProgress(current,total,prompt)；onDone(ok,fail)。 */
    val generateAll: (
        prompts: List<String>,
        onProgress: (Int, Int, String) -> Unit,
        onDone: (Int, Int) -> Unit,
    ) -> Unit,
)

val LocalInlineImageHandlers = compositionLocalOf<InlineImageHandlers> {
    InlineImageHandlers(
        enabled = false,
        findByPrompt = { null },
        generate = { _, cb -> cb(Result.failure(IllegalStateException("未配置"))) },
        generating = false,
        generateAll = { _, _, done -> done(0, 0) },
    )
}

@Composable
fun InlineImageMarkerCard(
    prompt: String,
    handlers: InlineImageHandlers,
) {
    var file by remember(prompt) { mutableStateOf<File?>(null) }
    var refreshTick by remember(prompt) { mutableStateOf(0) }
    var localGenerating by remember(prompt) { mutableStateOf(false) }
    var error by remember(prompt) { mutableStateOf<String?>(null) }

    LaunchedEffect(prompt, refreshTick) {
        file = handlers.findByPrompt(prompt)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            val cur = file
            if (cur != null && cur.exists()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(cur)
                        .crossfade(true)
                        .build(),
                    contentDescription = prompt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = {
                            if (handlers.enabled && !localGenerating) {
                                localGenerating = true
                                error = null
                                handlers.generate(prompt) { result ->
                                    localGenerating = false
                                    result.onSuccess { refreshTick++ }
                                        .onFailure { error = it.message }
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新生成")
                    }
                }
            } else {
                // 紧凑形态：一行小按钮 + tag 概要，避免占据太大版面
                val onClick: () -> Unit = {
                    if (handlers.enabled && !localGenerating) {
                        localGenerating = true
                        error = null
                        handlers.generate(prompt) { result ->
                            localGenerating = false
                            result.onSuccess { refreshTick++ }
                                .onFailure { error = it.message }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.AssistChip(
                        onClick = onClick,
                        enabled = handlers.enabled && !localGenerating,
                        leadingIcon = {
                            if (localGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Brush,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        },
                        label = {
                            Text(
                                if (localGenerating) "生成中"
                                else if (handlers.enabled) "生成插图"
                                else "未启用",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
