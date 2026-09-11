# 翻译流水线的速度设计

本文记录 `shared/src/jvmSharedMain/.../llm/ChapterTranslator.kt` 的**性能不变量**与实测数字。
改动这条链路前请先读一遍：下面每一条都对应一次真实的性能缺陷。

---

## 1. 一次章节翻译的完整链路

```
extractParagraphs(html)                Jsoup 解析，取出 <p> 段落
  → batchParagraphs(batchChars)        按字符数分批（默认 4000 字/批）
  → 标题翻译 ∥ 批次翻译                 ← 并发，见 §3
  → translateBatchStream(每批)         流式 SSE：解析 [N] 译文 → 推部分 HTML
      → retryMissingParagraphs         仅对缺号段落补译一次
  → buildPreviousContext               本批译文尾部 → 下一批/下一章上下文
  → onBatchTranslated                  抽取人名专有名词，更新术语表（落盘）
```

---

## 2. 流式回调不得是 O(章节长度)

**SSE 的每个 delta 都在读取循环里同步回调**，回调里做的任何与"整章长度"成正比的工作，
都会按 O(n²) 累积，并且直接拖慢"读下一个 token"。

### 曾经的写法（已修）

| 位置 | 问题 |
|---|---|
| `parseNumberedOutput(buf.toString(), n)` | 每个 delta 对**整个累积 buffer** 重新正则全扫 + `findAll` 分配 |
| `buildHtml(translatedParagraphs, paragraphs)` | 每个 delta 重建**整章** HTML（含全量 escape + join） |

### 实测代价（一章 80 字段落，输出 2.8 万字，14,195 个 delta）

| 指标 | 旧实现 |
|---|---|
| 回调总耗时 | **1.61 s**（40k 字时 5.41 s） |
| 其中纯解析 | 1.09 s |
| 其中重建 HTML | 0.28 s |
| 累计构造字符串 | **341 MB**（40k 字时 1.37 GB） |
| 均摊 | 0.113 ms/delta，且随章节增大而上升（O(n²) 的特征） |

在 Android 上这就是 GC 抖动与掉帧；1.3 GB 的临时字符串全部落在 512 MB 堆里。

### 现在的写法

- **[`IncrementalNumberedParser`]**：游标式推进，每个 delta 只扫新增的那一段，
  代价与新增字符数成正比。
- **[`IncrementalHtmlBuilder`]**：每段只 escape 一次并缓存；用 revision 去重，
  内容没变就不重新 join、也不向下推。
- **批末强制推送**（`takeIfDirty(force = true)`）：节流窗口不能吞掉一批的完整译文。
- **节流 250ms**（`PARTIAL_FLUSH_INTERVAL_MS`）：每次推送都会让阅读端整章重新
  Spanned / 布局，250ms 与 Android 侧原有的 `PartialBuffer` 对齐。

### 现状

```
[规模] 段数=300 端到端=37ms 部分译文下发=4 次
```

由 `shared/src/desktopTest/.../StreamingCostBenchmarkTest.kt` 守住：它跑真实
`ChapterTranslator` + 本地 SSE 桩服务端，断言下发次数被节流、端到端耗时不爆、
且每次下发的段落数单调不回退（否则界面上会看到已翻译内容变回原文）。

---

## 3. 标题与首批并发

旧实现串行：标题翻完（一次完整往返）才发第一批段落请求 —— 首屏第一段译文被推迟一整个 RTT。
现在 `titleJob` 与批次并发；标题**只用于 `onPartial` 的标题字段**，未完成时取 null，
绝不阻塞段落流式输出。标题翻译失败也不再丢掉整章（`translateTitleResilient` 返回 null）。

> ⚠️ `Job.getCompleted()` 在 job 未完成时**抛异常**而不是返回 null。标题通常还没翻完，
> 漏掉 `runCatching` 会让"标题比首批慢"这种常态把整批判成失败（实测触发过）。

---

## 4. 并发批次：窗口与门控

`translationConcurrency`（设置里可调，**默认 1 = 串行**）：

```
第 k 批发请求前必须等第 (k - window + 1) 批返回     window = concurrency
```

- `window = 1`：等紧邻的上一批 → **完全串行**，前文译文最强，与旧行为一致。
- `window > 1`：允许后一批与前一批同时在跑。第 k 批的"前文译文"取自更早一批，
  指代与称谓衔接略弱于串行 —— 这是用上下文质量换速度，所以默认关闭。

### 两个必须守住的不变量

1. **前文译文只能来自已经翻完的批次**，不允许并行期间凭空猜测。
   可观测形式：第 k 批的请求发出时间 ≥ 第 (k - window + 1) 批的返回时间。
2. **收割在单一协程里按批序进行**：调用方的 `onBatchTranslated` / `onPartial`
   永远串行且按批顺序被调用，调用方不需要考虑线程安全。

### 实测

| 配置 | 批次数 | 端到端 | 在途峰值 |
|---|---|---|---|
| `concurrency=1` | 4 | 846 ms | 2（含标题） |
| `concurrency=3` | 4 | **421 ms** | 4 |

由 `shared/src/desktopTest/.../TranslationPipelineSpeedTest.kt` 守住（桩服务端记录
每个请求的起止时间，断言窗口上限、重叠存在、门控满足）。

### ⚠️ 调度器必须切到 Dispatchers.IO

调用方可能跑在**单线程**调度器上（测试的 `runBlocking`、Android 的 `Dispatchers.Main`）。
协程在 `prevPromise.join()` 处挂起后，续体会被派发回**那个**单线程；随后发起的请求
就挤在同一线程上，只要有一个请求在建立连接/读响应，其余批次全部排队 ——
**并发被静默退化成串行**。因此整个批次体包在 `withContext(Dispatchers.IO)` 里。

这个坑当初的表现为：`concurrency=1` 与 `concurrency=3` 的端到端耗时完全相同。

---

## 5. 术语表：章内只读一次

旧实现每批都调一次 `glossaryProvider()`（网页书 = 读盘 + 反序列化整本缓存），
再 `joinToString` 重建一遍字符串。

现在章内只读一次（`glossarySnapshot`），每批仍只注入命中的条目
（`filterGlossaryForBatch`：原文命中本批原文，或译名命中前文译文）。

**取舍**：同一章前面批次刚抽出的新术语，不再立刻出现在后面批次的提示词里；
跨章仍然照常生效（下一章会重新读盘）。换来的是每批少一次读盘 + 全量字符串重建，
并且并发时各批不会各读一份快照。

---

## 6. ⚠️ SSE 会把 `[N]` 标记拆到两个 delta 里

增量解析器的标记扫描起点**必须回退一个"标记可能的最大长度"**，不能直接用本轮新增文本的起点。

```
append#1: delta=[1    从 from=0 搜 -> 未命中   ← '[' 已进 buffer
append#2: delta=]     从 from=2 搜 -> 未命中   ← '[' 在 index 0，被永久跳过
append#11: delta=[2   从 from=19 搜 -> 未命中  ← 同一个错误重复
```

`[12]` 会被 SSE 拆成 `[1`（上一轮）和 `2] `（这一轮）。若从本轮起点往后搜，`[` 落在已经
扫描过的区间里，标记**永远匹配不到**：`current` 始终为 -1 → `emit()` 从不执行 →
`onPartial()` 一次都不发。

### 为什么这个 bug 特别隐蔽

收尾的 `finish()` 走**全量重扫**，所以**最终译文完全正确**——它不以"译文错误"的形式暴露，
只让"边翻边看"静默消失。表现是整章只在结尾一次性出现，而所有非流式的测试全绿。

实测就是这样坏掉的：8 秒的流式响应，`onPartial` 只触发 **1 次**（批末那一次）。

### 正确写法

```kotlin
// 扫描起点回退 MAX_MARKER_CHARS（14 = '[' + 10 位数字 + ']' + 空白）
val searchFrom = minOf(scanLimit, maxOf(0, from - MAX_MARKER_CHARS))
```

回退扫是安全的：`lastMarkerEnd` 只会因**发现新标记**而推进，已扫描过的区间不会重复上报。

### 测试上的坑

**桩服务端必须真的慢**（每个 delta 之间 sleep）。若服务端瞬时返回，250ms 节流会把所有中间
推送都吞掉、只剩批末那一次——那样即使解析器坏了也测不出来。最初的测试版本正是因此漏掉了它。
见 `shared/src/desktopTest/.../StreamingTimelineTest.kt`。

---

## 7. 调用方约定

- **`translateStreaming`**：生产路径（App 两端都用它）。接受 `concurrency`，
  结果经 `onFinish` 交还 —— 中间态走 `onPartial`，最终态走 `onFinish`，两者都不会漏。
- **`translate`**：便捷重载，`concurrency = 1`、丢弃流式回调。留给"只要结果"的调用方。
- `onFinish` 是 **suspend** 的：桌面端要在里面落盘（`persistTranslation`）。
- `onBatchTranslated` 由调度器按批顺序串行调用，可以安全地做"读-改-写"式术语表合并。
