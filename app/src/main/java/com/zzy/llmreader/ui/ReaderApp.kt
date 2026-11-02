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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.zzy.llmreader.ui.icons.ReaderIcons
import com.zzy.llmreader.ui.theme.ReaderThemeOverride
import com.zzy.llmreader.web.WebSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zzy.llmreader.ui.reader.clearImageGenError
import com.zzy.llmreader.ui.reader.deleteGeneratedImage
import com.zzy.llmreader.ui.reader.extractScenePrompt
import com.zzy.llmreader.ui.reader.findCachedImageByPrompt
import com.zzy.llmreader.ui.reader.generateAllInlinePrompts
import com.zzy.llmreader.ui.reader.generateImage
import com.zzy.llmreader.ui.reader.generateInlineImage
import com.zzy.llmreader.ui.reader.imageFilePath
import com.zzy.llmreader.ui.reader.refreshChapterImages
import com.zzy.llmreader.ui.reader.openDiscoveryItem
import com.zzy.llmreader.ui.reader.retryChapterTranslation
import com.zzy.llmreader.ui.reader.saveCharacterLibrary
import com.zzy.llmreader.ui.reader.saveImageGenSettings

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
    // Toast 用来把「保存时被忽略的条目」这类信息明确告诉用户，而不是静默丢弃
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 书库列表的滚动位置：放在这一层才能在「打开书 → 返回书库」之间保住，
    // 放在 LibraryScreen 里 remember 的话，开书时整个屏幕离开组合、位置就丢了。
    val libraryListState = androidx.compose.foundation.lazy.rememberLazyListState()
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
                        text = state.book?.title ?: "我的书房",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (state.book != null) {
                        IconButton(onClick = {
                            returnToDiscovery = false
                            viewModel.closeBook()
                        }) {
                            Icon(ReaderIcons.Library, contentDescription = "返回书架")
                        }
                    }
                },
                actions = {
                    if (state.book == null) {
                        IconButton(onClick = { showDiscovery = true }) {
                            Icon(ReaderIcons.Search, contentDescription = "浏览网站")
                        }
                        IconButton(onClick = { showUrlDialog = true }) {
                            Icon(ReaderIcons.Link, contentDescription = "添加网页小说")
                        }
                    } else {
                        TranslationModeMenu(
                            current = state.readerPrefs.translationMode,
                            enabled = !state.loading,
                            onSelect = viewModel::setTranslationMode,
                        )
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(ReaderIcons.More, contentDescription = "更多操作")
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("查看详情") },
                                    leadingIcon = { Icon(ReaderIcons.Info, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.openBookDetailForCurrent()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("图片生成") },
                                    leadingIcon = { Icon(ReaderIcons.Image, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        showImagePanel = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("术语表") },
                                    leadingIcon = { Icon(ReaderIcons.BookOpen, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        showGlossary = true
                                    },
                                )
                                val isOriginalMode = state.readerPrefs.translationMode == TranslationMode.ORIGINAL
                                DropdownMenuItem(
                                    text = { Text(if (chromeVisible) "隐藏顶栏（沉浸式）" else "显示顶栏") },
                                    leadingIcon = {
                                        Icon(
                                            if (chromeVisible) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showOverflow = false
                                        chromeVisible = !chromeVisible
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("重新翻译本章") },
                                    leadingIcon = { Icon(ReaderIcons.Refresh, contentDescription = null) },
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
                                        leadingIcon = { Icon(ReaderIcons.Download, contentDescription = null) },
                                        enabled = !state.loading
                                            && state.currentChapter !in state.loadingChapters,
                                        onClick = {
                                            showOverflow = false
                                            viewModel.reDownloadChapter(state.currentChapter)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (state.tocRefreshing) "正在刷新目录…"
                                                else "刷新目录（保留译文）",
                                            )
                                        },
                                        leadingIcon = { Icon(ReaderIcons.Refresh, contentDescription = null) },
                                        enabled = !state.loading && !state.tocRefreshing,
                                        onClick = {
                                            showOverflow = false
                                            viewModel.refreshToc()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(ReaderIcons.Settings, contentDescription = "设置")
                    }
                },
            )
            }
        },
        floatingActionButton = {
            if (state.book == null) {
                ExtendedFloatingActionButton(
                    onClick = { openLauncher.launch(FormatDetector.openableMimeTypes) },
                    icon = { Icon(ReaderIcons.FolderOpen, contentDescription = null) },
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
                llmConfigured = state.settings.apiKey.isNotBlank(),
                onOpenSettings = { showSettings = true },
                listState = libraryListState,
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
                viewModel.saveGlossary(entries) { dropped ->
                    android.widget.Toast.makeText(
                        ctx,
                        "已忽略 $dropped 条无效条目（空行、整句或过长文本）",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
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
                imageVector = ReaderIcons.Languages,
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
                label.ifBlank { "正在准备你的书籍…" },
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
    llmConfigured: Boolean = true,
    onOpenSettings: () -> Unit = {},
    /**
     * 由上层持有：书库在打开一本书时会整个离开组合，
     * 若把 LazyListState 放在这里 remember，滚动位置每次回来都会重置到顶部。
     */
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    var pendingDelete by remember { mutableStateOf<RecentBook?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            if (recents.isEmpty()) "把故事，读成自己的模样。" else "欢迎回来，接着读吧。",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 24.dp, start = 4.dp, end = 4.dp),
        )
        Text(
            "本地书籍与在线小说，在一处安静阅读。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp, start = 4.dp, end = 4.dp),
        )
        // 必填项还没填时，把入口直接摆到书库页 —— 否则用户要翻译时才发现没配置，
        // 而设置面板里有十几个字段，不知道该填哪几个。
        if (!llmConfigured) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable(onClick = onOpenSettings),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("还没配置大模型", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "翻译功能需要 Base URL / API Key / Model 三项，点这里去填 →",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Text(
            "支持 TXT、Markdown、HTML、EPUB、PDF，以及 8 个在线小说站点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        if (recents.isNotEmpty()) {
            val latest = recents.first()
            Surface(
                onClick = { onOpenRecent(latest) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ReaderIcons.Library, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("继续阅读《${latest.title}》", style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("从上次停下的地方接着读", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                    }
                    Text("开始", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (recents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        ReaderIcons.Library,
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
                            Icon(ReaderIcons.Link, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("粘贴链接")
                        }
                        TextButton(onClick = onPick) {
                            Icon(ReaderIcons.FolderOpen, contentDescription = null)
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
                    "最近阅读",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${recents.size} 本",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(recents, key = { it.uri }) { book ->
                    RecentItem(
                        book = book,
                        onClick = { onOpenRecent(book) },
                        onDelete = { pendingDelete = book },
                        onShowDetail = { onShowDetail(book) },
                    )
                }
            }

            // 删除前先确认。网页小说的「移除」会连本地缓存的全部章节和
            // LLM 译文一起删掉（见 ReaderViewModel.removeRecent），
            // 旧版点一下就直接删，没有任何挽回余地。
            pendingDelete?.let { target ->
                val isWeb = target.source == com.zzy.llmreader.data.RecentBookSource.WEB ||
                    target.uri.startsWith("http", ignoreCase = true)
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("移除《${target.title}》？") },
                    text = {
                        Text(
                            if (isWeb) {
                                "会同时删除这本书已下载的全部章节正文、LLM 译文和术语表，" +
                                    "且无法恢复。重新打开需要重新下载和重新翻译。"
                            } else {
                                "只会从书库列表里移除，本地文件不受影响。"
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingDelete = null
                            onRemoveRecent(target)
                        }) { Text(if (isWeb) "删除缓存并移除" else "移除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                    },
                )
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
    val df = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }
    val isWeb = book.source == RecentBookSource.WEB || book.uri.startsWith("http", ignoreCase = true)
    val sourceLabel = if (isWeb) "在线" else "本地"
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 低饱和书封，保留区分度但不喧宾夺主。
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
                Icon(ReaderIcons.Info, contentDescription = "查看详情")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "移除")
            }
        }
    }
}

private fun recentPalette(seed: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val palettes = listOf(
        androidx.compose.ui.graphics.Color(0xFF648472) to androidx.compose.ui.graphics.Color(0xFF3E5C50),
        androidx.compose.ui.graphics.Color(0xFFAA8D6C) to androidx.compose.ui.graphics.Color(0xFF766044),
        androidx.compose.ui.graphics.Color(0xFF7F8FA0) to androidx.compose.ui.graphics.Color(0xFF526373),
        androidx.compose.ui.graphics.Color(0xFFAC8C83) to androidx.compose.ui.graphics.Color(0xFF785F57),
        androidx.compose.ui.graphics.Color(0xFF86957C) to androidx.compose.ui.graphics.Color(0xFF53634C),
        androidx.compose.ui.graphics.Color(0xFF92869C) to androidx.compose.ui.graphics.Color(0xFF65586F),
        androidx.compose.ui.graphics.Color(0xFF729395) to androidx.compose.ui.graphics.Color(0xFF49676A),
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
                    // 可选中复制：旧版全项目 0 处 SelectionContainer，
                    // 花钱让 AI 生成的总结一个字都复制不出来
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(ask.answer)
                    }
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
        )
        null -> Unit
    }
}
