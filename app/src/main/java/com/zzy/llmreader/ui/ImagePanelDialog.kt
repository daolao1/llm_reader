package com.zzy.llmreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zzy.llmreader.data.ImageBackend
import com.zzy.llmreader.data.ImageGenSettings
import com.zzy.llmreader.image.CharacterLibrary
import com.zzy.llmreader.image.CharacterPreset
import com.zzy.llmreader.image.ImageCache
import com.zzy.llmreader.image.OutfitPreset
import java.io.File

/**
 * 图片生成的统一面板：包含 4 个 tab。
 *  - 生成  (M3/M4): 输入 prompt → 生成 → 显示当前章节图片
 *  - 设置  (M2):    NovelAI / ComfyUI 后端配置
 *  - 角色  (M5):    人物 + 装束预设
 *  - 导入  (M6):    粘贴 SillyTavern st-chatu8 配置 JSON
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePanelDialog(
    settings: ImageGenSettings,
    library: CharacterLibrary,
    chapterImages: List<ImageCache.GeneratedImage>,
    generating: Boolean,
    error: String?,
    imagePath: (String) -> String?,
    onDismiss: () -> Unit,
    onSaveSettings: (ImageGenSettings) -> Unit,
    onSaveLibrary: (CharacterLibrary) -> Unit,
    onGenerate: (String, (Result<ImageCache.GeneratedImage>) -> Unit) -> Unit,
    onExtractScene: ((Result<String>) -> Unit) -> Unit,
    onDeleteImage: (String) -> Unit,
    onImportStChatu8: (String, (Result<String>) -> Unit) -> Unit,
    onClearError: () -> Unit,
    onLoadImagesIfNeeded: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("生成", "设置", "角色", "导入")

    // 进入面板时自动刷新图片列表
    androidx.compose.runtime.LaunchedEffect(Unit) { onLoadImagesIfNeeded() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 720.dp)
                .padding(horizontal = 8.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("图片生成", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    0 -> GenerateTab(
                        settings = settings,
                        chapterImages = chapterImages,
                        generating = generating,
                        error = error,
                        imagePath = imagePath,
                        onGenerate = onGenerate,
                        onExtractScene = onExtractScene,
                        onDeleteImage = onDeleteImage,
                        onClearError = onClearError,
                    )
                    1 -> SettingsTab(settings = settings, onSave = onSaveSettings)
                    2 -> CharactersTab(library = library, onSave = onSaveLibrary)
                    3 -> ImportTab(onImport = onImportStChatu8)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- 生成 tab

@Composable
private fun GenerateTab(
    settings: ImageGenSettings,
    chapterImages: List<ImageCache.GeneratedImage>,
    generating: Boolean,
    error: String?,
    imagePath: (String) -> String?,
    onGenerate: (String, (Result<ImageCache.GeneratedImage>) -> Unit) -> Unit,
    onExtractScene: ((Result<String>) -> Unit) -> Unit,
    onDeleteImage: (String) -> Unit,
    onClearError: () -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var extracting by remember { mutableStateOf(false) }
    val backendLabel = when (settings.backend) {
        ImageBackend.NONE -> "未启用（请在「设置」选择后端）"
        ImageBackend.NOVELAI -> "NovelAI · ${settings.novelaiModel}"
        ImageBackend.COMFYUI -> "ComfyUI · ${settings.comfyuiCheckpoint.ifBlank { "(默认)" }}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("当前后端：$backendLabel", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp),
            label = { Text("Prompt（英文 Danbooru tag，逗号分隔）") },
            minLines = 3,
            maxLines = 8,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    extracting = true
                    onExtractScene { result ->
                        extracting = false
                        result.onSuccess { prompt = it }
                    }
                },
                enabled = !extracting && !generating,
                modifier = Modifier.weight(1f),
            ) {
                if (extracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                }
                Spacer(Modifier.size(4.dp))
                Text("从章节抽取场景")
            }
            Button(
                onClick = {
                    onClearError()
                    onGenerate(prompt) { /* 由 ViewModel 自动刷新列表 */ }
                },
                enabled = !generating && prompt.isNotBlank()
                    && settings.backend != ImageBackend.NONE,
                modifier = Modifier.weight(1f),
            ) {
                if (generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Brush, contentDescription = null)
                }
                Spacer(Modifier.size(4.dp))
                Text(if (generating) "生成中…" else "生成")
            }
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()
        Text("本章已生成 ${chapterImages.size} 张", style = MaterialTheme.typography.titleSmall)
        if (chapterImages.isEmpty()) {
            Text("还没有图片。输入 prompt 或点「从章节抽取场景」开始。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chapterImages, key = { it.id }) { entry ->
                    val path = imagePath(entry.filename)
                    Card(modifier = Modifier.size(width = 200.dp, height = 280.dp)) {
                        Column {
                            Box(Modifier.weight(1f)) {
                                if (path != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                            .data(File(path))
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = entry.prompt,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${entry.width}×${entry.height}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp)
                                        .weight(1f),
                                )
                                IconButton(onClick = { onDeleteImage(entry.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- 设置 tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(settings: ImageGenSettings, onSave: (ImageGenSettings) -> Unit) {
    var s by remember { mutableStateOf(settings) }
    var backendMenuOpen by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        // 后端选择
        ExposedDropdownMenuBox(
            expanded = backendMenuOpen,
            onExpandedChange = { backendMenuOpen = it },
        ) {
            OutlinedTextField(
                value = when (s.backend) {
                    ImageBackend.NONE -> "禁用"
                    ImageBackend.NOVELAI -> "NovelAI"
                    ImageBackend.COMFYUI -> "ComfyUI"
                },
                onValueChange = {},
                readOnly = true,
                label = { Text("后端") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = backendMenuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            DropdownMenu(
                expanded = backendMenuOpen,
                onDismissRequest = { backendMenuOpen = false },
            ) {
                listOf(
                    ImageBackend.NONE to "禁用",
                    ImageBackend.NOVELAI to "NovelAI",
                    ImageBackend.COMFYUI to "ComfyUI",
                ).forEach { (b, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            s = s.copy(backend = b)
                            backendMenuOpen = false
                        },
                    )
                }
            }
        }
        when (s.backend) {
            ImageBackend.NOVELAI -> NovelAiSettings(s) { s = it }
            ImageBackend.COMFYUI -> ComfyUiSettings(s) { s = it }
            ImageBackend.NONE -> Text(
                "选择一个后端以填写参数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.autoSceneExtract,
                onClick = { s = s.copy(autoSceneExtract = !s.autoSceneExtract) },
                label = { Text("默认自动抽场景") },
            )
        }
        HorizontalDivider()
        Text("在文中插入插图（推荐）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.enableInlineImages,
                onClick = { s = s.copy(enableInlineImages = !s.enableInlineImages) },
                label = { Text(if (s.enableInlineImages) "已启用" else "已关闭") },
            )
        }
        Text(
            "启用后，翻译时 LLM 会在每个画面感强烈的段落末尾追加 [IMG: 英文 tag] 标记。" +
                "阅读器会把这些标记渲染成可点的图片卡片，按一下即可生成图片，并直接显示在文中位置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.enableInlineImages) {
            Text(
                "建议每章生成插图数：${s.inlineImagesPerChapter} 张",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = s.inlineImagesPerChapter.toFloat(),
                onValueChange = { s = s.copy(inlineImagesPerChapter = it.toInt()) },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text(
                "提示：仅指导 LLM 大致密度；实际生成数取决于章节长度和场景。" +
                    "启用此项会改变 system prompt，会绕开 prompt 缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { onSave(s) }, modifier = Modifier.fillMaxWidth()) {
            Text("保存设置")
        }
    }
}

@Composable
private fun NovelAiSettings(s: ImageGenSettings, onChange: (ImageGenSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = s.novelaiApiKey,
            onValueChange = { onChange(s.copy(novelaiApiKey = it)) },
            label = { Text("NovelAI API Key (pst-...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.novelaiSite == "official",
                onClick = { onChange(s.copy(novelaiSite = "official")) },
                label = { Text("官方") },
            )
            FilterChip(
                selected = s.novelaiSite == "custom",
                onClick = { onChange(s.copy(novelaiSite = "custom")) },
                label = { Text("自定义站点") },
            )
        }
        if (s.novelaiSite == "custom") {
            OutlinedTextField(
                value = s.novelaiCustomUrl,
                onValueChange = { onChange(s.copy(novelaiCustomUrl = it)) },
                label = { Text("自定义 URL") },
                placeholder = { Text("https://example.com/ai/generate-image") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = s.novelaiModel,
            onValueChange = { onChange(s.copy(novelaiModel = it)) },
            label = { Text("Model") },
            placeholder = { Text("nai-diffusion-4-5-full") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.novelaiSampler,
                onValueChange = { onChange(s.copy(novelaiSampler = it)) },
                label = { Text("Sampler") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.novelaiSchedule,
                onValueChange = { onChange(s.copy(novelaiSchedule = it)) },
                label = { Text("Schedule") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.novelaiWidth.toString(),
                onValueChange = { onChange(s.copy(novelaiWidth = it.toIntOrNull() ?: s.novelaiWidth)) },
                label = { Text("宽") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.novelaiHeight.toString(),
                onValueChange = { onChange(s.copy(novelaiHeight = it.toIntOrNull() ?: s.novelaiHeight)) },
                label = { Text("高") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.novelaiSteps.toString(),
                onValueChange = { onChange(s.copy(novelaiSteps = it.toIntOrNull() ?: s.novelaiSteps)) },
                label = { Text("Steps") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.novelaiScale.toString(),
                onValueChange = {
                    onChange(s.copy(novelaiScale = it.toDoubleOrNull() ?: s.novelaiScale))
                },
                label = { Text("CFG Scale") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.novelaiCfgRescale.toString(),
                onValueChange = {
                    onChange(s.copy(novelaiCfgRescale = it.toDoubleOrNull() ?: s.novelaiCfgRescale))
                },
                label = { Text("CFG Rescale") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.novelaiSeed.toString(),
                onValueChange = { onChange(s.copy(novelaiSeed = it.toLongOrNull() ?: 0L)) },
                label = { Text("Seed (0=随机)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = s.novelaiSm,
                onClick = { onChange(s.copy(novelaiSm = !s.novelaiSm)) },
                label = { Text("SM") },
            )
            FilterChip(
                selected = s.novelaiSmDyn,
                onClick = { onChange(s.copy(novelaiSmDyn = !s.novelaiSmDyn)) },
                label = { Text("SM Dyn") },
            )
            FilterChip(
                selected = s.novelaiDecrisper,
                onClick = { onChange(s.copy(novelaiDecrisper = !s.novelaiDecrisper)) },
                label = { Text("Decrisper") },
            )
            FilterChip(
                selected = s.novelaiVarietyBoost,
                onClick = { onChange(s.copy(novelaiVarietyBoost = !s.novelaiVarietyBoost)) },
                label = { Text("Variety+") },
            )
        }
        OutlinedTextField(
            value = s.novelaiPositivePrefix,
            onValueChange = { onChange(s.copy(novelaiPositivePrefix = it)) },
            label = { Text("正向 prompt 前缀") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1, maxLines = 3,
        )
        OutlinedTextField(
            value = s.novelaiNegativePrompt,
            onValueChange = { onChange(s.copy(novelaiNegativePrompt = it)) },
            label = { Text("负向 prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 5,
        )
    }
}

@Composable
private fun ComfyUiSettings(s: ImageGenSettings, onChange: (ImageGenSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = s.comfyuiUrl,
            onValueChange = { onChange(s.copy(comfyuiUrl = it)) },
            label = { Text("ComfyUI URL") },
            placeholder = { Text("http://127.0.0.1:8188") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.comfyuiCheckpoint,
            onValueChange = { onChange(s.copy(comfyuiCheckpoint = it)) },
            label = { Text("Checkpoint 模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.comfyuiSampler,
                onValueChange = { onChange(s.copy(comfyuiSampler = it)) },
                label = { Text("Sampler") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.comfyuiScheduler,
                onValueChange = { onChange(s.copy(comfyuiScheduler = it)) },
                label = { Text("Scheduler") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = s.comfyuiWidth.toString(),
                onValueChange = { onChange(s.copy(comfyuiWidth = it.toIntOrNull() ?: s.comfyuiWidth)) },
                label = { Text("宽") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.comfyuiHeight.toString(),
                onValueChange = { onChange(s.copy(comfyuiHeight = it.toIntOrNull() ?: s.comfyuiHeight)) },
                label = { Text("高") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.comfyuiSteps.toString(),
                onValueChange = { onChange(s.copy(comfyuiSteps = it.toIntOrNull() ?: s.comfyuiSteps)) },
                label = { Text("Steps") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = s.comfyuiCfg.toString(),
                onValueChange = { onChange(s.copy(comfyuiCfg = it.toDoubleOrNull() ?: s.comfyuiCfg)) },
                label = { Text("CFG") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = s.comfyuiWorkflowName,
            onValueChange = { onChange(s.copy(comfyuiWorkflowName = it)) },
            label = { Text("工作流名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.comfyuiWorkflowJson,
            onValueChange = { onChange(s.copy(comfyuiWorkflowJson = it)) },
            label = { Text("工作流 JSON（含 %POSITIVE% 等占位符）") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp),
            minLines = 4, maxLines = 10,
        )
        Text(
            "占位符：%POSITIVE% %NEGATIVE% %MODEL_NAME% %WIDTH% %HEIGHT% %SEED% %STEPS% %CFG% %SAMPLER% %SCHEDULER%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = s.comfyuiPositivePrefix,
            onValueChange = { onChange(s.copy(comfyuiPositivePrefix = it)) },
            label = { Text("正向 prompt 前缀") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1, maxLines = 3,
        )
        OutlinedTextField(
            value = s.comfyuiNegativePrompt,
            onValueChange = { onChange(s.copy(comfyuiNegativePrompt = it)) },
            label = { Text("负向 prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 5,
        )
    }
}

// ----------------------------------------------------------------------- 角色 tab

@Composable
private fun CharactersTab(library: CharacterLibrary, onSave: (CharacterLibrary) -> Unit) {
    var lib by remember { mutableStateOf(library) }
    var editChar by remember { mutableStateOf<CharacterPreset?>(null) }
    var editOutfit by remember { mutableStateOf<OutfitPreset?>(null) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("角色 (${lib.characters.size})", style = MaterialTheme.typography.titleSmall)
        lib.characters.forEach { c ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editChar = c },
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            c.appearancePrompt.ifBlank { c.englishName },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        lib = lib.copy(characters = lib.characters.filter { it.id != c.id })
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
        Button(
            onClick = {
                editChar = CharacterPreset(
                    id = "c_" + System.currentTimeMillis(),
                    name = "",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("+ 添加角色") }

        HorizontalDivider()

        Text("装束 (${lib.outfits.size})", style = MaterialTheme.typography.titleSmall)
        lib.outfits.forEach { o ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editOutfit = o },
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            o.prompt,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        lib = lib.copy(outfits = lib.outfits.filter { it.id != o.id })
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
        Button(
            onClick = {
                editOutfit = OutfitPreset(
                    id = "o_" + System.currentTimeMillis(),
                    name = "",
                    prompt = "",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("+ 添加装束") }

        Button(onClick = { onSave(lib) }, modifier = Modifier.fillMaxWidth()) {
            Text("保存角色库")
        }
    }

    editChar?.let { current ->
        CharacterEditDialog(
            initial = current,
            outfits = lib.outfits,
            onDismiss = { editChar = null },
            onSave = { saved ->
                lib = lib.copy(
                    characters = lib.characters.toMutableList().apply {
                        val i = indexOfFirst { it.id == saved.id }
                        if (i >= 0) this[i] = saved else add(saved)
                    },
                )
                editChar = null
            },
        )
    }
    editOutfit?.let { current ->
        OutfitEditDialog(
            initial = current,
            onDismiss = { editOutfit = null },
            onSave = { saved ->
                lib = lib.copy(
                    outfits = lib.outfits.toMutableList().apply {
                        val i = indexOfFirst { it.id == saved.id }
                        if (i >= 0) this[i] = saved else add(saved)
                    },
                )
                editOutfit = null
            },
        )
    }
}

@Composable
private fun CharacterEditDialog(
    initial: CharacterPreset,
    outfits: List<OutfitPreset>,
    onDismiss: () -> Unit,
    onSave: (CharacterPreset) -> Unit,
) {
    var c by remember { mutableStateOf(initial) }
    var aliasText by remember { mutableStateOf(initial.aliases.joinToString(", ")) }
    var outfitMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑角色") },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(c.copy(aliases = aliasText.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }))
                },
                enabled = c.name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = c.name,
                    onValueChange = { c = c.copy(name = it) },
                    label = { Text("中文名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = c.englishName,
                    onValueChange = { c = c.copy(englishName = it) },
                    label = { Text("英文名 (可选)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliasText,
                    onValueChange = { aliasText = it },
                    label = { Text("别名（用逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1, maxLines = 3,
                )
                OutlinedTextField(
                    value = c.appearancePrompt,
                    onValueChange = { c = c.copy(appearancePrompt = it) },
                    label = { Text("外观 prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 6,
                )
                Box {
                    OutlinedButton(
                        onClick = { outfitMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "默认装束：" + (
                                outfits.find { it.id == c.defaultOutfitId }?.name ?: "(无)"
                            ),
                        )
                    }
                    DropdownMenu(
                        expanded = outfitMenuOpen,
                        onDismissRequest = { outfitMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("(无)") },
                            onClick = {
                                c = c.copy(defaultOutfitId = null)
                                outfitMenuOpen = false
                            },
                        )
                        outfits.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.name) },
                                onClick = {
                                    c = c.copy(defaultOutfitId = o.id)
                                    outfitMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = c.notes,
                    onValueChange = { c = c.copy(notes = it) },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1, maxLines = 3,
                )
            }
        },
    )
}

@Composable
private fun OutfitEditDialog(
    initial: OutfitPreset,
    onDismiss: () -> Unit,
    onSave: (OutfitPreset) -> Unit,
) {
    var o by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑装束") },
        confirmButton = {
            TextButton(
                onClick = { onSave(o) },
                enabled = o.name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = o.name,
                    onValueChange = { o = o.copy(name = it) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = o.prompt,
                    onValueChange = { o = o.copy(prompt = it) },
                    label = { Text("装束 prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 8,
                )
                OutlinedTextField(
                    value = o.notes,
                    onValueChange = { o = o.copy(notes = it) },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1, maxLines = 3,
                )
            }
        },
    )
}

// ----------------------------------------------------------------------- 导入 tab

@Composable
private fun ImportTab(onImport: (String, (Result<String>) -> Unit) -> Unit) {
    // 整个 settings.json 可能有数 MB，绝对不能放进 OutlinedTextField（会卡死）。
    // 用 fileJson 存"从文件加载的完整内容"，pasteText 用于手动粘贴的小段。
    var fileJson by remember { mutableStateOf<String?>(null) }
    var pasteText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var pickedSize by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8) ?: ""
            }.onSuccess { content ->
                fileJson = content
                pickedSize = content.length
                pickedName = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (i >= 0) c.getString(i) else null
                        } else null
                    }
                }.getOrNull() ?: uri.lastPathSegment
                status = null
            }.onFailure {
                status = "❌ 读取失败：${it.message}"
            }
        }
    }

    val effectiveJson = fileJson ?: pasteText
    val canImport = effectiveJson.isNotBlank() && !importing

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            "支持两种方式：从文件选择 settings.json（推荐，几 MB 也没问题），" +
                "或在下方文本框直接粘贴一小段 JSON。" +
                "导入会覆盖当前后端、模型、采样、角色 / 装束、ComfyUI 工作流等。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    pickFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("选择文件")
            }
            if (fileJson != null || pasteText.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        fileJson = null
                        pasteText = ""
                        pickedName = null
                        pickedSize = 0
                        status = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("清空")
                }
            }
        }
        if (fileJson != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "📄 ${pickedName ?: "(未命名)"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "大小：${formatBytes(pickedSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val preview = remember(fileJson) {
                        fileJson?.take(300)?.replace("\n", " ")?.let {
                            if ((fileJson?.length ?: 0) > 300) "$it…" else it
                        } ?: ""
                    }
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("或在此粘贴 JSON 文本") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                minLines = 5, maxLines = 12,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        Button(
            onClick = {
                importing = true
                status = null
                onImport(effectiveJson) { result ->
                    importing = false
                    status = result.fold(
                        onSuccess = { "✅ $it" },
                        onFailure = { "❌ ${it.message}" },
                    )
                }
            },
            enabled = canImport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(4.dp))
            } else {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.size(4.dp))
            }
            Text("导入")
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
