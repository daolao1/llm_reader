package com.zzy.llmreader.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.data.ReaderPreferences
import com.zzy.llmreader.data.ReadingTheme
import com.zzy.llmreader.data.RecentBook
import com.zzy.llmreader.data.RecentBookSource
import com.zzy.llmreader.data.TranslationMode
import com.zzy.llmreader.format.FormatDetector
import com.zzy.llmreader.model.BookContent
import com.zzy.llmreader.ui.theme.ReaderThemeOverride
import com.zzy.llmreader.web.WebSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(
    initialUrl: String? = null,
    viewModel: ReaderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val recents by viewModel.recents.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showDiscovery by remember { mutableStateOf(false) }
    // 从发现页点进某本书后记一下，退出该书时自动回到发现页。
    var returnToDiscovery by remember { mutableStateOf(false) }
    var showGlossary by remember { mutableStateOf(false) }
    var showImagePanel by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val snackbarHost = remember { SnackbarHostState() }

    // CF 拦截后自动弹验证 Activity；拿到 cookie 后 ViewModel 自动重试。
    val cfAutoLauncher = rememberLauncherForActivityResult(
        com.zzy.llmreader.web.CloudflareChallengeActivity.Contract(),
    ) { result ->
        if (result != null) {
            viewModel.onCloudflareCookieResolved(result.cookie, result.userAgent)
        } else {
            viewModel.dismissCloudflareChallenge()
        }
    }
    LaunchedEffect(state.pendingCloudflareUrl) {
        val url = state.pendingCloudflareUrl
        if (!url.isNullOrBlank()) {
            cfAutoLauncher.launch(
                com.zzy.llmreader.web.CloudflareChallengeActivity.Input(
                    url = url,
                    userAgent = "",
                ),
            )
        }
    }

    // 返回键处理：优先关掉打开的子对话框 → 关闭当前书 → 否则交给系统（退出应用）。
    BackHandler(
        enabled = showSettings || showUrlDialog || showGlossary || showImagePanel
            || showDiscovery
            || state.bookDetail != null
            || state.ask.visible || state.book != null
    ) {
        when {
            showSettings -> showSettings = false
            showImagePanel -> showImagePanel = false
            showGlossary -> showGlossary = false
            showUrlDialog -> showUrlDialog = false
            // 详情独立页（覆盖在 Discovery / 书库 上层）
            state.bookDetail != null -> viewModel.dismissBookDetail()
            // 旧的详情弹窗（已废弃，但兜底处理）
            showDiscovery && state.discovery.detailItemId != null -> viewModel.dismissDiscoveryDetail()
            showDiscovery -> showDiscovery = false
            state.ask.visible -> viewModel.hideAsk()
            state.book != null -> {
                val backToDisc = returnToDiscovery
                returnToDiscovery = false
                // 先把发现页对话框打开，再关闭书，避免书库主页一闪。
                if (backToDisc) showDiscovery = true
                viewModel.closeBook()
            }
        }
    }
    val currentRecent = remember(state.book, recents) {
        val book = state.book ?: return@remember null
        recents.firstOrNull { it.title == book.title }
    }
    val isWebBook = state.webCanonicalUrl != null ||
        currentRecent?.source == RecentBookSource.WEB ||
        (currentRecent?.uri?.startsWith("http", ignoreCase = true) == true)
    // 打开/关闭书时确保 chrome 可见
    LaunchedEffect(state.book?.title) { chromeVisible = true }
    // 从发现页点开书后，等书真的加载出来再关掉发现页，避免主页闪一下。
    LaunchedEffect(state.book) {
        if (state.book != null && showDiscovery) showDiscovery = false
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.openBook(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            viewModel.openWebBook(initialUrl)
        }
    }

    LaunchedEffect(state.error) {
        val msg = state.error ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        viewModel.dismissError()
    }

    com.zzy.llmreader.ui.theme.LlmReaderTheme(
        darkTheme = com.zzy.llmreader.ui.theme.resolveDark(state.readerPrefs.appTheme),
        fontFamily = state.readerPrefs.readerFontFamily,
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            // 阅读时支持隐藏顶栏（沉浸式）；书库 / 加载页始终显示
            val showChrome = state.book == null || chromeVisible
            AnimatedVisibility(
                visible = showChrome,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
                TopAppBar(
                title = {
                    Text(
                        text = state.book?.title ?: "LLM Reader",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (state.book != null) {
                        TextButton(onClick = {
                            // \u201c\u4e66\u5e93\"\u6309\u94ae\u660e\u786e\u662f\u53bb\u4e66\u5e93\uff0c\u4e0d\u56de\u53d1\u73b0\u9875\uff1b\u53d1\u73b0\u8fd4\u56de\u53ea\u9760\u7cfb\u7edf back\u3002\n                            returnToDiscovery = false\n                            viewModel.closeBook()
                        }) { Text("书库") }
                    }
                },
                actions = {
                    if (state.book == null) {
                        IconButton(onClick = { showDiscovery = true }) {
                            Icon(Icons.Default.Search, contentDescription = "浏览网站")
                        }
                        IconButton(onClick = { showUrlDialog = true }) {
                            Icon(Icons.Default.Link, contentDescription = "添加网页小说")
                        }
                    } else {
                        TranslationModeMenu(
                            current = state.readerPrefs.translationMode,
                            enabled = !state.loading,
                            onSelect = viewModel::setTranslationMode,
                        )
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("查看详情") },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.openBookDetailForCurrent()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("图片生成") },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        showImagePanel = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("术语表") },
                                    leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        showGlossary = true
                                    },
                                )
                                val isOriginalMode = state.readerPrefs.translationMode == TranslationMode.ORIGINAL
                                DropdownMenuItem(
                                    text = { Text("重新翻译本章") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    enabled = !state.loading
                                        && state.currentChapter !in state.translatingChapters
                                        && !isOriginalMode,
                                    onClick = {
                                        showOverflow = false
                                        viewModel.retranslateCurrentChapter()
                                    },
                                )
                                if (isWebBook) {
                                    DropdownMenuItem(
                                        text = { Text("重新下载本章") },
                                        leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                                        enabled = !state.loading && isOriginalMode,
                                        onClick = {
                                            showOverflow = false
                                            viewModel.refreshCurrentWebBook()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
            }
        },
        floatingActionButton = {
            if (state.book == null) {
                ExtendedFloatingActionButton(
                    onClick = { openLauncher.launch(FormatDetector.openableMimeTypes) },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    text = { Text("打开文件") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(padding, state.loadingText)
            state.book == null -> LibraryScreen(
                padding = padding,
                recents = recents,
                onPick = { openLauncher.launch(FormatDetector.openableMimeTypes) },
                onAddWebBook = { showUrlDialog = true },
                onOpenRecent = viewModel::openRecent,
                onRemoveRecent = viewModel::removeRecent,
                onShowDetail = viewModel::openBookDetailFromRecent,
            )
            else -> ReaderThemeOverride(
                state.readerPrefs.readingTheme,
                fontFamily = state.readerPrefs.readerFontFamily,
            ) {
                val inlineHandlers = remember(
                    state.imageGenSettings.enableInlineImages,
                    state.imageGenSettings.backend,
                    state.generatingImage,
                ) {
                    InlineImageHandlers(
                        enabled = state.imageGenSettings.enableInlineImages
                            && state.imageGenSettings.backend != com.zzy.llmreader.data.ImageBackend.NONE,
                        findByPrompt = { p -> viewModel.findCachedImageByPrompt(p) },
                        generate = viewModel::generateInlineImage,
                        generating = state.generatingImage,
                        generateAll = { prompts, onProgress, onDone ->
                            viewModel.generateAllInlinePrompts(prompts, onProgress, onDone)
                        },
                    )
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalInlineImageHandlers provides inlineHandlers,
                ) {
                    ReaderScreen(
                        padding = padding,
                        state = state,
                        chromeVisible = chromeVisible,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        onCycleTheme = viewModel::cycleReadingTheme,
                        onSelectChapter = viewModel::selectChapter,
                        onSelectChapterAtEnd = viewModel::selectChapterAtEnd,
                        onSetFontScale = viewModel::setFontScale,
                        onAskClicked = viewModel::showAsk,
                        onRetryTranslation = viewModel::retryChapterTranslation,
                        onScrollFractionChange = viewModel::setChapterScrollFraction,
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = state.settings,
            initialPrefs = state.readerPrefs,
            onDismiss = { showSettings = false },
            onSave = { llm, prefs ->
                viewModel.saveLlmSettings(llm)
                viewModel.saveReaderPrefs(prefs)
                showSettings = false
            },
            onFetchModels = viewModel::fetchModels,
            onCloudflareCookieCaptured = { cookie, ua ->
                viewModel.saveReaderPrefs(
                    state.readerPrefs.copy(
                        hamelnCookie = cookie.trim(),
                        hamelnUserAgent = ua.trim(),
                    ),
                )
            },
            onScribblehubCookieCaptured = { cookie, ua ->
                viewModel.saveReaderPrefs(
                    state.readerPrefs.copy(
                        scribblehubCookie = cookie.trim(),
                        scribblehubUserAgent = ua.trim(),
                    ),
                )
            },
            onAo3CookieCaptured = { cookie, ua ->
                viewModel.saveReaderPrefs(
                    state.readerPrefs.copy(
                        ao3Cookie = cookie.trim(),
                        ao3UserAgent = ua.trim(),
                    ),
                )
            },
        )
    }

    if (showGlossary) {
        GlossaryDialog(
            entries = viewModel.getGlossary(),
            onDismiss = { showGlossary = false },
            onSave = { entries ->
                viewModel.saveGlossary(entries)
                showGlossary = false
            },
            onDedupe = { callback -> viewModel.deduplicateGlossaryManual(callback) },
        )
    }

    if (showUrlDialog) {
        OpenUrlDialog(
            sources = viewModel.supportedSites(),
            onDismiss = { showUrlDialog = false },
            onOpen = { url ->
                showUrlDialog = false
                viewModel.openWebBook(url)
            },
        )
    }

    if (showDiscovery) {
        DiscoveryScreen(
            state = state.discovery,
            sites = viewModel.discoverableSites(),
            onDismiss = { showDiscovery = false },
            onSelectSite = viewModel::selectDiscoverySite,
            onUpdateQuery = { transform -> viewModel.updateDiscoveryQuery(transform) },
            onLoadMore = viewModel::loadMoreDiscovery,
            onTranslateToggle = viewModel::setDiscoveryTranslate,
            onRetranslateAll = viewModel::retranslateDiscoveryAll,
            onOpenItem = { id ->
                // 保持发现页打开，等书加载好（state.book != null）后再关，避免闪一下主页。
                returnToDiscovery = true
                viewModel.openDiscoveryItem(id)
            },
            onBackToSitePicker = viewModel::clearDiscovery,
            onScrollChange = viewModel::setDiscoveryScroll,
            onShowDetail = { id -> viewModel.openBookDetail(id) },
            onDismissDetail = viewModel::dismissDiscoveryDetail,
        )
    }

    state.bookDetail?.let { detail ->
        val context = LocalContext.current
        BookDetailScreen(
            detail = detail,
            onBack = viewModel::dismissBookDetail,
            onStartReading = {
                returnToDiscovery = showDiscovery
                viewModel.openBookDetailChapter(0)
            },
            onOpenChapter = { idx ->
                returnToDiscovery = showDiscovery
                viewModel.openBookDetailChapter(idx)
            },
            onOpenSimilar = { id ->
                viewModel.openBookDetail(id, sourceId = detail.sourceId)
            },
            onOpenSiteUrl = { url ->
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            },
            onTagClick = { tag ->
                viewModel.dismissBookDetail()
                if (!showDiscovery) showDiscovery = true
                viewModel.updateDiscoveryQuery {
                    val clean = tag.trim().trimStart('#').trim()
                    if (clean.isBlank()) it
                    else {
                        val mergedTags = (it.tags + clean)
                            .distinctBy { t -> t.trim().trimStart('#').lowercase(java.util.Locale.ROOT) }
                            .take(8)
                        val normalizedTags = mergedTags
                            .map { t -> t.trim().trimStart('#').lowercase(java.util.Locale.ROOT) }
                            .toSet()
                        val cleanKeyword = it.keyword
                            .split(Regex("[\\s　]+"))
                            .filter { word ->
                                word.isNotBlank() &&
                                    word.trim().trimStart('#').lowercase(java.util.Locale.ROOT) !in normalizedTags
                            }
                            .joinToString(" ")
                        it.copy(keyword = cleanKeyword, tags = mergedTags)
                    }
                }
            },
        )
    }
    if (showImagePanel) {
        ImagePanelDialog(
            settings = state.imageGenSettings,
            library = state.characterLibrary,
            chapterImages = state.chapterImages,
            generating = state.generatingImage,
            error = state.imageGenError,
            imagePath = viewModel::imageFilePath,
            onDismiss = { showImagePanel = false },
            onSaveSettings = viewModel::saveImageGenSettings,
            onSaveLibrary = viewModel::saveCharacterLibrary,
            onGenerate = viewModel::generateImage,
            onExtractScene = viewModel::extractScenePrompt,
            onDeleteImage = viewModel::deleteGeneratedImage,
            onImportStChatu8 = viewModel::importStChatu8Settings,
            onClearError = viewModel::clearImageGenError,
            onLoadImagesIfNeeded = viewModel::refreshChapterImages,
        )
    }

    if (state.ask.visible) {
        AskDialog(
            ask = state.ask,
            onQuestion = viewModel::setQuestion,
            onSubmit = viewModel::ask,
            onDismiss = viewModel::hideAsk,
        )
    }
    } // end LlmReaderTheme
}

@Composable
private fun TranslationModeMenu(
    current: TranslationMode,
    enabled: Boolean,
    onSelect: (TranslationMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        TranslationMode.ORIGINAL -> "原文"
        TranslationMode.TRANSLATED -> "译文"
        TranslationMode.BILINGUAL -> "双语"
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(
                TranslationMode.ORIGINAL to "原文",
                TranslationMode.TRANSLATED to "译文",
                TranslationMode.BILINGUAL to "双语对照",
            ).forEach { (mode, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = {
                        if (mode == current) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadingBox(padding: PaddingValues, label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LibraryScreen(
    padding: PaddingValues,
    recents: List<RecentBook>,
    onPick: () -> Unit,
    onAddWebBook: () -> Unit,
    onOpenRecent: (RecentBook) -> Unit,
    onRemoveRecent: (RecentBook) -> Unit,
    onShowDetail: (RecentBook) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "本地格式：TXT · Markdown · HTML · EPUB · PDF\n网站支持：Literotica · Syosetu / Novel18 · AO3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        if (recents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "书库还是空的",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "选择本地文档或粘贴一个网页小说链接开始阅读",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(onClick = onAddWebBook) {
                            Icon(Icons.Default.Link, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("粘贴链接")
                        }
                        TextButton(onClick = onPick) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("选择文件")
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "书库",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${recents.size} 本",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recents, key = { it.uri }) { book ->
                    RecentItem(
                        book = book,
                        onClick = { onOpenRecent(book) },
                        onDelete = { onRemoveRecent(book) },
                        onShowDetail = { onShowDetail(book) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentItem(
    book: RecentBook,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShowDetail: () -> Unit = {},
) {
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isWeb = book.source == RecentBookSource.WEB || book.uri.startsWith("http", ignoreCase = true)
    val sourceLabel = if (isWeb) "网页缓存" else "本地文件"
    val sourceIcon = if (isWeb) Icons.Default.Language else Icons.Default.InsertDriveFile
    val hint = remember(book.uri) {
        if (isWeb) {
            runCatching { android.net.Uri.parse(book.uri).host.orEmpty() }.getOrDefault("")
        } else {
            android.net.Uri.parse(book.uri).lastPathSegment.orEmpty()
        }
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 渐变彩色"封面"+ 来源小图标徽章
            val palette = remember(book.title) { recentPalette(book.title) }
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 56.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(palette.first, palette.second),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    sourceIcon,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (hint.isNotBlank()) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (isWeb) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            sourceLabel,
                            color = if (isWeb) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        df.format(Date(book.openedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onShowDetail) {
                Icon(Icons.Default.Info, contentDescription = "查看详情")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "移除")
            }
        }
    }
}

private fun recentPalette(seed: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val palettes = listOf(
        androidx.compose.ui.graphics.Color(0xFF6A11CB) to androidx.compose.ui.graphics.Color(0xFF2575FC),
        androidx.compose.ui.graphics.Color(0xFFEE0979) to androidx.compose.ui.graphics.Color(0xFFFF6A00),
        androidx.compose.ui.graphics.Color(0xFF134E5E) to androidx.compose.ui.graphics.Color(0xFF71B280),
        androidx.compose.ui.graphics.Color(0xFFFF512F) to androidx.compose.ui.graphics.Color(0xFFDD2476),
        androidx.compose.ui.graphics.Color(0xFF1F4037) to androidx.compose.ui.graphics.Color(0xFF99F2C8),
        androidx.compose.ui.graphics.Color(0xFF373B44) to androidx.compose.ui.graphics.Color(0xFF4286F4),
        androidx.compose.ui.graphics.Color(0xFF8E2DE2) to androidx.compose.ui.graphics.Color(0xFF4A00E0),
    )
    val idx = kotlin.math.abs(seed.hashCode()) % palettes.size
    return palettes[idx]
}

@Composable
private fun OpenUrlDialog(
    sources: List<WebSource>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onOpen(url) },
                enabled = url.isNotBlank(),
            ) { Text("打开") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加网页小说") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("小说 URL") },
                    placeholder = { Text(sources.firstOrNull()?.sampleUrl ?: "https://...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            readClipboard(context)?.let { url = it }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "从剪贴板粘贴")
                        }
                    },
                )
                Text(
                    "支持站点（点击填入示例）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sources.forEach { source ->
                        AssistChip(
                            onClick = { url = source.sampleUrl },
                            label = { Text(source.displayName, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    return text?.trim()?.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    initial: LlmSettings,
    initialPrefs: ReaderPreferences,
    onDismiss: () -> Unit,
    onSave: (LlmSettings, ReaderPreferences) -> Unit,
    onFetchModels: (LlmSettings, (Result<List<String>>) -> Unit) -> Unit,
    onCloudflareCookieCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
    onScribblehubCookieCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
    onAo3CookieCaptured: (cookie: String, userAgent: String) -> Unit = { _, _ -> },
) {
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var modelList by remember { mutableStateOf(emptyList<String>()) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var modelLoading by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var prefetchAhead by remember {
        mutableStateOf(initialPrefs.webPrefetchAhead.toFloat())
    }
    var translationLanguage by remember { mutableStateOf(initialPrefs.translationLanguage) }
    var translationStyle by remember { mutableStateOf(initialPrefs.translationStyle) }
    var glossaryDedupeInterval by remember {
        mutableStateOf(initialPrefs.glossaryDedupeInterval.toFloat())
    }
    var glossaryMaxSize by remember {
        mutableStateOf(initialPrefs.glossaryMaxSize.toFloat())
    }
    var translationBatchChars by remember {
        mutableStateOf(initialPrefs.translationBatchChars.toFloat())
    }
    var pixivCookie by remember { mutableStateOf(initialPrefs.pixivPhpSessId) }
    var hamelnCookie by remember { mutableStateOf(initialPrefs.hamelnCookie) }
    var capturedHamelnUserAgent by remember { mutableStateOf(initialPrefs.hamelnUserAgent) }
    var scribblehubCookie by remember { mutableStateOf(initialPrefs.scribblehubCookie) }
    var capturedScribblehubUserAgent by remember { mutableStateOf(initialPrefs.scribblehubUserAgent) }
    var ao3Cookie by remember { mutableStateOf(initialPrefs.ao3Cookie) }
    var capturedAo3UserAgent by remember { mutableStateOf(initialPrefs.ao3UserAgent) }
    var appTheme by remember { mutableStateOf(initialPrefs.appTheme) }
    var readerFontFamily by remember { mutableStateOf(initialPrefs.readerFontFamily) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    LlmSettings(baseUrl, apiKey, model),
                    initialPrefs.copy(
                        webPrefetchAhead = prefetchAhead.toInt(),
                        translationLanguage = translationLanguage.ifBlank {
                            ReaderPreferences.DEFAULT_TRANSLATION_LANGUAGE
                        },
                        translationStyle = translationStyle,
                        glossaryDedupeInterval = glossaryDedupeInterval.toInt(),
                        glossaryMaxSize = glossaryMaxSize.toInt(),
                        translationBatchChars = translationBatchChars.toInt(),
                        pixivPhpSessId = pixivCookie.trim(),
                        hamelnCookie = hamelnCookie.trim(),
                        hamelnUserAgent = capturedHamelnUserAgent.trim(),
                        scribblehubCookie = scribblehubCookie.trim(),
                        scribblehubUserAgent = capturedScribblehubUserAgent.trim(),
                        ao3Cookie = ao3Cookie.trim(),
                        ao3UserAgent = capturedAo3UserAgent.trim(),
                        appTheme = appTheme,
                        readerFontFamily = readerFontFamily,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("LLM 配置", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = {
                        if (modelList.isNotEmpty()) modelDropdownExpanded = it
                    },
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        singleLine = true,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (modelList.isNotEmpty()) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                                }
                                IconButton(
                                    onClick = {
                                        modelLoading = true
                                        modelError = null
                                        onFetchModels(LlmSettings(baseUrl, apiKey, model)) { result ->
                                            modelLoading = false
                                            result.onSuccess { list ->
                                                modelList = list
                                                modelDropdownExpanded = list.isNotEmpty()
                                            }.onFailure { err ->
                                                modelError = err.message
                                            }
                                        }
                                    },
                                    enabled = !modelLoading && apiKey.isNotBlank(),
                                ) {
                                    if (modelLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.CloudDownload, contentDescription = "获取模型列表")
                                    }
                                }
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
                                    Text(
                                        m,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                },
                                onClick = {
                                    model = m
                                    modelDropdownExpanded = false
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                if (modelError != null) {
                    Text(
                        modelError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "智谱 GLM 默认 baseUrl: https://open.bigmodel.cn/api/paas/v4\n" +
                        "OpenAI 兼容: https://api.openai.com/v1",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text("外观", style = MaterialTheme.typography.titleSmall)
                Text("日间 / 夜间", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val options = listOf(
                        com.zzy.llmreader.data.AppTheme.SYSTEM to "跟随系统",
                        com.zzy.llmreader.data.AppTheme.LIGHT to "白天",
                        com.zzy.llmreader.data.AppTheme.DARK to "夜间",
                    )
                    options.forEach { (value, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = appTheme == value,
                            onClick = { appTheme = value },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "夜间会把整个 App（书库、发现页、设置）切到深色背景。阅读区内还可以单独循环切换 白天/护眼/夜间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("阅读字体", style = MaterialTheme.typography.bodyMedium)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.zzy.llmreader.data.ReaderFontFamily.entries.forEach { ff ->
                        androidx.compose.material3.FilterChip(
                            selected = readerFontFamily == ff,
                            onClick = { readerFontFamily = ff },
                            label = {
                                Text(
                                    ff.displayName,
                                    fontFamily = com.zzy.llmreader.ui.theme.resolveFontFamily(ff),
                                )
                            },
                        )
                    }
                }
                Text(
                    "字体仅影响正文与界面文字渲染。等宽字体适合代码/日文混排预览，衬线字体阅读更书卷气。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("网页小说", style = MaterialTheme.typography.titleSmall)
                Text(
                    "预取章节数：${prefetchAhead.toInt()} 章" +
                        if (prefetchAhead.toInt() == 0) "（关闭）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = prefetchAhead,
                    onValueChange = { prefetchAhead = it },
                    valueRange = 0f..ReaderPreferences.MAX_PREFETCH_AHEAD.toFloat(),
                    steps = ReaderPreferences.MAX_PREFETCH_AHEAD - 1,
                )
                Text(
                    "翻到一章后会在后台静默下载接下来的 N 章；设为 0 关闭预取。" +
                        "数字越大越流畅，但会占用流量和存储。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = translationLanguage,
                    onValueChange = { translationLanguage = it },
                    label = { Text("翻译目标语言") },
                    placeholder = { Text("例如：简体中文 / English / 日本語") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = translationStyle,
                    onValueChange = { translationStyle = it },
                    label = { Text("翻译风格说明（可选）") },
                    placeholder = { Text("例如：口语化 / 轻小说风格 / 正式文学 / 保留敬语") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
                Text(
                    "术语表智能去重间隔：${glossaryDedupeInterval.toInt()} 批" +
                        if (glossaryDedupeInterval.toInt() == 0) "（关闭）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = glossaryDedupeInterval,
                    onValueChange = { glossaryDedupeInterval = it },
                    valueRange = 0f..20f,
                    steps = 19,
                )
                Text(
                    "每翻译 N 批后用 LLM 对术语表做一次智能去重（合并简称/异写/统一译名）。" +
                        "设为 0 关闭。推荐 5。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "术语表容量上限：${glossaryMaxSize.toInt()} 条",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = glossaryMaxSize,
                    onValueChange = { glossaryMaxSize = it },
                    valueRange = 20f..200f,
                    steps = 17,
                )
                Text(
                    "超过上限时自动淘汰最久未命中的条目（LRU）。" +
                        "长篇小说人物多可调大，短篇可调小。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "翻译分批大小：${translationBatchChars.toInt()} 字符 / 批",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = translationBatchChars,
                    onValueChange = { translationBatchChars = it },
                    valueRange = ReaderPreferences.MIN_TRANSLATION_BATCH_CHARS.toFloat()
                        ..ReaderPreferences.MAX_TRANSLATION_BATCH_CHARS.toFloat(),
                    steps = 22, // 每 500 一档：500..12000 共 24 个位置
                )
                Text(
                    "每次发给 LLM 的最大字符数。越大请求次数越少（更便宜/上下文更连贯），" +
                        "但模型输出 token 上限一旦被压缩，末尾段落会被截断。\n" +
                        "调用 GPT-4o / Claude / Gemini-2.5-pro 类输出不限的模型可到8000+；" +
                        "DeepSeek / GLM-Flash / Qwen-Turbo 类低成本模型建议 3000–4000。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Pixiv 登录", style = MaterialTheme.typography.titleSmall)
                val pixivCtx = LocalContext.current
                OutlinedTextField(
                    value = pixivCookie,
                    onValueChange = { pixivCookie = it.trim() },
                    label = { Text("PHPSESSID") },
                    singleLine = true,
                    placeholder = { Text("123456_AbCdEfG...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val pixivLoginLauncher = rememberLauncherForActivityResult(
                    com.zzy.llmreader.web.CloudflareChallengeActivity.Contract(),
                ) { result ->
                    if (result != null) {
                        // 从 cookie 串里抽 PHPSESSID=xxx
                        val phpSessId = Regex("PHPSESSID=([^;\\s]+)").find(result.cookie)
                            ?.groupValues?.getOrNull(1)
                            ?.trim()
                            .orEmpty()
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
                TextButton(onClick = {
                    pixivLoginLauncher.launch(
                        com.zzy.llmreader.web.CloudflareChallengeActivity.Input(
                            url = "https://accounts.pixiv.net/login?return_to=https%3A%2F%2Fwww.pixiv.net%2F",
                            userAgent = "",
                            targetCookieName = "PHPSESSID",
                            titleHint = "请登录 Pixiv 账号；登录成功后会自动返回 PHPSESSID。\n如果之前登录过，可以直接打开任意 pixiv 页面后点底部「完成」。",
                        ),
                    )
                }) { Text("网页登录获取 PHPSESSID") }
                Text(
                    "用于抓取 Pixiv 小说（支持全年龄；填了 PHPSESSID 才能拉取 R-18 / 多页 / 系列）。\n" +
                        "推荐方式：点上面按钮在内嵌网页登录 pixiv 账号，会自动抓回 PHPSESSID。\n" +
                        "也可以手动：电脑浏览器登录 pixiv.net → F12 → Application → Cookies → 复制 PHPSESSID。\n" +
                        "Cookie 仅保存在本机，不会上传给 LLM 服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Hameln (syosetu.org) Cookie", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = hamelnCookie,
                    onValueChange = { hamelnCookie = it.trim() },
                    label = { Text("可选 cookie串") },
                    singleLine = true,
                    placeholder = { Text("cf_clearance=...; over18=off") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val ctx = LocalContext.current
                val cfLauncher = rememberLauncherForActivityResult(
                    com.zzy.llmreader.web.CloudflareChallengeActivity.Contract(),
                ) { result ->
                    if (result != null) {
                        hamelnCookie = result.cookie
                        capturedHamelnUserAgent = result.userAgent
                        // 立即持久化一份，免得用户以为点了没反应。
                        onCloudflareCookieCaptured(result.cookie, result.userAgent)
                        android.widget.Toast.makeText(
                            ctx,
                            "已拿到 cf_clearance（${result.cookie.length} 字符）",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            ctx,
                            "未拿到 cf_clearance，请手动完成验证后重试",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                TextButton(onClick = {
                    cfLauncher.launch(
                        com.zzy.llmreader.web.CloudflareChallengeActivity.Input(
                            url = "https://syosetu.org/novel/410483/",
                            // 留空 → 用 WebView 自带的真实 Chrome UA，更易过 CF。
                            userAgent = "",
                        ),
                    )
                }) { Text("自动通过 Cloudflare 验证") }
                Text(
                    "点上面按钮会用 WebView 打开 Hameln，过完 Cloudflare（必要时点验证码）后" +
                        "自动把 cf_clearance 和 UA 抓回来填到这里。\n" +
                        "如果你想手动粘贴：浏览器 → F12 → Application → Cookies → 复制整串。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("ScribbleHub Cookie", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = scribblehubCookie,
                    onValueChange = { scribblehubCookie = it.trim() },
                    label = { Text("可选 cookie串") },
                    singleLine = true,
                    placeholder = { Text("cf_clearance=...; ...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val shCfLauncher = rememberLauncherForActivityResult(
                    com.zzy.llmreader.web.CloudflareChallengeActivity.Contract(),
                ) { result ->
                    if (result != null) {
                        scribblehubCookie = result.cookie
                        capturedScribblehubUserAgent = result.userAgent
                        // 立即持久化一份。
                        onScribblehubCookieCaptured(result.cookie, result.userAgent)
                        android.widget.Toast.makeText(
                            ctx,
                            "已拿到 ScribbleHub cookie（${result.cookie.length} 字符）",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            ctx,
                            "未拿到 cf_clearance，请手动完成验证后重试",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                TextButton(onClick = {
                    shCfLauncher.launch(
                        com.zzy.llmreader.web.CloudflareChallengeActivity.Input(
                            url = "https://www.scribblehub.com/",
                            userAgent = "",
                        ),
                    )
                }) { Text("自动通过 ScribbleHub Cloudflare 验证") }
                Text(
                    "ScribbleHub 也挂在 Cloudflare 后面；点按钮在内嵌 WebView 完成验证后" +
                        "会自动抓回 cf_clearance 和 UA。\n" +
                        "示例：${com.zzy.llmreader.web.ScribbleHubSource().sampleUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("AO3 Cookie", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = ao3Cookie,
                    onValueChange = { ao3Cookie = it.trim() },
                    label = { Text("可选 cookie串") },
                    singleLine = true,
                    placeholder = { Text("cf_clearance=...; ...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val ao3CfLauncher = rememberLauncherForActivityResult(
                    com.zzy.llmreader.web.CloudflareChallengeActivity.Contract(),
                ) { result ->
                    if (result != null) {
                        ao3Cookie = result.cookie
                        capturedAo3UserAgent = result.userAgent
                        onAo3CookieCaptured(result.cookie, result.userAgent)
                        android.widget.Toast.makeText(
                            ctx,
                            "已拿到 AO3 cookie（${result.cookie.length} 字符）",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            ctx,
                            "未拿到 cf_clearance，请手动完成验证后重试",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                TextButton(onClick = {
                    ao3CfLauncher.launch(
                        com.zzy.llmreader.web.CloudflareChallengeActivity.Input(
                            url = "https://archiveofourown.org/",
                            userAgent = "",
                        ),
                    )
                }) { Text("自动通过 AO3 Cloudflare 验证") }
                Text(
                    "AO3 偶尔会用 Cloudflare 限流（HTTP 503/525）。点按钮在内嵌 WebView 中" +
                        "完成验证后会自动抓回 cf_clearance 和 UA，并自动重试上次失败的章节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "阅读时点顶栏「译」按钮可在 原文 / 译文 / 双语 三种模式之间切换；" +
                        "翻译会调用上面配置的 LLM，结果按章缓存，避免重复消耗。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun GlossaryDialog(
    entries: List<com.zzy.llmreader.web.GlossaryEntry>,
    onDismiss: () -> Unit,
    onSave: (List<com.zzy.llmreader.web.GlossaryEntry>) -> Unit,
    onDedupe: ((Result<List<com.zzy.llmreader.web.GlossaryEntry>>) -> Unit) -> Unit,
) {
    var items by remember {
        mutableStateOf(entries.map { it.original to it.translated })
    }
    var newOriginal by remember { mutableStateOf("") }
    var newTranslated by remember { mutableStateOf("") }
    var deduping by remember { mutableStateOf(false) }
    // 当前正在编辑的条目下标；null 表示无
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editOriginal by remember { mutableStateOf("") }
    var editTranslated by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(items.map { (o, t) -> com.zzy.llmreader.web.GlossaryEntry(o, t) })
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        deduping = true
                        onDedupe { result ->
                            deduping = false
                            result.onSuccess { dedupedEntries ->
                                items = dedupedEntries.map { it.original to it.translated }
                            }
                        }
                    },
                    enabled = !deduping && items.size > 1,
                ) {
                    if (deduping) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("智能去重")
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = { Text("术语表") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "翻译时模型会严格遵守此对照表。首章翻译后会自动生成，你也可以手动增删改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                items.forEachIndexed { idx, (orig, trans) ->
                    if (editingIndex == idx) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = editOriginal,
                                onValueChange = { editOriginal = it },
                                label = { Text("原文") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = editTranslated,
                                onValueChange = { editTranslated = it },
                                label = { Text("译文") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val o = editOriginal.trim()
                                    val t = editTranslated.trim()
                                    if (o.isNotBlank() && t.isNotBlank()) {
                                        items = items.toMutableList().apply { set(idx, o to t) }
                                    }
                                    editingIndex = null
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "完成", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { editingIndex = null },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingIndex = idx
                                    editOriginal = orig
                                    editTranslated = trans
                                },
                        ) {
                            Text(
                                "$orig → $trans",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = {
                                    editingIndex = idx
                                    editOriginal = orig
                                    editTranslated = trans
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = {
                                    items = items.toMutableList().apply { removeAt(idx) }
                                    if (editingIndex == idx) editingIndex = null
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("添加新条目", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = newOriginal,
                        onValueChange = { newOriginal = it },
                        label = { Text("原文") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newTranslated,
                        onValueChange = { newTranslated = it },
                        label = { Text("译文") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            if (newOriginal.isNotBlank() && newTranslated.isNotBlank()) {
                                items = items + (newOriginal.trim() to newTranslated.trim())
                                newOriginal = ""
                                newTranslated = ""
                            }
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                }
            }
        },
    )
}

@Composable
private fun AskDialog(
    ask: AskState,
    onQuestion: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !ask.loading) {
                Text(if (ask.loading) "生成中…" else "提问")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("询问大模型（基于当前章节）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ask.question,
                    onValueChange = onQuestion,
                    label = { Text("问题（留空则总结本章）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ask.error?.let {
                    Text("错误：$it", color = MaterialTheme.colorScheme.error)
                }
                if (ask.answer.isNotBlank()) {
                    Text("回答：", style = MaterialTheme.typography.titleSmall)
                    Text(ask.answer)
                }
            }
        },
    )
}

/** 给 Reader 屏幕用：判断是 PDF 还是富文本，分别分发。 */
@Composable
private fun ReaderScreen(
    padding: PaddingValues,
    state: ReaderUiState,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onCycleTheme: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onSelectChapterAtEnd: (Int) -> Unit = onSelectChapter,
    onSetFontScale: (Float) -> Unit,
    onAskClicked: () -> Unit,
    onRetryTranslation: (Int) -> Unit,
    onScrollFractionChange: (Int, Float) -> Unit = { _, _ -> },
) {
    when (val book = state.book) {
        is BookContent.Text -> TextReader(
            padding = padding,
            book = book,
            currentChapter = state.currentChapter,
            fontScale = state.fontScale,
            loadingChapters = state.loadingChapters,
            translatingChapters = state.translatingChapters,
            translationProgress = state.translationProgress,
            translationMode = state.readerPrefs.translationMode,
            readingTheme = state.readerPrefs.readingTheme,
            chromeVisible = chromeVisible,
            failedTranslations = state.failedTranslations,
            onToggleChrome = onToggleChrome,
            onCycleTheme = onCycleTheme,
            onSelectChapter = onSelectChapter,
            onSelectChapterAtEnd = onSelectChapterAtEnd,
            onSetFontScale = onSetFontScale,
            onAskClicked = onAskClicked,
            onRetryTranslation = onRetryTranslation,
            initialScrollFraction = state.initialChapterScrollFraction,
            onScrollFractionChange = onScrollFractionChange,
        )
        is BookContent.Pdf -> PdfReader(
            padding = padding,
            book = book,
            fontScale = state.fontScale,
            onSetFontScale = onSetFontScale,
        )
        null -> Unit
    }
}
