package com.zzy.llmreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zzy.llmreader.data.AppTheme
import com.zzy.llmreader.data.LlmPresets
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.data.ReaderFontFamily
import com.zzy.llmreader.data.ReaderPreferences
import com.zzy.llmreader.ui.theme.resolveFontFamily
import com.zzy.llmreader.web.CloudflareChallengeActivity

/**
 * 设置面板。
 *
 * 分组按**用户意图**而不是实现来源组织（旧版把「翻译目标语言」「术语表容量」
 * 这些全局翻译设置塞在「网页小说」标题下，用户根本找不到）：
 *
 *   ① 开始使用   必填三项 + 测试连接 —— 新用户打开就知道该做什么
 *   ② 翻译       目标语言 / 风格 / 分批 / 术语表
 *   ③ 阅读       主题 / 字体
 *   ④ 在线阅读   预取 + 各站点登录（默认折叠，需要时才展开）
 *
 * 其它针对旧版的改动：
 *  - API Key / cookie 一律遮蔽，带显示切换（旧版全明文）
 *  - Base URL 就地校验：漏 scheme、误把 /chat/completions 也填进来都会当场提示
 *  - 长说明收进可展开的「说明」，面板默认只留一行摘要
 *  - 有未保存改动时标题旁标「● 未保存」，点外部关闭会先确认
 *  - `remember` 带上 key，避免设置还在异步加载时打开面板、
 *    结果用空白覆盖掉已存好的配置
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    initial: LlmSettings,
    initialPrefs: ReaderPreferences,
    onDismiss: () -> Unit,
    onSave: (LlmSettings, ReaderPreferences) -> Unit,
    onFetchModels: (LlmSettings, (Result<List<String>>) -> Unit) -> Unit,
    onCloudflareCookieCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
    onScribblehubCookieCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
    onAo3CookieCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
) {
    // keyed remember：设置异步到达时字段会刷新，而不是保留空白
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var model by remember(initial) { mutableStateOf(initial.model) }

    var modelList by remember { mutableStateOf(emptyList<String>()) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var modelLoading by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var modelOk by remember { mutableStateOf<String?>(null) }

    var showApiKey by remember { mutableStateOf(false) }

    var prefetchAhead by remember(initialPrefs) {
        mutableStateOf(initialPrefs.webPrefetchAhead.toFloat())
    }
    var translationLanguage by remember(initialPrefs) { mutableStateOf(initialPrefs.translationLanguage) }
    var translationStyle by remember(initialPrefs) { mutableStateOf(initialPrefs.translationStyle) }
    var glossaryDedupeInterval by remember(initialPrefs) {
        mutableStateOf(initialPrefs.glossaryDedupeInterval.toFloat())
    }
    var glossaryMaxSize by remember(initialPrefs) { mutableStateOf(initialPrefs.glossaryMaxSize.toFloat()) }
    var translationBatchChars by remember(initialPrefs) {
        mutableStateOf(initialPrefs.translationBatchChars.toFloat())
    }
    var translationConcurrency by remember(initialPrefs) {
        mutableStateOf(initialPrefs.translationConcurrency.toFloat())
    }
    var pixivCookie by remember(initialPrefs) { mutableStateOf(initialPrefs.pixivPhpSessId) }
    var hamelnCookie by remember(initialPrefs) { mutableStateOf(initialPrefs.hamelnCookie) }
    var capturedHamelnUserAgent by remember(initialPrefs) { mutableStateOf(initialPrefs.hamelnUserAgent) }
    var scribblehubCookie by remember(initialPrefs) { mutableStateOf(initialPrefs.scribblehubCookie) }
    var capturedScribblehubUserAgent by remember(initialPrefs) {
        mutableStateOf(initialPrefs.scribblehubUserAgent)
    }
    var ao3Cookie by remember(initialPrefs) { mutableStateOf(initialPrefs.ao3Cookie) }
    var capturedAo3UserAgent by remember(initialPrefs) { mutableStateOf(initialPrefs.ao3UserAgent) }
    var appTheme by remember(initialPrefs) { mutableStateOf(initialPrefs.appTheme) }
    var readerFontFamily by remember(initialPrefs) { mutableStateOf(initialPrefs.readerFontFamily) }

    var expandedSiteGroup by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val ctx = LocalContext.current

    // ---- 校验 ----
    val baseUrlError: String? = when {
        baseUrl.isBlank() -> "必填"
        !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
            "要以 http:// 或 https:// 开头"
        baseUrl.trimEnd('/').endsWith("/chat/completions") ->
            "只填到 /v1 即可，不要带 /chat/completions"
        else -> null
    }
    val apiKeyMissing = apiKey.isBlank()

    fun buildSettings() = LlmSettings(baseUrl.trim(), apiKey.trim(), model.trim())
    fun buildPrefs() = initialPrefs.copy(
        webPrefetchAhead = prefetchAhead.toInt(),
        translationLanguage = translationLanguage.ifBlank {
            ReaderPreferences.DEFAULT_TRANSLATION_LANGUAGE
        },
        translationStyle = translationStyle,
        glossaryDedupeInterval = glossaryDedupeInterval.toInt(),
        glossaryMaxSize = glossaryMaxSize.toInt(),
        translationBatchChars = translationBatchChars.toInt(),
        translationConcurrency = translationConcurrency.toInt()
            .coerceIn(1, ReaderPreferences.MAX_TRANSLATION_CONCURRENCY),
        pixivPhpSessId = pixivCookie.trim(),
        hamelnCookie = hamelnCookie.trim(),
        hamelnUserAgent = capturedHamelnUserAgent.trim(),
        scribblehubCookie = scribblehubCookie.trim(),
        scribblehubUserAgent = capturedScribblehubUserAgent.trim(),
        ao3Cookie = ao3Cookie.trim(),
        ao3UserAgent = capturedAo3UserAgent.trim(),
        appTheme = appTheme,
        readerFontFamily = readerFontFamily,
    )

    val dirty = remember(
        baseUrl, apiKey, model, prefetchAhead, translationLanguage, translationStyle,
        glossaryDedupeInterval, glossaryMaxSize, translationBatchChars, translationConcurrency,
        pixivCookie, hamelnCookie, scribblehubCookie, ao3Cookie,
        capturedHamelnUserAgent, capturedScribblehubUserAgent, capturedAo3UserAgent,
        appTheme, readerFontFamily,
    ) {
        buildSettings() != initial || buildPrefs() != initialPrefs
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("你在设置里做了修改但还没保存。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }

    AlertDialog(
        onDismissRequest = { if (dirty) confirmDiscard = true else onDismiss() },
        confirmButton = {
            TextButton(
                onClick = { onSave(buildSettings(), buildPrefs()) },
                // 只在「填了但填错」时拦住保存。Base URL 留空是合法状态 ——
                // 只想看本地小说、不翻译的用户，不该被强迫先配 LLM 才能改主题。
                enabled = baseUrl.isBlank() || baseUrlError == null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { if (dirty) confirmDiscard = true else onDismiss() }) { Text("取消") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置")
                if (dirty) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "● 未保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                // ================= ① 开始使用 =================
                SectionTitle("① 开始使用", "这三项填好就能翻译了，其余都可以留空")

                // 先选服务商，自动填 Base URL —— 不该要求用户手打地址。
                // 之前 Base URL 是个空输入框，新用户不知道填什么；更常见的坑是
                // 把文档里那条完整地址粘进来（带 /chat/completions），
                // LlmClient 再拼一次就变成 .../chat/completions/chat/completions。
                val currentPreset = LlmPresets.match(baseUrl)
                var providerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                ) {
                    OutlinedTextField(
                        value = currentPreset.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("服务商") },
                        supportingText = {
                            val hint = currentPreset.keyHint
                            if (hint.isNotBlank()) Text(hint)
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        LlmPresets.allWithCustom.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(preset.displayName)
                                        if (!preset.custom) {
                                            Text(
                                                preset.baseUrl,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    providerExpanded = false
                                    if (preset.custom) {
                                        // 自定义：清空让用户自己填（保留已填内容更友好）
                                        if (LlmPresets.match(baseUrl) != LlmPresets.CUSTOM) baseUrl = ""
                                    } else {
                                        baseUrl = preset.baseUrl
                                        // 模型栏为空、或还停在别的服务商的推荐模型上时，一并替换
                                        val isStaleSuggestion = LlmPresets.all
                                            .any { model in it.suggestedModels }
                                        if (model.isBlank() || isStaleSuggestion) {
                                            preset.suggestedModels.firstOrNull()?.let { model = it }
                                        }
                                        // 换了服务商，之前拉到的模型列表作废
                                        modelList = emptyList()
                                        modelOk = null
                                        modelError = null
                                    }
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://open.bigmodel.cn/api/paas/v4") },
                    isError = baseUrlError != null,
                    supportingText = {
                        Text(
                            baseUrlError
                                ?: "填到 /v1 为止，不要带 /chat/completions（选服务商会自动填）",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    isError = apiKeyMissing,
                    supportingText = { if (apiKeyMissing) Text("必填：没有密钥无法调用任何模型") },
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showApiKey) "隐藏密钥" else "显示密钥",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { if (modelList.isNotEmpty()) modelDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        placeholder = { Text("glm-4-flash") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        singleLine = true,
                        trailingIcon = {
                            if (modelList.isNotEmpty()) {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                            }
                        },
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false },
                    ) {
                        modelList.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                onClick = {
                                    model = m
                                    modelDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // 测试连接：旧版把唯一的验证入口藏成一个没有文字标签的云朵图标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            modelLoading = true
                            modelError = null
                            modelOk = null
                            onFetchModels(buildSettings()) { result ->
                                modelLoading = false
                                result.onSuccess { list ->
                                    modelList = list
                                    modelOk = if (list.isEmpty()) {
                                        "连接成功，但该地址没有返回任何模型；请手动填 Model"
                                    } else {
                                        "连接成功，拉到 ${list.size} 个模型"
                                    }
                                    if (model.isBlank() && list.isNotEmpty()) model = list.first()
                                }.onFailure { err ->
                                    modelError = err.message ?: "连接失败"
                                }
                            }
                        },
                        enabled = !modelLoading && baseUrlError == null && !apiKeyMissing,
                    ) {
                        if (modelLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("测试连接并拉取模型")
                    }
                }
                modelOk?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                modelError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "常见原因：Base URL 填多了 /chat/completions、密钥无效、或该地址不支持 /models 接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionDivider()

                // ================= ② 翻译 =================
                SectionTitle("② 翻译", "本地书和网页小说共用这套设置")
                OutlinedTextField(
                    value = translationLanguage,
                    onValueChange = { translationLanguage = it },
                    label = { Text("目标语言") },
                    placeholder = { Text("简体中文 / English / 日本語") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = translationStyle,
                    onValueChange = { translationStyle = it },
                    label = { Text("风格说明（可选）") },
                    placeholder = { Text("口语化 / 轻小说风格 / 保留敬语") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                SliderRow(
                    label = "分批大小",
                    valueText = "${translationBatchChars.toInt()} 字符/批" +
                        if (translationBatchChars.toInt() == ReaderPreferences.DEFAULT_TRANSLATION_BATCH_CHARS) {
                            "（推荐）"
                        } else "",
                ) {
                    Slider(
                        value = translationBatchChars,
                        onValueChange = { translationBatchChars = it },
                        valueRange = ReaderPreferences.MIN_TRANSLATION_BATCH_CHARS.toFloat()
                            ..ReaderPreferences.MAX_TRANSLATION_BATCH_CHARS.toFloat(),
                        steps = 22,
                    )
                }
                CollapsibleHelp("分批大小怎么选？") {
                    "每次发给 LLM 的最大字符数。越大请求次数越少（更便宜、上下文更连贯），" +
                        "但模型输出 token 上限一旦被压缩，末尾段落会被截断。\n\n" +
                        "输出不限长的模型（GPT-4o / Claude / Gemini-2.5-pro）可以到 8000+；" +
                        "低成本模型（DeepSeek / GLM-Flash / Qwen-Turbo）建议 3000–4000。"
                }
                SliderRow(
                    label = "并发的批次请求数",
                    valueText = if (translationConcurrency.toInt() <= 1) {
                        "串行（推荐先用这个）"
                    } else {
                        "${translationConcurrency.toInt()} 路并发"
                    },
                ) {
                    Slider(
                        value = translationConcurrency,
                        onValueChange = { translationConcurrency = it },
                        valueRange = 1f..ReaderPreferences.MAX_TRANSLATION_CONCURRENCY.toFloat(),
                        steps = ReaderPreferences.MAX_TRANSLATION_CONCURRENCY - 2,
                    )
                }
                CollapsibleHelp("要不要开并发？") {
                    "默认串行：一次只发一个批次请求。\n\n" +
                        "调到 2–4 后，允许后一批与前一批同时在跑，整章完成时间可缩短近一半" +
                        "（实测 4 批 3 路并发：852ms → 422ms）。\n\n" +
                        "代价：第 k 批的\"前文译文\"取自更早的一批，人物指代与称谓的衔接会比串行略弱。" +
                        "另一个前提是你的 API 地址允许并发——很多按 Key 限流的服务商在并发时会返回 429，" +
                        "遇到这种情况请调回串行。"
                }
                SliderRow(
                    label = "术语表智能去重",
                    valueText = if (glossaryDedupeInterval.toInt() == 0) {
                        "关闭（推荐 5）"
                    } else {
                        "每 ${glossaryDedupeInterval.toInt()} 批一次"
                    },
                ) {
                    Slider(
                        value = glossaryDedupeInterval,
                        onValueChange = { glossaryDedupeInterval = it },
                        valueRange = 0f..20f,
                        steps = 19,
                    )
                }
                CollapsibleHelp("术语表是什么？") {
                    "翻译时自动积累的人名/专有名词对照表，用来保证全书译名一致。" +
                        "每翻 N 批让 LLM 合并一次简称、异写和重复译名；0 表示关闭。"
                }
                SliderRow(label = "术语表容量", valueText = "${glossaryMaxSize.toInt()} 条") {
                    Slider(
                        value = glossaryMaxSize,
                        onValueChange = { glossaryMaxSize = it },
                        valueRange = 20f..200f,
                        steps = 17,
                    )
                }
                CollapsibleHelp("容量超了会怎样？") {
                    "超过上限时自动淘汰最久没被命中的条目（LRU）。" +
                        "长篇小说人物多可以调大，短篇调小即可。"
                }

                SectionDivider()

                // ================= ③ 阅读 =================
                SectionTitle("③ 阅读", null)
                Text("应用主题", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppTheme.SYSTEM to "跟随系统",
                        AppTheme.LIGHT to "白天",
                        AppTheme.DARK to "夜间",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = appTheme == value,
                            onClick = { appTheme = value },
                            label = { Text(label) },
                        )
                    }
                }
                Text("正文字体", style = MaterialTheme.typography.bodyMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReaderFontFamily.entries.forEach { ff ->
                        FilterChip(
                            selected = readerFontFamily == ff,
                            onClick = { readerFontFamily = ff },
                            label = { Text(ff.displayName, fontFamily = resolveFontFamily(ff)) },
                        )
                    }
                }

                SectionDivider()

                // ================= ④ 在线阅读 =================
                SectionTitle("④ 在线阅读", "只用本地文件的话，这一节可以完全不管")
                SliderRow(
                    label = "后台预取",
                    valueText = if (prefetchAhead.toInt() == 0) "关闭" else "${prefetchAhead.toInt()} 章",
                ) {
                    Slider(
                        value = prefetchAhead,
                        onValueChange = { prefetchAhead = it },
                        valueRange = 0f..ReaderPreferences.MAX_PREFETCH_AHEAD.toFloat(),
                        steps = ReaderPreferences.MAX_PREFETCH_AHEAD - 1,
                    )
                }
                CollapsibleHelp("预取是做什么的？") {
                    "翻到一章后，在后台把接下来的 N 章静默下载好，翻页时就不用等。" +
                        "设为 0 关闭；数字越大越流畅，但更费流量和存储。"
                }

                Text(
                    "站点登录（按需，默认不需要）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "只有遇到「需要登录」或 Cloudflare 拦截时才需要填。不填也能用，只是部分内容拉不到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- Pixiv ----
                val pixivCtx = ctx
                val pixivLoginLauncher = rememberLauncherForActivityResult(
                    CloudflareChallengeActivity.Contract(),
                ) { result ->
                    if (result != null) {
                        val phpSessId = Regex("PHPSESSID=([^;\\s]+)").find(result.cookie)
                            ?.groupValues?.getOrNull(1)?.trim().orEmpty()
                        if (phpSessId.isNotBlank()) {
                            pixivCookie = phpSessId
                            android.widget.Toast.makeText(
                                pixivCtx,
                                "已拿到 PHPSESSID（${phpSessId.length} 字符）",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                pixivCtx,
                                "返回的 cookie 里找不到 PHPSESSID，请确认已成功登录",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                SiteGroup(
                    title = "Pixiv",
                    summary = if (pixivCookie.isBlank()) "未登录（只能看全年龄）" else "已填 PHPSESSID",
                    expanded = expandedSiteGroup == "pixiv",
                    onToggle = { expandedSiteGroup = if (expandedSiteGroup == "pixiv") null else "pixiv" },
                ) {
                    OutlinedTextField(
                        value = pixivCookie,
                        onValueChange = { pixivCookie = it.trim() },
                        label = { Text("PHPSESSID") },
                        placeholder = { Text("123456_AbCdEfG...") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row {
                        TextButton(onClick = {
                            pixivLoginLauncher.launch(
                                CloudflareChallengeActivity.Input(
                                    url = "https://accounts.pixiv.net/login?return_to=https%3A%2F%2Fwww.pixiv.net%2F",
                                    userAgent = "",
                                    targetCookieName = "PHPSESSID",
                                    titleHint = "请登录 Pixiv 账号；登录成功后会自动返回 PHPSESSID。\n" +
                                        "如果之前登录过，可以直接打开任意 pixiv 页面后点底部「完成」。",
                                ),
                            )
                        }) { Text("打开网页登录，自动获取") }
                    }
                    CollapsibleHelp("怎么手动获取？") {
                        "电脑浏览器登录 pixiv.net → F12 → Application → Cookies → 复制 PHPSESSID。\n" +
                            "填了才能拉取 R-18 / 多页 / 系列作品。Cookie 只存在本机，不会上传给 LLM 服务。"
                    }
                }

                // ---- Hameln ----
                SiteGroup(
                    title = "Hameln (syosetu.org)",
                    summary = if (hamelnCookie.isBlank()) "未填（遇 Cloudflare 会失败）" else "已填 cookie",
                    expanded = expandedSiteGroup == "hameln",
                    onToggle = { expandedSiteGroup = if (expandedSiteGroup == "hameln") null else "hameln" },
                ) {
                    SiteCredentialFields(
                        cookie = hamelnCookie,
                        onCookieChange = { hamelnCookie = it },
                        userAgent = capturedHamelnUserAgent,
                        onUserAgentChange = { capturedHamelnUserAgent = it },
                        onCaptured = { cookie, ua ->
                            hamelnCookie = cookie
                            capturedHamelnUserAgent = ua
                            onCloudflareCookieCaptured(cookie, ua)
                        },
                        challengeUrl = "https://syosetu.org/",
                        titleHint = "完成 Cloudflare 验证后会自动返回。",
                    )
                }

                // ---- ScribbleHub ----
                SiteGroup(
                    title = "ScribbleHub",
                    summary = if (scribblehubCookie.isBlank()) "未填（遇 Cloudflare 会失败）" else "已填 cookie",
                    expanded = expandedSiteGroup == "scribblehub",
                    onToggle = {
                        expandedSiteGroup = if (expandedSiteGroup == "scribblehub") null else "scribblehub"
                    },
                ) {
                    SiteCredentialFields(
                        cookie = scribblehubCookie,
                        onCookieChange = { scribblehubCookie = it },
                        userAgent = capturedScribblehubUserAgent,
                        onUserAgentChange = { capturedScribblehubUserAgent = it },
                        onCaptured = { cookie, ua ->
                            scribblehubCookie = cookie
                            capturedScribblehubUserAgent = ua
                            onScribblehubCookieCaptured(cookie, ua)
                        },
                        challengeUrl = "https://www.scribblehub.com/",
                        titleHint = "完成 Cloudflare 验证后会自动返回。",
                    )
                }

                // ---- AO3 ----
                SiteGroup(
                    title = "AO3",
                    summary = if (ao3Cookie.isBlank()) "未填（遇 Cloudflare 会失败）" else "已填 cookie",
                    expanded = expandedSiteGroup == "ao3",
                    onToggle = { expandedSiteGroup = if (expandedSiteGroup == "ao3") null else "ao3" },
                ) {
                    SiteCredentialFields(
                        cookie = ao3Cookie,
                        onCookieChange = { ao3Cookie = it },
                        userAgent = capturedAo3UserAgent,
                        onUserAgentChange = { capturedAo3UserAgent = it },
                        onCaptured = { cookie, ua ->
                            ao3Cookie = cookie
                            capturedAo3UserAgent = ua
                            onAo3CookieCaptured(cookie, ua)
                        },
                        challengeUrl = "https://archiveofourown.org/",
                        titleHint = "完成 Cloudflare 验证后会自动返回。",
                    )
                }
            }
        },
    )
}

// ---------------------------------------------------------------- 小组件

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
}

/** 滑块 + 左侧标题右侧当前值，避免旧版「标题在上、值写在标题里」的重复。 */
@Composable
private fun SliderRow(label: String, valueText: String, slider: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        slider()
    }
}

/** 长说明默认收起，点一下才展开 —— 旧版把五行说明全铺在面板里，等于没人看。 */
@Composable
private fun CollapsibleHelp(title: String, body: () -> String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = if (expanded) "▾ $title" else "▸ $title",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        )
        if (expanded) {
            Text(
                body(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 站点凭据折叠组：默认只显示一行状态，展开才看到输入框。 */
@Composable
private fun SiteGroup(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // clickable 只挂在标题行：挂到整个 Surface 会和展开后的输入框抢点击，
            // 可能出现「点输入框反而把分组收起来」。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

/**
 * 一组站点凭据输入。
 *
 * UA 字段放在「高级」里默认隐藏 —— 它是过 Cloudflare 时自动写入的，
 * 旧版把它和 cookie 并排做成普通输入框，用户会以为必须手填。
 */
@Composable
private fun SiteCredentialFields(
    cookie: String,
    onCookieChange: (String) -> Unit,
    userAgent: String,
    onUserAgentChange: (String) -> Unit,
    onCaptured: (cookie: String, userAgent: String) -> Unit,
    challengeUrl: String,
    titleHint: String,
) {
    val launcher = rememberLauncherForActivityResult(
        CloudflareChallengeActivity.Contract(),
    ) { result ->
        if (result != null) onCaptured(result.cookie, result.userAgent)
    }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = cookie,
            onValueChange = onCookieChange,
            label = { Text("cf_clearance cookie") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row {
            TextButton(onClick = {
                launcher.launch(
                    CloudflareChallengeActivity.Input(
                        url = challengeUrl,
                        userAgent = "",
                        titleHint = titleHint,
                    ),
                )
            }) { Text("打开网页自动过验证") }
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "收起高级" else "高级")
            }
        }
        if (showAdvanced) {
            OutlinedTextField(
                value = userAgent,
                onValueChange = onUserAgentChange,
                label = { Text("User-Agent（自动填写）") },
                supportingText = { Text("验证时自动获取，cf_clearance 与它绑定，一般不用改") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}
