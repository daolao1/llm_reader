# LLM Reader

基于 **Kotlin Multiplatform + Compose** 的跨平台小说阅读器：
打开本地文档或在线小说 → 调用大模型翻译 / 问答 / 配图。

支持 **Android** 与 **桌面端（Windows / macOS / Linux）**，两端共用同一套核心逻辑。

技术栈：Kotlin 2.0.21 · AGP 8.6.1 · Compose BOM 2024.10 · Compose Multiplatform 1.7.3 ·
OkHttp · Jsoup · kotlinx.serialization · DataStore · Coil · Markwon

---

## 1. 功能

**阅读**
- 本地文档：TXT / Markdown / HTML / EPUB / PDF（PDF 仅 Android）
- 在线小说：Literotica、Syosetu / Novel18、AO3、Pixiv、Hameln、Kakuyomu、ScribbleHub、Royal Road
- 网页小说按需下载正文并缓存到本地，删除条目时一并清理
- 目录树（支持 kakuyomu 这类章节分组）、阅读进度记忆、字体缩放
- 阅读主题：日间 / 米黄 / 夜间；应用主题：跟随系统 / 亮色 / 暗色

**翻译**
- 逐章 LLM 翻译，流式返回部分译文（边翻边看）
- 三种显示模式：原文 / 译文 / 双语对照
- 跨章节术语表（人名、专有名词）自动积累，支持手动编辑与智能去重
- 网页书译文落盘缓存，重开不必重翻
- 提示词针对 DeepSeek / Gemini 等分别调优，并刻意保持 system prompt 稳定以命中 provider 的 prompt cache

**发现页**（Android 已实现）
- 图书馆式浏览 8 个站点：关键字搜索、多维过滤、排序、相似作品、作者同作品
- 列表标题/简介/标签一键翻译并缓存

**AI 配图**（Android 已实现）
- NovelAI / ComfyUI 双后端
- 可让 LLM 在译文中插入 `[IMG: danbooru tags]` 标记，渲染成文中"一键生成"卡片
- 支持导入 SillyTavern `st-chatu8` 配置 JSON

---

## 2. 工程结构

```
llm_reader/
├── shared/          Kotlin Multiplatform 库 —— Android 与桌面端共用的全部核心逻辑
│   └── src/
│       ├── commonMain/      模型 + 平台缝隙声明（expect）
│       ├── jvmSharedMain/   ★ 两端共用的 JVM 实现：站点抓取 / LLM / 仓储 / 文档解析
│       ├── androidMain/     Android 侧 actual
│       └── desktopMain/     桌面侧 actual
├── app/             Android 客户端（Jetpack Compose、SAF、WebView 过 CF、PdfRenderer）
├── desktop/         桌面客户端（Compose Multiplatform for Desktop）
├── docs/            设计文档
├── scripts/         辅助脚本
└── .github/workflows/release.yml   打 tag 自动发版
```

改造细节见 **[docs/MULTIPLATFORM.md](docs/MULTIPLATFORM.md)**。

---

## 3. 开发环境

- JDK 17（`settings.gradle.kts` 启用了 foojay-resolver，Gradle 会自动解析工具链）
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`、`platform-tools`

无 SDK 时一键安装（Linux / macOS）：

```bash
bash scripts/setup_android_sdk.sh        # 默认装到 ~/Android/Sdk
```

在工程根目录创建 `local.properties` 指向 SDK：

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

> macOS 上若 `./gradlew` 报 `Unable to locate a Java Runtime`，说明 Homebrew 装的 JDK
> 没有注册到系统（`/usr/bin/java` 只是个存根）。注册一次即可：
> ```bash
> sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk \
>   /Library/Java/JavaVirtualMachines/openjdk-17.jdk
> ```

---

## 4. 构建与运行

```bash
# Android
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/
./gradlew :app:installDebug           # 安装到已连接设备/模拟器

# 桌面端
./gradlew :desktop:run                # 直接运行
./gradlew :desktop:packageDmg         # macOS 打包（Windows 用 packageMsi，Linux 用 packageDeb）
./gradlew :desktop:createDistributable # 免安装目录

# 只编译共享逻辑（最快的问题定位方式）
./gradlew :shared:compileKotlinDesktop
```

桌面端数据目录：`~/.llm_reader`（可用环境变量 `LLM_READER_HOME` 覆盖）。

### Android 连接模拟器

```bash
bash scripts/run_via_winadb.sh                        # Windows 雷电模拟器（WSL2 场景）
LDPLAYER_ADB=/mnt/c/your/path/adb.exe bash scripts/run_via_winadb.sh
bash scripts/connect_ldplayer.sh                      # 非 WSL 环境直连
```

Android Studio：`File → Open` 选择工程根目录，等待 Gradle Sync 后直接运行 `app`。

---

## 5. 配置大模型

应用内「设置」填写（Android 在顶栏，桌面端在右上角齿轮）：

| 字段 | 示例 |
|---|---|
| Base URL | `https://open.bigmodel.cn/api/paas/v4`（智谱 GLM）、`https://api.openai.com/v1` |
| API Key | 你的密钥 |
| Model | `glm-4-flash` / `gpt-4o-mini` / `deepseek-chat` |

请求遵循 OpenAI 兼容的 `/chat/completions`，支持 SSE 流式。
点「获取列表」可直接拉取该 Base URL 下的可用模型。

**自定义站点配置**（可选）：Pixiv `PHPSESSID`、Hameln / ScribbleHub / AO3 的
`cf_clearance` cookie，用于访问需要登录或被 Cloudflare 拦截的内容。
Android 端遇到 Cloudflare 会自动弹出内嵌浏览器完成验证并回填 cookie。

---

## 6. 发版

推送 `v*` tag 即触发 GitHub Actions 自动构建并发布 Release：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

产物：签名 APK、`.deb`、`.msi`、`.dmg`。
也可在 Actions 页面手动 `workflow_dispatch` 并指定版本号。

需要配置 4 个仓库 Secret（`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、
`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）；未配置时会回退到 debug 签名并给出警告。
详见 [docs/MULTIPLATFORM.md](docs/MULTIPLATFORM.md) 第 7 节。

---

## 7. 说明

- 网页源实现依赖各站点当前的 HTML 结构，站点改版后需要同步更新对应 `*Source.kt`。
- 抓取功能请遵守目标站点的服务条款与 robots 约定，仅用于个人阅读。
- 翻译提示词、术语表等均为本地配置，不会上传到除你自己配置的 LLM 接口以外的任何服务。
