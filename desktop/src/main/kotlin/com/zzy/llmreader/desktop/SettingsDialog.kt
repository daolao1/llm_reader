package com.zzy.llmreader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.zzy.llmreader.data.AppTheme
import com.zzy.llmreader.data.LlmSettings
import com.zzy.llmreader.data.ReaderPreferences
import com.zzy.llmreader.data.ReadingTheme
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun SettingsDialog(state: DesktopUiState, app: DesktopAppState, onDismiss: () -> Unit) {
    // remember 带 key：设置是异步从 DataStore 到达的，
    // 若在首次发射之前打开面板，无 key 的 remember 会一直保留空白初值，
    // 此时点「保存」就会用空字符串覆盖掉已存好的配置。
    var baseUrl by remember(state.settings) { mutableStateOf(state.settings.baseUrl) }
    var apiKey by remember(state.settings) { mutableStateOf(state.settings.apiKey) }
    var model by remember(state.settings) { mutableStateOf(state.settings.model) }
    var language by remember(state.prefs) { mutableStateOf(state.prefs.translationLanguage) }
    var style by remember(state.prefs) { mutableStateOf(state.prefs.translationStyle) }
    var batchChars by remember(state.prefs) { mutableStateOf(state.prefs.translationBatchChars.toString()) }
    var prefetchAhead by remember(state.prefs) { mutableStateOf(state.prefs.webPrefetchAhead.toString()) }

    // ---- 站点登录（桌面端没有内嵌浏览器，只能手动粘贴）----
    var pixivCookie by remember(state.prefs) { mutableStateOf(state.prefs.pixivPhpSessId) }
    var hamelnCookie by remember(state.prefs) { mutableStateOf(state.prefs.hamelnCookie) }
    var hamelnUa by remember(state.prefs) { mutableStateOf(state.prefs.hamelnUserAgent) }
    var scribblehubCookie by remember(state.prefs) { mutableStateOf(state.prefs.scribblehubCookie) }
    var scribblehubUa by remember(state.prefs) { mutableStateOf(state.prefs.scribblehubUserAgent) }
    var ao3Cookie by remember(state.prefs) { mutableStateOf(state.prefs.ao3Cookie) }
    var ao3Ua by remember(state.prefs) { mutableStateOf(state.prefs.ao3UserAgent) }
    var expandedSite by remember { mutableStateOf<String?>(null) }
    var showApiKey by remember { mutableStateOf(false) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle("大模型（OpenAI 兼容 /chat/completions）")
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    supportingText = { Text("例如 https://open.bigmodel.cn/api/paas/v4 或 https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showApiKey = !showApiKey }) {
                            Text(if (showApiKey) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            fetching = true
                            hint = null
                            app.fetchModels(LlmSettings(baseUrl, apiKey, model)) { result ->
                                fetching = false
                                result.onSuccess { models = it; hint = "拉到 ${it.size} 个模型" }
                                    .onFailure { hint = "获取失败：${it.message}" }
                            }
                        },
                        enabled = !fetching && apiKey.isNotBlank(),
                    ) { Text(if (fetching) "获取中…" else "获取列表") }
                }
                models.takeIf { it.isNotEmpty() }?.let { list ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                    ) {
                        LazyColumn {
                            items(list) { m ->
                                Text(
                                    text = m,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "点击下面任意模型名即可填入：",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.take(4).forEach { m ->
                            TextButton(onClick = { model = m }) { Text(m, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                SectionTitle("翻译")
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text("目标语言") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text("风格说明（可选）") },
                    supportingText = { Text("例如：口语化 / 轻小说风格 / 正式文学") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = batchChars,
                    onValueChange = { batchChars = it.filter(Char::isDigit) },
                    label = { Text("每批最大字符数") },
                    supportingText = { Text("越大请求越少，但单次输出越长越容易截断；建议 2000–6000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))
                SectionTitle("网页小说")
                OutlinedTextField(
                    value = prefetchAhead,
                    onValueChange = { prefetchAhead = it.filter(Char::isDigit) },
                    label = { Text("后台预取章节数") },
                    supportingText = { Text("0 表示关闭；默认 3") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))
                SectionTitle("站点登录（按需）")
                Text(
                    "桌面端没有内嵌浏览器，遇到 Cloudflare 拦截或需要登录时，只能手动粘贴 cookie。\n" +
                        "获取方式：用浏览器打开站点并通过验证 → F12 → Application → Cookies → " +
                        "复制 cf_clearance（Pixiv 是 PHPSESSID）。不填也能用，只是部分内容拉不到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SiteCredentialGroup(
                    title = "Pixiv",
                    summary = if (pixivCookie.isBlank()) "未登录（只能看全年龄）" else "已填 PHPSESSID",
                    expanded = expandedSite == "pixiv",
                    onToggle = { expandedSite = if (expandedSite == "pixiv") null else "pixiv" },
                ) {
                    OutlinedTextField(
                        value = pixivCookie,
                        onValueChange = { pixivCookie = it },
                        label = { Text("PHPSESSID") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SiteCredentialGroup(
                    title = "Hameln (syosetu.org)",
                    summary = if (hamelnCookie.isBlank()) "未填" else "已填 cookie",
                    expanded = expandedSite == "hameln",
                    onToggle = { expandedSite = if (expandedSite == "hameln") null else "hameln" },
                ) {
                    CookieAndUa(
                        cookie = hamelnCookie, onCookie = { hamelnCookie = it },
                        ua = hamelnUa, onUa = { hamelnUa = it },
                    )
                }
                SiteCredentialGroup(
                    title = "ScribbleHub",
                    summary = if (scribblehubCookie.isBlank()) "未填" else "已填 cookie",
                    expanded = expandedSite == "scribblehub",
                    onToggle = { expandedSite = if (expandedSite == "scribblehub") null else "scribblehub" },
                ) {
                    CookieAndUa(
                        cookie = scribblehubCookie, onCookie = { scribblehubCookie = it },
                        ua = scribblehubUa, onUa = { scribblehubUa = it },
                    )
                }
                SiteCredentialGroup(
                    title = "AO3",
                    summary = if (ao3Cookie.isBlank()) "未填" else "已填 cookie",
                    expanded = expandedSite == "ao3",
                    onToggle = { expandedSite = if (expandedSite == "ao3") null else "ao3" },
                ) {
                    CookieAndUa(
                        cookie = ao3Cookie, onCookie = { ao3Cookie = it },
                        ua = ao3Ua, onUa = { ao3Ua = it },
                    )
                }

                Spacer(Modifier.height(4.dp))
                SectionTitle("外观")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { t ->
                        FilterChip(
                            selected = state.prefs.appTheme == t,
                            onClick = { app.savePrefs(state.prefs.copy(appTheme = t)) },
                            label = {
                                Text(
                                    when (t) {
                                        AppTheme.SYSTEM -> "跟随系统"
                                        AppTheme.LIGHT -> "亮色"
                                        AppTheme.DARK -> "暗色"
                                    },
                                )
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingTheme.entries.forEach { t ->
                        FilterChip(
                            selected = state.prefs.readingTheme == t,
                            onClick = { app.savePrefs(state.prefs.copy(readingTheme = t)) },
                            label = {
                                Text(
                                    when (t) {
                                        ReadingTheme.DAY -> "日间"
                                        ReadingTheme.SEPIA -> "米黄"
                                        ReadingTheme.NIGHT -> "夜间"
                                    },
                                )
                            },
                        )
                    }
                }

                hint?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.saveSettings(LlmSettings(baseUrl.trim(), apiKey.trim(), model.trim()))
                app.savePrefs(
                    state.prefs.copy(
                        translationLanguage = language.trim().ifBlank { "简体中文" },
                        translationStyle = style,
                        translationBatchChars = batchChars.toIntOrNull()
                            ?.coerceIn(500, 20000) ?: state.prefs.translationBatchChars,
                        webPrefetchAhead = prefetchAhead.toIntOrNull()
                            ?.coerceIn(0, 20) ?: state.prefs.webPrefetchAhead,
                        pixivPhpSessId = pixivCookie.trim(),
                        hamelnCookie = hamelnCookie.trim(),
                        hamelnUserAgent = hamelnUa.trim(),
                        scribblehubCookie = scribblehubCookie.trim(),
                        scribblehubUserAgent = scribblehubUa.trim(),
                        ao3Cookie = ao3Cookie.trim(),
                        ao3UserAgent = ao3Ua.trim(),
                    ),
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
    }
}

/** 折叠的站点凭据组：默认只显示一行状态，需要时才展开。 */
@Composable
private fun SiteCredentialGroup(
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }
}

/** cookie + User-Agent。UA 是过验证时自动写回的，与 cf_clearance 绑定，一般不用手填。 */
@Composable
private fun CookieAndUa(
    cookie: String,
    onCookie: (String) -> Unit,
    ua: String,
    onUa: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = cookie,
            onValueChange = onCookie,
            label = { Text("cf_clearance cookie") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ua,
            onValueChange = onUa,
            label = { Text("User-Agent（与 cookie 绑定）") },
            supportingText = { Text("浏览器 F12 → Network → 任意请求的 User-Agent；留空则用默认 UA") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
