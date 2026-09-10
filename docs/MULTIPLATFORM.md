# 多平台（Android + Desktop）改造说明

本文记录 `llm_reader` 从纯 Android 工程改造为 Kotlin Multiplatform 工程的设计、
落地边界，以及继续扩展到 iOS 时还需要补的东西。

---

## 1. 结论速览

| 项目 | 改造前 | 改造后 |
|---|---|---|
| 模块 | `:app` 单模块 | `:shared`（KMP）+ `:app`（Android）+ `:desktop`（Compose Desktop） |
| 目标平台 | Android | Android + Windows / macOS / Linux |
| 核心逻辑位置 | 全在 `:app` | `:shared`，两端共用约 7,000 行 |
| 章节正文类型 | `android.text.Spanned` | **HTML 字符串**（平台中立） |
| 平台依赖入口 | 直接持有 `Context` | `AppDirs` + `DataStore<Preferences>` 注入 |
| 发版 | 手动 `assembleRelease` | GitHub Actions：打 tag 自动出 APK + deb/msi/dmg |

**:shared 与 :app / :desktop 的代码占比**

```
shared/src/jvmSharedMain   站点抓取(8 站) · LLM 翻译 · 生图客户端 · 配置仓储 · 文档解析   ≈ 7,000 行
shared/src/commonMain      模型 + 平台缝隙声明（expect）                                  ≈  100 行
app/                       Android Compose UI + WebView 过 CF + PDF 渲染                  ≈ 9,900 行
desktop/                   Compose Desktop UI                                            ≈ 1,100 行
```

---

## 2. 模块与源集结构

```
llm_reader/
├── shared/                         KMP 库：androidTarget() + jvm("desktop")
│   └── src/
│       ├── commonMain/             纯 Kotlin：模型、平台缝隙声明
│       │   └── com/zzy/llmreader/
│       │       ├── model/BookContent.kt        Chapter.content: String? (HTML)
│       │       └── platform/PlatformLog.kt     expect 日志
│       ├── jvmSharedMain/          ★ Android 与 Desktop 共用的 JVM 实现
│       │   └── com/zzy/llmreader/
│       │       ├── LlmReaderCore.kt            组合根（两端各 new 一个）
│       │       ├── web/                        8 个站点源 + 发现页 + 缓存仓储
│       │       ├── llm/                        LlmClient(SSE) + ChapterTranslator
│       │       ├── image/                      NovelAI / ComfyUI 客户端 + 图片缓存
│       │       ├── data/                       DataStore 仓储
│       │       ├── format/                     DocumentParser + HTML→富文本
│       │       └── platform/AppDirs.kt         平台目录抽象
│       ├── androidMain/            actual：android.util.Log
│       └── desktopMain/            actual：println 日志 + org.json 实现
├── app/                            Android：Compose UI、SAF、WebView 过 CF、PdfRenderer
└── desktop/                        Desktop：Compose Multiplatform UI
```

### 为什么用 `jvmSharedMain` 这个中间源集

8 个站点抓取依赖 **Jsoup**，LLM 客户端依赖 **OkHttp**，EPUB 解析依赖
**java.util.zip / javax.xml**。这些都是 JVM 专有，放不进 `commonMain`。

KMP 的 `dependsOn` 允许声明一个"Android 和 Desktop 都依赖"的中间源集，
于是这 ≈7,000 行**一行没改**就实现了两端共享：

```kotlin
val jvmSharedMain by creating { dependsOn(commonMain) }
androidMain.get().dependsOn(jvmSharedMain)
val desktopMain by getting { dependsOn(jvmSharedMain) }
```

> 代价：这个源集只对 JVM 系平台可用。iOS 需要先把 Jsoup → Ksoup、
> `java.util.zip` → KMP 解压库、OkHttp → Ktor 之后才能上移到 `commonMain`。
> 详见第 6 节。

---

## 3. 平台缝隙（全部的跨平台改动点）

改造时刻意把平台差异压缩到极少数几个位置，其余代码保持零改动。

| 缝隙 | 改造前（Android only） | 改造后 |
|---|---|---|
| **可写目录** | 各处直接 `context.filesDir` | `AppDirs(dataDir, cacheDir)` 构造注入 |
| **配置存储** | `Context.preferencesDataStore("settings")` | `PreferenceDataStoreFactory.createWithPath`，路径一致 |
| **文档输入** | `DocumentParser(context).parse(uri)` | `DocumentParser().parse(BookSource)` |
| **日志** | `android.util.Log` | `expect fun platformLogI/W/E` |
| **富文本** | 模型直接存 `Spanned` | 模型存 HTML；两端各自渲染 |
| **CF 验证** | 内嵌 WebView Activity | Android 保留；桌面端改用"手动粘贴 cookie" |
| **PDF** | `android.graphics.pdf.PdfRenderer` | Android 保留；桌面端暂不渲染 |
| **文件选择** | SAF `ActivityResultContracts.OpenDocument` | Android 保留；桌面端 AWT `FileDialog` |
| **org.json** | Android 平台自带 | 桌面端补 `org.json:json` 依赖（4 个站点源共用） |

### 3.1 配置迁移不丢数据（关键细节）

`createAppDataStore` 刻意复刻了 Android 原路径：

```kotlin
// 结果路径 = <filesDir>/datastore/settings.preferences_pb
// 与旧的 Context.preferencesDataStore(name = "settings") 完全一致
val file = File(File(dirs.dataDir, "datastore"), "$name.preferences_pb")
```

Android 端传 `AppDirs(filesDir, cacheDir)`，所以**升级后已有的 API Key、
cookie、阅读进度、术语表、生图配置全部原样保留**。桌面端同一函数写到
`~/.llm_reader/datastore/settings.preferences_pb`。

### 3.2 章节正文从 Spanned 改为 HTML

这是本次唯一的"破坏性"类型改动，收益却很大：

- **翻译管线本来就以 HTML 为单位**（`ChapterTranslator` 直接吃 HTML），
  改造后删掉了 `spannedToHtml()` 这个 Spanned → HTML 的反向转换 hack；
- Android 端只在 UI 边界转换一次：`TextReader` 里
  `remember(chapter.content) { htmlToSpanned(it) }`，下游的段落缩进、
  `[IMG:]` 插图卡片等逻辑**一行未改**；
- 桌面端用 `htmlToRichBlocks()`（jsoup，放在 shared）转成
  `RichBlock` 列表再映射到 Compose `AnnotatedString`，HTML 解析只有一份实现。

---

## 4. 构建与运行

前置：JDK 17。`settings.gradle.kts` 已启用 **foojay-resolver**，
Gradle 会自动解析/下载匹配的 JDK 工具链，不再依赖硬编码的 `org.gradle.java.home`。

```bash
# Android
./gradlew :app:assembleDebug
./gradlew :app:installDebug

# 桌面端：直接运行
./gradlew :desktop:run

# 桌面端：打本机安装包（macOS→dmg / Windows→msi / Linux→deb）
./gradlew :desktop:packageDmg

# 只编译核心逻辑（最快的问题定位方式，秒级）
./gradlew :shared:compileKotlinDesktop

# 核心逻辑的冒烟测试（解析 / 持久化 / 富文本 / 站点派发，13 个用例）
./gradlew :shared:desktopTest
```

> **jpackage 供应商校验**：Compose Desktop 默认拒绝用 Homebrew 的 OpenJDK 打包，
> 本工程在 `gradle.properties` 里设了 `compose.desktop.packaging.checkJdkVendor=false`
> 以便本机打包。正式安装包由 CI 用 **Temurin JDK 17** 构建，不受影响；
> 若本地 `.dmg` 出现异常，请换 Temurin 重新打包。

> macOS 上如果 `./gradlew` 报 `Unable to locate a Java Runtime`，
> 说明启动 Gradle 的 JVM 没注册到系统（Homebrew 装的 JDK 默认不注册）：
> ```bash
> sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk \
>   /Library/Java/JavaVirtualMachines/openjdk-17.jdk
> ```

桌面端数据目录：`~/.llm_reader`（可用环境变量 `LLM_READER_HOME` 覆盖）。

---

## 5. 桌面端当前能力与已知限制

**已实现**：本地文件（TXT / Markdown / HTML / EPUB）解析、8 个站点的网页小说抓取与
章节懒加载、目录树、原文/译文/双语三种模式、逐章 LLM 翻译（流式部分结果 +
网页书译文落盘缓存）、术语表注入、阅读进度记录、最近阅读列表、LLM 与翻译设置、
日间/米黄/夜间阅读主题、字体缩放。

**限制**（有意留到后续）：

| 功能 | 状态 |
|---|---|
| PDF 阅读 | ❌ 桌面端未实现（Android 用系统 `PdfRenderer`） |
| Cloudflare 挑战 | ⚠️ 无内嵌浏览器；需在其他浏览器过验证后到设置里粘贴 cookie |
| AI 配图（NovelAI / ComfyUI） | ⚠️ 客户端已共享，但桌面端 UI 尚未接出来 |
| 发现页（图书馆式浏览） | ⚠️ 逻辑已共享，桌面端 UI 未实现（可直接粘 URL 阅读） |
| 阅读进度精确到滚动位置 | ⚠️ 只记录到章节 |

---

## 6. 如果要继续支持 iOS

`commonMain` 目前只有模型和日志声明，iOS 需要的额外工作按优先级：

1. **HTML 解析**：`org.jsoup` → `com.fleeksoft.ksoup`（API 基本同构，改 import 为主），
   之后 8 个站点源可以从 `jvmSharedMain` 上移到 `commonMain`。
2. **HTTP**：`okhttp3` → `ktor-client`（`LlmClient`、`HtmlWebSource`、
   NovelAI/ComfyUI 客户端）；DoH 那段 DNS 逻辑需要按平台重写
   （iOS 走 `NSURLSession` + 自定义 DNS 或干脆只依赖系统 DNS）。
3. **ZIP/XML**：`java.util.zip` → `okio`/`kotlinx-io` 的 KMP 解压；
   `javax.xml` → `xmlutil` 或 ksoup。
4. **org.json**：4 个站点源改为 `kotlinx.serialization`（顺带去掉平台差异）。
5. **DataStore**：`datastore-preferences-core` 本身支持 Apple 目标，但要提供
   `AppDirs` 的 iOS 实现（`NSDocumentDirectory`）。
6. **UI**：把 `app/` 与 `desktop/` 两套 UI 合并到 Compose Multiplatform 的
   `commonMain`。主要拦路虎仍是 `TextReader`：Android 侧基于
   `AndroidView + TextView + Markwon`，需要换成 shared 的富文本中间表示。
   （好在类型已经统一成 HTML，`htmlToRichBlocks()` 可直接复用。）

---

## 7. 自动发版（GitHub Actions）

工作流：`.github/workflows/release.yml`

**触发**：推送 `v*` tag，或在 Actions 页面手动 `workflow_dispatch`（可指定版本号）。

### 7.1 签名（写死在仓库里）

Release 签名**不使用 GitHub Secrets**，而是直接写死在仓库根目录：

```
keystore/release.keystore     签名密钥（PKCS12）
keystore.properties           密码 / alias / 路径，由 app/build.gradle.kts 读取
```

`app/build.gradle.kts` 启动时读 `keystore.properties`，有配置就用它签 release；
**文件缺失时自动回退 debug 签名** —— 这样别人 clone 下来照样能构建，CI 也不需要任何额外配置。

> ⚠️ **仓库必须保持私有。**
> 签名私钥在版本控制里，一旦仓库公开，任何人都能拿到这把 key，签出与你 App
> 同包名、同签名的 APK，安卓会把它当作合法更新安装。
>
> **轮换密钥**：删掉 `keystore/` 与 `keystore.properties` → 用 `keytool -genkeypair`
> 重新生成 → 更新 `keystore.properties`。代价是已安装的 App 因签名不同无法覆盖升级，
> 用户必须卸载重装。

CI 里还有一道保险：`Verify the APK is signed with the release key` 步骤用
`apksigner --print-certs` 检查产物，发现是 `CN=Android Debug` 就**直接让构建失败**，
避免签名静默失效却照常发布。

本地验证签名是否生效：

```bash
./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
# 期望 DN: CN=LLM Reader, OU=Personal, O=daolao1, C=CN
```

### 7.2 执行流程

```
prepare   ── 从 tag/输入解析 versionName + versionCode(=major*10000+minor*100+patch)
   ├── android (ubuntu)    :app:assembleRelease → apksigner 验签 → android-apk
   ├── desktop (matrix)    ubuntu→.deb / windows→.msi / macos→.dmg
   └── release (ubuntu)    download-artifact + softprops/action-gh-release
                           自动生成 release notes，预发布版按 `-` 后缀判定
```

本地复现 CI 的版本注入：

```bash
./gradlew :app:assembleRelease -PversionName=1.2.3 -PversionCode=10203
```

> 注意 1：只要 `desktop` 矩阵里**任意一个** OS 打包失败，`release` 任务就不会执行
> （`needs` 语义）。若 Windows runner 偶发失败，可先只重跑失败的 leg。
>
> 注意 2：私有仓库的 Actions 分钟数是**计费**的（公开仓库免费），且 macOS runner
> 按 10 倍计费。额度紧张时可以把 `macos-latest` 从矩阵里去掉。
