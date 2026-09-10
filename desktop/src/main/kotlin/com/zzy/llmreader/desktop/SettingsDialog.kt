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

@Composable
fun SettingsDialog(state: DesktopUiState, app: DesktopAppState, onDismiss: () -> Unit) {
    var baseUrl by remember { mutableStateOf(state.settings.baseUrl) }
    var apiKey by remember { mutableStateOf(state.settings.apiKey) }
    var model by remember { mutableStateOf(state.settings.model) }
    var language by remember { mutableStateOf(state.prefs.translationLanguage) }
    var style by remember { mutableStateOf(state.prefs.translationStyle) }
    var batchChars by remember { mutableStateOf(state.prefs.translationBatchChars.toString()) }
    var prefetchAhead by remember { mutableStateOf(state.prefs.webPrefetchAhead.toString()) }

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
