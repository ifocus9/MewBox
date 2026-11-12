# MewBox × UZN「网页视频嗅探」移植计划

> 文档版本：v1.0 ｜ 编制：2026-11-10
> 依据：`UZN_网页视频嗅探功能梳理.md`（雷电模拟器实机 UI 探索 + APK 逆向 + 反混淆 + 动态验证）
> 目标仓库：`D:\project\MewBox`（`com.mewbox.tv`，v1.0.3 / versionCode 4，minSdk 26 / targetSdk 35，仅 `arm64-v8a`）
> 前置调研：`D:\project\AVBox`（同代 Kotlin/Compose）、`D:\project\MewPlayer`（Java 上游）、`D:\project\CatPawOpen`（JS 源）
> **本文只做规划，未修改 MewBox 任何文件。**

---

## 0. 一页纸决策摘要

| 项 | 结论 |
| :--- | :--- |
| **核心判断** | UZN 的「网页视频嗅探」是**用户可见的独立浏览器工具页**；MewBox `docs/plan.md:466` 的 P3 待办 `SnifferPlayUrlResolver` 是**播放链路里的无头自动嗅探**。二者不是同一个功能，但共用同一个采集内核。 |
| **推荐路线** | 先建**共用嗅探内核**，一期做形态 A（独立嗅探页，对齐 UZN 体验），二期把同一内核接到形态 B（顺手关掉 P3 待办，修掉 `parse == 1` 只弹 Toast 的残缺逻辑）。 |
| **唯一硬缺口** | MewBox 全仓库 **零匹配** `shouldInterceptRequest` / `onLoadResource` / `WebResourceResponse`。WebView 有、Bridge 有、Cookie/UA/代理有、ExoPlayer HLS 有，唯独「看不见页面发出的资源请求」。 |
| **最大可复用资产** | ① UZN 反混淆后的 `sniffer_deob.js`（纯 JS，20.5 KB，Hook 全网络 API）；② AVBox `player/PlayUrlResolver.kt`（同为 Kotlin，含 generation 防串轮 / 20s 超时 / 广告拦截 / Header 合并）；③ MewBox 自家 `HtmlWebController` + `Players.play()`。 |
| **新增依赖** | **0 个**。全部用现有 Hilt / Coroutines / Flow / Timber / Media3 / Material3；不改 `libs.versions.toml`，不改 `build.gradle.kts` 依赖块。 |
| **建议砍掉** | 「下载」（MewBox 无任何下载器，m3u8 分片合并成本最高）与 `parses/rules/ads` 配置解析（涉及 Room 迁移）。依据 `code.md:21`「APP 仅为我个人使用，功能设计保持克制，优先按最小需求实现」。 |
| **主要风险** | R8 混淆误删 `@JavascriptInterface`（Release 已开 `isMinifyEnabled`）；`shouldInterceptRequest` 高频回调拖慢页面；WebView 生命周期泄漏。 |

---

## 1. 先分清两种「嗅探」（本计划最重要的判断）

### 1.1 形态 A：独立工具页 —— UZN 现在展示的这个

用户主动输入网址 → 内嵌 WebView 真实浏览 → 右下角「已嗅探到 N 个资源」→ 结果抽屉 → 预览 / 复制 / 外部打开。
**特征：有可见网页、有地址栏、有 UA 切换、有结果列表、人来决定用哪条资源。**

### 1.2 形态 B：播放链路内联 —— MewBox 本来就该做的

源返回 `parse == 1` 的网页地址 → 后台起一个**无头 WebView** 加载 → 拦截到首个真实媒体流 → 自动喂给 ExoPlayer。
**特征：用户无感、无 UI、命中即停、20 秒超时、只看第一条命中的 URL。**

MewBox 对此已有明确痕迹：

| 位置 | 现状 |
| :--- | :--- |
| `bean/Models.kt:77,87` | `/** 0 = 直链；1 = 需网页嗅探 */ val parse: Int = 0`；`val needSniff: Boolean get() = parse == 1 \|\| jx == 1` |
| `ui/detail/DetailViewModel.kt:402-404` | 命中 `parse == 1` 只弹 Toast「该线路返回为网页嗅探地址，建议切换其它线路或换源」，随后**仍把网页 URL 交给 ExoPlayer**（必然报格式错误） |
| `docs/plan.md:89` | 预留 `PlayUrlResolver.kt // 播放地址解析器接口（首版直链，预留本地代理流与 WebView 嗅探扩展）` —— **文件实际不存在** |
| `docs/plan.md:419,456,466` | 已勾选「拦截提示」；P3 待办「实现 `SnifferPlayUrlResolver`（内置轻量级 WebView 拦截嗅探 `parse == 1` 的真实媒体流）」 |

### 1.3 对比表

| 维度 | 形态 A（UZN 工具页） | 形态 B（MewBox P3） |
| :--- | :--- | :--- |
| 入口 | 设置页 / 工具入口，用户点进去 | 详情页点剧集时自动触发 |
| WebView | 可见、占满内容区、可交互 | 无头、不可见、`blockNetworkImage = true` |
| 结果 | 多条列表，用户挑 | 第一条命中即回调起播 |
| 生命周期 | 跟随页面，退出才销毁 | 一次解析 20s，命中/超时立即 `destroy()` |
| 去重 | 按 `(url, source)` 保留多来源 | 只取首个 |
| UA/Cookie | 用户可切 UA、可清 Cookie | 继承源站 `Result.header` |
| 共用内核 | ✅ 同一个 `SnifferWebViewClient` + `MediaResourceMatcher` + `SniffSession` | ✅ 同左 |

### 1.4 推荐路线与理由

**P0 内核 → P1 形态 A（MVP）→ P2 形态 A 补全 → P3 形态 B 接线 → P4 可选增强。**

理由：
1. 老大这次说的「这个功能」指的是刚梳理完的 UZN 工具页，**形态 A 是主线**，不能只交一个无头解析器。
2. 内核若只按形态 B 写（命中即停、无列表、无 UA），形态 A 还得重写一遍；按「采集 → 归一化 → 会话状态流」抽象一次，两种形态只是「消费方式」不同。
3. 形态 B 接上后，`DetailViewModel.kt:402` 那段残缺逻辑自然消失，等于顺手还清 P3 技术债。
4. 形态 A 需要一个**可见 WebView**，正好可以作为形态 B 无头模式的调试台：同一套探针，肉眼能看结果，排障成本低一个数量级。

> ⚠️ 不建议的做法：直接把 UZN 的 Flutter 页面「翻译」成 Compose。UZN 是暗色 + `#8B5CF6` 紫、MPV 播放、自带下载器；MewBox 是浅色锁死 + `#217AF4` 蓝、Media3 ExoPlayer、无下载器。UI 与下游能力必须按 MewBox 重做，只有**JS 探针**值得原样搬。

---

## 2. 现状盘点：MewBox 已有什么、缺什么

### 2.1 可直接复用（均已核实文件路径）

| 能力 | 文件 | 复用方式 |
| :--- | :--- | :--- |
| 可见 WebView 容器（完整配置了 JS/DOM/混合内容/免手势播放/Cookie） | `ui/web/fm/HtmlWebController.kt:380-434`、`ui/web/WebActivity.kt:391-485` | 抄 `createWebView()` 的 `WebSettings` 配置块，新建独立实例，**不改原文件** |
| 注入脚本机制（在 `onPageStarted` / `onPageCommitVisible` / `onPageFinished` 三处 `injectSdk`） | `HtmlWebController.kt:437-492,657-659` | 同模式注入 `sniffer_inject.js`，三处注入解决早发请求漏抓 |
| JS Bridge（`addJavascriptInterface`） | `HtmlWebController.kt:433-434`、`ui/web/fm/HomeWebBridge.kt`、`FmJsSdk.kt` | 新增独立 `mewSnifferBridge`，**不污染 `fongmiBridge` 的 30+ action** |
| Cookie / UA / Referer 同步 | `api/net/CookieBridge.kt`、`api/net/WebResourceGateway.kt` | 嗅探命中后补 `CookieManager.getCookie(url)` 进 headers |
| 本地 HTTP 代理（CORS / Range / 自定义头转发） | `api/net/WebResourceGateway.kt:41,66-118`、`api/net/LocalMessageBridge.kt` | 预览跨域受限资源时走 `/webResource` 代理 |
| ExoPlayer + HLS 播放（动态 Header 注入） | `player/Players.kt:231-349`（`play(url, headers, title, startPosition, format)`）、`:700-742`（`inferMimeType` 识别 `.m3u8`/`#EXTM3U`） | 「预览」直接调 `players.play(...)`，**天然支持 m3u8，无 MPV 兼容问题** |
| 播放参数契约 | `bean/Models.kt:70-88`（`Result.header` / `format` / `parse` / `needSniff`） | 形态 B 无需改实体 |
| 路由与导航 | `MainActivity.kt:263-374` `NavHost` | 加一条 `composable("sniffer")` |
| 主题 / 组件 | `ui/theme/Theme.kt:9-11`（`BluePrimary = 0xFF217AF4`）、`ui/components/*`、`ui/player/FullScreenEpisodeDrawer.kt` | 结果抽屉照 `FullScreenEpisodeDrawer` 的形态做 |
| 单测基建 | `app/src/test/` 19 个 JUnit4 + `okhttp.mockwebserver` + `test/resources/samples/` | 匹配器 / 归一化 / 去重全部可纯 JVM 单测 |

### 2.2 唯一核心缺口（根因）

```
MewBox WebViewClient 现有 override：
  onPageStarted / onPageCommitVisible / onPageFinished
  doUpdateVisitedHistory / shouldOverrideUrlLoading
  onReceivedSslError / onReceivedError / onRenderProcessGone
缺失：
  shouldInterceptRequest(view, WebResourceRequest)   ← 看不到 XHR/fetch/img/video 的 URL
  onLoadResource(view, url)                          ← 看不到加载完成的资源
  WebResourceResponse                                ← 无法拦截/屏蔽广告
```

**验证步骤（可复现）**：在 `D:\project\MewBox` 下检索 `shouldInterceptRequest|onLoadResource|WebResourceResponse|WebViewAssetLoader|sniffer` → `app/src/main` 零匹配（仅 `docs/plan.md` 有文字描述）。这就是「WebView 有，但嗅探不到」的根因，不是解析器或播放器的问题。

### 2.3 三份可抄的现成实现（按推荐度排序）

| 优先级 | 来源 | 文件 | 可抄什么 |
| :--- | :--- | :--- | :--- |
| ① 主蓝本 | **AVBox**（同为 Kotlin + Compose） | `app/src/main/java/com/github/avbox/osc/player/PlayUrlResolver.kt` | `Host` 接口(59-80)、`shouldInterceptRequest`(697-713)、`checkIsVideo`(647-692)、`checkVideoFormat`(546-562)、`isSniffRequestOfCurrentRound()`(525-527) 的 **generation 防串轮**、`configWebViewSys()`(564-621) 的无头配置、`PARSE_TIMEOUT_MS = 20s`(718)、命中后 `CookieManager.getCookie` 合并(684-686) |
| ① 配套 | AVBox | `util/DefaultConfig.kt:59-82`（`snifferMatch` 正则）、`util/VideoParseRuler.kt`（host 维度 regex/filter/script/exclude）、`util/AdBlocker.kt` | 媒体 URL 正则库、规则引擎、广告空响应 |
| ② 对齐上游 | **MewPlayer**（Java，`code.md:10` 明确要求「播放器嗅探优先参考」） | `ui/custom/CustomWebView.java`（`shouldInterceptRequest` 130-139、`isVideoFormat` 222-239、`getScript` 200-205 自动点击脚本、`onParseSuccess` 247-251）、`utils/Sniffer.java`（`SNIFFER` 正则 26 行）、`utils/WebSniffHeaders.java`、`api/config/RuleConfig.java`、`impl/ParseCallback.java` | 规则协议、UA 清洗、「命中即回调」契约、`config.json` 的 `rules/ads` 语义 |
| ③ 独有资产 | **UZN**（本次逆向产物） | `uzn_probe/sniffer_deob.js`（反混淆 462 处替换）、`uzn_probe/strings_valid.txt`（235 条字符串）、`uzn_probe/webroot/index.html`（8 类请求测试页） | **JS 层探针**：Hook `fetch` / `XHR` / `TextDecoder.decode` / `MediaSource.appendBuffer`、DOM 扫描、静音自动播放、批量点播放按钮、`MutationObserver`、桥未就绪入队（上限 200）。这是 AVBox / MewPlayer **都没有**的一层，专治 blob/MSE / JS 内存里现造的 m3u8 |
| — 无关 | CatPawOpen | 纯 Node.js / QuickJS 源仓库，无 Android 代码 | 不参与本次移植 |

> `MewPlayer/web/HomeWebController.java:643-646` 还有一个 `shouldInterceptRequest` → `listener.onWebRequest(...)` 的转发写法，MewBox 的 `HtmlWebController` 尚未移植这个回调；若 P4 要让「HTML 网页订阅源」也具备嗅探能力，照它补即可。

### 2.4 差距结论

MewBox 不需要「从零做嗅探」，需要的是：**补一条原生拦截通道 + 移植 UZN 的 JS 探针 + 把两路结果归一化成一个 StateFlow + 加一个工具页**。下游（播放、Header、Cookie、代理）全部现成。

---

## 3. 功能差距矩阵（UZN → MewBox）

| # | UZN 功能 | UZN 实现 | MewBox 现状 | 改造动作 | 复用来源 | 优先级 | 取舍 |
| :-- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | 内嵌 WebView 浏览 | `flutter_inappwebview` | `HtmlWebController` / `WebActivity` 已有 | 新建 `SnifferWebViewFactory`，抄配置块 | MewBox | P0 | 必做 |
| 2 | 地址栏 + 前往 + 粘贴 + 清除 | Flutter TextField | 无 | Compose `OutlinedTextField` + 剪贴板 | Material3 | P1 | 必做 |
| 3 | 自动补 `https://` | Dart 层 | 无 | `normalizeUrl()`，白名单放行 `http://` / `about:blank` / `file://` | 新建 | P1 | 必做（UZN 实测 `about:blank` 会被补成 `https://about:blank9/...`，MewBox 要修掉） |
| 4 | 刷新页面 | `reload()` | 无 | `webView.reload()` | 原生 | P1 | 必做 |
| 5 | **立即嗅探** | Toast「已触发嗅探」+ 手动重扫 | 无 | 注入 `window.__mewSniff.scanNow()`，桥侧 `evaluateJavascript` | UZN | P2 | 必做 |
| 6 | **UA 六选项 + 自定义** | `settings.userAgentString` | 未设 UA | `UaPreset` 枚举 + 自定义输入，`WebView.settings.userAgentString` | UZN 交互 / 原生 API | P2 | 必做（很多站按 UA 决定下发 mp4 还是 m3u8） |
| 7 | 菜单：清空列表 | Dart 层 | 无 | `SniffSession.clear()` | 新建 | P2 | 必做 |
| 8 | 菜单：外部浏览器打开 | `url_launcher` | 无 | `Intent(ACTION_VIEW, Uri.parse(url))` | 原生 | P2 | 必做 |
| 9 | 菜单：清空网页 Cookie | `CookieManager` | `CookieBridge` 已有同步逻辑 | `CookieManager.removeAllCookies{}` + `CookieBridge.flush()` | MewBox | P2 | 必做 |
| 10 | **原生拦截通道** | `shouldInterceptRequest` 等（dex 实测） | **缺失** | 新增 `SnifferWebViewClient` | AVBox / MewPlayer | P0 | 必做 |
| 11 | **JS 探针通道** | `sniffer_inject.js` 混淆版 | 缺失 | 移植 `sniffer_deob.js`，改桥接为 `mewSnifferBridge.report(json)` | UZN | P0 | 必做（blob/MSE 唯一解） |
| 12 | 自动静音点击播放按钮 | `.vjs-big-play-button` / `.dplayer-play` / `[aria-label*="播放"]` | 无 | 随 JS 探针一起搬，工具页菜单给「自动触发播放」开关 | UZN | P2 | 建议做（显著提升命中率） |
| 13 | 结果归一化 / 去重 / 过滤 | Dart 层（实测） | 无 | `SniffSession`：`(url, source)` 去重、按 kind 归一化 | UZN 动态验证结论 | P0 | 必做 |
| 14 | 结果胶囊「已嗅探到 N 个资源」 | Flutter | 无 | Compose 悬浮 `FloatingActionButton.extended` | MewBox 组件 | P1 | 必做 |
| 15 | 结果抽屉（格式/文件名/来源·时间） | Flutter BottomSheet | 有 `FullScreenEpisodeDrawer` 模板 | `ModalBottomSheet` + `LazyColumn` | MewBox | P1 | 必做 |
| 16 | 预览 → 原生播放器 | MPV `NativePlayerActivity`（**模拟器 GL 崩溃**） | ExoPlayer `Players.play()` | 直接调 `players.play(url, headers, ...)` | MewBox | P1 | 必做，**且比 UZN 更稳**（Media3 无 SIGTRAP） |
| 17 | 复制 / 外部打开 | Flutter | 无 | `ClipboardManager` / `Intent` | 原生 | P2 | 必做 |
| 18 | **下载（含 m3u8 合并）** | 内置下载器 | **完全无下载器** | — | — | P4 | **建议砍掉**：仅留复制/外部打开；若只要 MP4，P4 用系统 `DownloadManager`（m3u8 仍不支持） |
| 19 | `parse == 1` 自动解析起播 | 无此概念 | 只弹 Toast | `SnifferPlayUrlResolver` 无头模式 | AVBox | P3 | 建议做（还 P3 债） |
| 20 | `rules` / `ads` / `parses` 配置 | UZN 无 | `config.json` 有字段但 `VodConfig` 忽略 | 扩展 `ResultParser` + `Entities.Config` + Room 迁移 | MewPlayer `RuleConfig` | P4 | 建议砍掉 / 后置 |

---

## 4. 目标架构

### 4.1 分层落点与架构不变量校验

`code.md:59-88` 的硬约束：`ui -> domain/viewModel -> catvod/api/db -> bean`，`player` 严禁依赖 `ui`，`domain` 严禁引入 Android View，`engine` 严禁依赖 `ui/player/domain`。

| 新模块 | 落点 | 是否触碰 Android View | 合规说明 |
| :--- | :--- | :--- | :--- |
| `SniffModels`（纯 data class） | `bean/sniff/` | 否 | `bean` 位于依赖图底层，最干净 |
| `MediaResourceMatcher`（纯判定） | `player/sniff/` | 否 | 纯 Kotlin，无 Android 依赖，可直接 JVM 单测 |
| `SnifferWebViewClient` | `player/sniff/` | 是（`android.webkit.WebViewClient`） | 只依赖 `android.webkit` + `bean` + 回调接口，**不 import `com.mewbox.tv.ui.*`**，不违反「player 严禁依赖 ui」 |
| `SnifferJsBridge` | `player/sniff/` | 否（仅注解） | 同上 |
| `SniffSession`（StateFlow） | `player/sniff/` | 否 | 状态容器，对齐 `Players` 的 `StateFlow<PlayerState>` 模式 |
| `SnifferPlayUrlResolver`（无头） | `player/sniff/` | 是（创建 WebView） | 用 `appContext` 创建，不接触 Compose；与 `docs/plan.md:89` 命名一致 |
| `SnifferWebViewFactory` | `ui/web/sniff/` | 是 | **视图创建归 ui**，与 `ui/web/fm/` 平级 |
| `SnifferScreen` / `SnifferViewModel` / `Components` | `ui/sniff/` | 是 | UI 只消费 `StateFlow`，经 ViewModel 中转（对齐 `DetailViewModel` 注入 `Players` 的既有先例） |
| `assets/sniffer_inject.js` | `app/src/main/assets/` | — | 纯资源，无 Kotlin 依赖 |

> 需在 `.agents/rules/code.md` 第 3 节补一句：「`player/sniff` 为嗅探能力子包，允许使用 `android.webkit`，但不得引用 `com.mewbox.tv.ui`」。这是唯一一处规范补充，不改现有分层。

### 4.2 新增文件清单

| # | 文件（相对 `app/src/main/`） | 职责 | Hilt |
| :-- | :--- | :--- | :--- |
| 1 | `java/com/mewbox/tv/bean/sniff/SniffModels.kt` | `SniffResource` / `SniffKind` / `SniffSource` / `SniffOptions` / `SniffState` | — |
| 2 | `java/com/mewbox/tv/player/sniff/MediaResourceMatcher.kt` | URL + 响应体判定：扩展名、`#EXTM3U`、`video/tos`、16 字节 key 排除、blob/data/javascript 排除 | `@Singleton` |
| 3 | `java/com/mewbox/tv/player/sniff/SniffEventParser.kt` | 解析 JS 桥上报的 JSON → `SniffResource`；kind 归一化；容错脏数据 | `@Singleton` |
| 4 | `java/com/mewbox/tv/player/sniff/SnifferJsBridge.kt` | `@JavascriptInterface report(json: String)` / `ready()` / `scanDone()` | 会话新建 |
| 5 | `java/com/mewbox/tv/player/sniff/SniffSink.kt` | 采集层与会话层之间的回调接口（解耦，便于单测） | — |
| 6 | `java/com/mewbox/tv/player/sniff/SnifferWebViewClient.kt` | `shouldInterceptRequest` / `onLoadResource` / 三处注入探针 / 广告空响应（P4） | 会话新建 |
| 7 | `java/com/mewbox/tv/player/sniff/SniffSession.kt` | 队列上限 200、`(url, source)` 去重、过滤、generation 防串轮、`StateFlow<SniffState>` | 会话级（ViewModel 持有） |
| 8 | `java/com/mewbox/tv/player/sniff/SnifferPlayUrlResolver.kt` | 形态 B：无头 WebView + 20s 超时 + 首条命中回调 | `@Singleton` |
| 9 | `java/com/mewbox/tv/ui/web/sniff/SnifferWebViewFactory.kt` | 创建并配置可见 WebView（抄 `HtmlWebController:392-420`），挂 client + bridge | — |
| 10 | `java/com/mewbox/tv/ui/sniff/SnifferViewModel.kt` | 持有 `SniffSession`，编排 URL 加载、UA、菜单动作、预览 | `@HiltViewModel` |
| 11 | `java/com/mewbox/tv/ui/sniff/SnifferScreen.kt` | 页面骨架：`BackHandler` + `AndroidView` + 结果胶囊 + 抽屉 | — |
| 12 | `java/com/mewbox/tv/ui/sniff/SnifferUiState.kt` | 集中状态容器（UDF，私有 `MutableStateFlow`） | — |
| 13 | `java/com/mewbox/tv/ui/sniff/components/SnifferTopBar.kt` | 返回 / 标题 / 刷新 / 立即嗅探 / UA / 更多 | — |
| 14 | `java/com/mewbox/tv/ui/sniff/components/SnifferAddressBar.kt` | 输入框 + 粘贴 + 清除 + 前往 | — |
| 15 | `java/com/mewbox/tv/ui/sniff/components/SniffResultSheet.kt` | `ModalBottomSheet` + 条目 + 操作按钮 | — |
| 16 | `java/com/mewbox/tv/ui/sniff/components/UaPickerDialog.kt` | 六选项 + 自定义输入 | — |
| 17 | `assets/sniffer_inject.js` | 由 `uzn_probe/sniffer_deob.js` 改造：抹掉 Flutter/Windows/iOS 分支，统一走 `mewSnifferBridge` | — |
| 18 | `app/src/test/java/com/mewbox/tv/sniff/*.kt` | 5 个单测类（见第 8 节） | — |

### 4.3 修改文件清单（修改前 / 修改后）

| 文件 | 修改前 | 修改后 | 影响面 |
| :--- | :--- | :--- | :--- |
| `MainActivity.kt:263-374` `NavHost` | 5 条路由：`main` / `settings` / `search` / `detail` / `play` | 新增 `composable("sniffer") { SnifferScreen(onBack = ...) }` | 低，仅加一条 |
| `ui/setting/` 设置页 | 无工具入口 | 加一行「网页视频嗅探」入口 → `nav.navigate("sniffer")` | 低 |
| `ui/detail/DetailViewModel.kt:402-404` | `parse == 1` → 弹 Toast，仍把网页 URL 交给 ExoPlayer | 改为 `resolver.resolve(result.url, result.header) { found, headers -> players.play(...) }`；失败回落原 Toast + `isResolvingPlay = false` | **中**，需回归播放链路 |
| `app/proguard-rules.pro` | 无 JS 桥 keep 规则 | 追加 `-keepattributes JavascriptInterface` 与 `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }` | **高**：漏了 Release 包嗅探全废，Debug 正常（最坑的坑） |
| `AndroidManifest.xml` | `WebActivity` 已注册 | 无需改（嗅探页是 Compose 路由，不是新 Activity） | 无 |
| `docs/plan.md:419,466` | P3 待办 / 「拦截提示已勾选」 | 更新为已实现，补嗅探章节 | 文档 |
| `.agents/rules/code.md` | 无 sniff 包约定 | 补 1 条（见 4.1） | 文档 |
| `bean/Models.kt` / `Entities.kt` / `AppDatabase.kt` / `ResultParser.kt` | `rules/ads/parses` 忽略 | **P4 才动**，需 `MIGRATION_7_8` | 高，故后置 |
| `libs.versions.toml` / `app/build.gradle.kts` | — | **不改**（零新依赖） | 无 |

### 4.4 数据流（形态 A 时序）

```
用户输入 URL
  → SnifferViewModel.load(normalizeUrl(input))
  → SnifferWebViewFactory 创建 WebView（loadUrl 之前 addJavascriptInterface）
  → addJavascriptInterface(SnifferJsBridge, "mewSnifferBridge")
  → wv.loadUrl(url)
      ├─ onPageStarted      → evaluateJavascript(sniffer_inject.js)   ← 早注入，抓早期 fetch/XHR
      ├─ onPageCommitVisible→ 再注入（幂等标志 __MEW_SNIFFER_INSTALLED__）
      ├─ onPageFinished     → 再注入 + scanNow() + CookieBridge.flush()
      ├─ shouldInterceptRequest(request)  ─┐
      │    取 url / UA / Referer / Origin │→ SniffSink.onNative(url, headers, "native")
      └─ JS 探针 Hook fetch/XHR/Decoder/MSE/DOM → mewSnifferBridge.report(json)
                                                → SniffEventParser → SniffSink.onJs(event)
  → SniffSession：generation 校验 → 过滤 → 归一化 → (url,source) 去重 → StateFlow 发射
  → SnifferUiState.resources → 右下角胶囊 / 抽屉列表
  → 用户点「预览」→ players.play(url, headers + Cookie, title, format)
  → ExoPlayer（m3u8 走 HlsMediaSource.Factory，Player.kt:304-307）
```

形态 B 的差异：把 `SnifferViewModel` 换成 `SnifferPlayUrlResolver`，WebView 无头且 `blockNetworkImage = true`，`SniffSession` 配 `firstOnly = true` + 20s 超时，首条命中即 `stopLoading()` → `destroy()` → 回调 `DetailViewModel`。

### 4.5 数据模型（Kotlin 骨架）

```kotlin
// bean/sniff/SniffModels.kt
enum class SniffKind(val label: String) {
    M3U8("M3U8"), MP4("MP4"), MKV("MKV"), FLV("FLV"),
    TS("TS"), MP3("MP3"), M4A("M4A"), AAC("AAC"),
    MPD("MPD"), OGG("OGG"), WEBM("WEBM"), UNKNOWN("未知")
}

/** 采集通道。UZN 动态验证证实：同一 URL 可同时来自两条通道，不按 URL 硬去重。 */
enum class SniffSource(val label: String) {
    DOM("dom"), FETCH("fetch"), XHR("xhr"), DECODE("decode"),
    MSE("mse"), NATIVE("native")
}

data class SniffResource(
    val url: String,
    val kind: SniffKind,
    val source: SniffSource,
    val headers: Map<String, String> = emptyMap(),
    val caption: String = "",          // 文件名，取自 URL path 末段
    val sizeBytes: Long? = null,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val snippet: String? = null,       // m3u8 前 120 字节，用于判定与展示
) {
    /** 仅视频类允许「预览 / 下载」；音频只允许预览与复制（对齐 UZN 实测：OGG 无下载按钮） */
    val isVideo: Boolean get() = kind in VIDEO_KINDS
    val dedupeKey: String get() = "$url|$source"

    companion object {
        val VIDEO_KINDS = setOf(
            SniffKind.M3U8, SniffKind.MP4, SniffKind.MKV, SniffKind.FLV,
            SniffKind.TS, SniffKind.MPD, SniffKind.WEBM
        )
    }
}

data class SniffOptions(
    val userAgent: String? = null,
    val autoTriggerPlay: Boolean = true,
    val maxItems: Int = 200,           // 对齐 UZN 桥未就绪入队上限
    val firstOnly: Boolean = false,    // 形态 B 置 true
    val timeoutMs: Long = 20_000L,     // 对齐 AVBox PARSE_TIMEOUT_MS
)

data class SniffState(
    val resources: List<SniffResource> = emptyList(),
    val currentUrl: String = "",
    val pageTitle: String = "网页视频嗅探",
    val isLoading: Boolean = false,
    val isSniffing: Boolean = false,
    val error: String? = null,
)
```

### 4.6 核心接口签名

```kotlin
// player/sniff/SniffSink.kt
interface SniffSink {
    fun onNative(url: String, headers: Map<String, String>)
    fun onJsEvent(json: String)
    fun onPageError(url: String, message: String)
}

// player/sniff/MediaResourceMatcher.kt
@Singleton
class MediaResourceMatcher @Inject constructor() {
    /** 纯 URL 判定，O(1)，禁止在 shouldInterceptRequest 内读响应体 */
    fun match(url: String, contentType: String? = null): SniffKind?
    /** 响应体判定，仅用于 JS 探针上报的 snippet（#EXTM3U / 16 字节 AES key） */
    fun matchSnippet(url: String, snippet: String): SniffKind?
    fun isExcluded(url: String): Boolean      // blob:/data:/javascript:/favicon/.html/广告域名
    fun isHlsKey(snippet: String): Boolean    // 16 字节 → true，不进结果列表
}

// player/sniff/SniffSession.kt
class SniffSession(
    private val options: SniffOptions,
    private val matcher: MediaResourceMatcher,
    private val parser: SniffEventParser,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SniffState())
    val state: StateFlow<SniffState> = _state.asStateFlow()
    fun nextGeneration(): Int
    fun accept(url: String, source: SniffSource, headers: Map<String, String>, snippet: String? = null)
    fun acceptJs(json: String)
    fun clear()
    fun close()
}

// player/sniff/SnifferPlayUrlResolver.kt
@Singleton
class SnifferPlayUrlResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val matcher: MediaResourceMatcher,
) {
    /** 形态 B 唯一入口。返回 null 表示超时/未命中；回调在命中后立即触发并销毁 WebView。 */
    suspend fun resolve(
        pageUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000L,
    ): SniffResource?
}
```

### 4.7 JS ↔ Kotlin 桥协议

注入脚本统一上报（**抹掉 UZN 的 `iaw` / `win` / `linux` 三分支**）：

```javascript
// 替换 uzn_probe/sniffer_deob.js:182-244, 958-961 的平台分发逻辑
function report(evt) {
  if (window.mewSnifferBridge && typeof window.mewSnifferBridge.report === 'function') {
    window.mewSnifferBridge.report(JSON.stringify(evt))
  } else {
    (window.__mewSniffQueue = window.__mewSniffQueue || []).push(evt)
    if (window.__mewSniffQueue.length > 200) window.__mewSniffQueue.shift()
  }
}
```

JSON 事件 schema（对齐 UZN 实测 kind）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `kind` | String | `dom` / `fetch` / `fetch-m3u8` / `fetch-key` / `xhr` / `xhr-m3u8` / `xhr-key` / `mse` / `decode-m3u8` / `page-error` |
| `url` | String | 资源 URL；`decode-m3u8` 时 UZN 误填 `location.href`，MewBox 需修正（见 5.4） |
| `method` | String? | GET / POST |
| `headers` | Object? | 仅保留 UA / Referer / Origin / Cookie |
| `snippet` | String? | 响应体前 120 字节，`#EXTM3U` 判定用 |
| `size` | Number? | 响应体字节数；16 → HLS AES key |
| `ts` | Number | 毫秒时间戳 |

---

## 5. 双通道采集设计（技术核心）

### 5.1 通道一：原生拦截（MewBox 必补）

```kotlin
override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
    val url = request.url.toString()
    if (!isCurrentGeneration()) return null
    if (matcher.isExcluded(url)) return null

    // 只保留播放需要的三个头；AVBox PlayUrlResolver.kt:704-710 的同款处理
    val headers = request.requestHeaders
        .filterKeys { it.equals("User-Agent", true) || it.equals("Referer", true) || it.equals("Origin", true) }
        .mapValues { (_, v) -> " $v" }

    val kind = matcher.match(url, request.requestHeaders["Accept"]) ?: return null
    sink.onNative(url, headers + mapOf("X-Mew-Sniff-Kind" to kind.name))

    // 形态 A：返回 null，让请求继续加载（不打断用户浏览）
    // 形态 B：命中后由 resolver 立即 stopLoading() + destroy()
    return null
}
```

**性能红线**：`shouldInterceptRequest` 对页面每个资源都回调。方法体内只允许做字符串判定和一次 `sink` 调用，**严禁**发网络请求、读响应体、切线程、打高频 `Timber.d`（改为采样计数）。日志用 `Timber.w` 只打命中项。

### 5.2 通道二：JS 探针（UZN 独有，专治原生看不到的数据）

| Hook 点 | 抓什么 | 原生通道能否替代 |
| :--- | :--- | :--- |
| `window.fetch` | fetch 发出的 m3u8 / 分片 / 密钥 | 部分能（URL 层面），但**看不到响应体里的 `#EXTM3U`** |
| `XMLHttpRequest.open/send` | 老站点的 XHR 流地址 | 同上 |
| `TextDecoder.decode` | **JS 内存里现造的播放列表**（页面自己拼 m3u8 字符串，从未发网络请求） | ❌ 完全不能 |
| `MediaSource.addSourceBuffer().appendBuffer` | blob / MSE 分片流 | ❌ 完全不能 |
| `video/audio/source` 的 `src` / `currentSrc` | DOM 直挂的媒体 | 能 |
| `MutationObserver` + 多次定时扫描 | 异步插入的元素 | ❌ 不能 |
| 静音 `play()` + 批量点播放按钮 | 触发懒加载，让资源请求真的发出 | ❌ 不能 |

### 5.3 kind 全表与归一化（依据动态验证实测）

| JS 探针 kind | 判定条件 | MewBox 归一化 `SniffSource` | 是否进结果列表 |
| :--- | :--- | :--- | :--- |
| `dom` | `<video>/<audio>/<source>` 的 src，排除 blob/data/javascript | `DOM`（UZN 显示为 `native`） | ✅ |
| `fetch` | fetch 响应，非媒体内容 | `FETCH` | ❌（非媒体，过滤） |
| `fetch-m3u8` | fetch 响应体以 `#EXTM3U` 开头 | `FETCH` | ✅ |
| `fetch-key` | fetch 响应 16 字节 | — | ❌（HLS AES 密钥） |
| `xhr` / `xhr-m3u8` / `xhr-key` | 同上，走 XHR | `XHR` / — | 同上 |
| `mse` | `appendBuffer` 分片 | `MSE` | ❌（分片，只用于标记存在） |
| `decode-m3u8` | `TextDecoder` 解出 `#EXTM3U` | `DECODE` | ⚠️ 见 5.4 |
| `page-error` | `window.onerror` | — | ❌（仅 Timber.w） |
| 原生 `shouldInterceptRequest` | URL 正则命中 | `NATIVE` | ✅ |

> **动态验证实录**（本地测试页主动发 8 类请求）：页面日志 `decode-m3u8 46 | xhr-m3u8 117 | xhr-key 16 | xhr 20 | fetch-m3u8 117 | fetch-key 16 | fetch 15 | mse 64`，UZN 抽屉只显示 5 条：`M3U8 a.m3u8 (fetch)`、`M3U8 a.m3u8 (native)`、`M3U8 b.m3u8 (xhr)`、`HTML index.html (decode)`、`MP4 clip.mp4 (native)`。证明 **Dart 层有归一化 + 过滤 + 双通道并行**，MewBox 照此实现。

### 5.4 去重、过滤与修正 UZN 已知缺陷

| 规则 | 做法 |
| :--- | :--- |
| 去重键 | `url + "|" + source`，**不按 URL 硬去重**（实测 `a.m3u8` 同时出现 `fetch` 与 `native` 两条，都是有效信息） |
| 过滤 | 16 字节 key、普通 json/txt、mse 分片、广告域名、`favicon.ico`、`.html`（除非确认是 m3u8 内容） |
| 队列上限 | 200，超出丢弃最旧（对齐 UZN 桥未就绪入队上限） |
| generation 防串轮 | 每次 `loadUrl` / `reload` 递增 `AtomicInteger`；回调携带 gen，不等于当前 gen 直接丢弃（照抄 AVBox `isSniffRequestOfCurrentRound():525-527`），**防止上一页的延迟请求污染新页结果** |
| **修正 UZN 缺陷 ①** | `decode-m3u8` 上报的 `url` 是 `location.href`，UZN 因此把 m3u8 显示成 `HTML index.html`。MewBox 改为：生成合成 URL `mewsniff://inline/<ts>.m3u8`，把 `snippet` 全文存进 `SniffResource.snippet`，列表标签显示 `M3U8 · 内联播放列表`，预览时走 `WebResourceGateway` 本地代理喂给 ExoPlayer |
| **修正 UZN 缺陷 ②** | UZN 对裸地址无脑补 `https://`，`about:blank` 被补成 `https://about:blank9/index.html`。MewBox `normalizeUrl()` 对 `about:` / `data:` / `file:` / `http://` / `https://` 白名单直接放行 |
| **修正 UZN 缺陷 ③** | UZN 预览跳 MPV，在雷电模拟器 GL 线程 `SIGTRAP` 崩溃（`MpvNative_renderContextCreate`），App 被杀。MewBox 用 Media3 ExoPlayer，**天然规避**，预览前把 `CookieManager.getCookie(url)` 并入 headers |

### 5.5 自动触发播放

随 JS 探针移植 UZN 的选择器集合（`.vjs-big-play-button`、`.dplayer-play`、`[aria-label*="播放"]` 等），逻辑：先把所有 `video/audio` 静音 + `muted = true` → `play().catch(() => {})` → 再批量点击播放按钮 → 触发资源请求。

工具页菜单提供「自动触发播放」开关（默认开，纯 UI 局部状态，不入库）。无头形态 B 恒开。

### 5.6 注入时机与防重复

| 时机 | 动作 | 原因 |
| :--- | :--- | :--- |
| `addJavascriptInterface` | **必须在 `loadUrl` 之前** | 否则桥不存在，早发事件丢失 |
| `onPageStarted` | 首次 `evaluateJavascript(探针)` | 抓早期 fetch/XHR（UZN 只在 `onPageFinished` 注入，会漏） |
| `onPageCommitVisible` | 二次注入（幂等） | 对齐 `HtmlWebController.kt:454-468` 现有模式 |
| `onPageFinished` | 三次注入 + `scanNow()` + `CookieBridge.flush()` | DOM 完整后扫描 `<video>` |
| 幂等标志 | `window.__MEW_SNIFFER_INSTALLED__` | 对齐 UZN `__UZN_SNIFFER_INSTALLED__` |
| 桥未就绪队列 | `window.__mewSniffQueue`，桥 ready 后一次性 flush（上限 200） | 对齐 UZN |

---

## 6. UI 方案（按 MewBox 视觉规范，不照搬 UZN）

### 6.1 页面与入口

- 路由：`composable("sniffer")`，`SnifferScreen(onBack = { nav.popBackStack() })`
- 入口：设置页新增一行「网页视频嗅探」（图标用 Material3 `Icons.Outlined.Radar` 或现有图标集，`code.md:20` 要求不单独写 XML 图标）
- 不做底部 Tab（`code.md:21` 克制原则，工具页够用即可）

### 6.2 控件清单（对齐 UZN 功能，标 MewBox 组件）

| UZN 元素 | MewBox 实现 | 说明 |
| :--- | :--- | :--- |
| 顶栏标题 | `GlassTopBar`（`ui/components/` 现成） | 加载后显示网页 `<title>` |
| 刷新 / 立即嗅探 / UA / 更多 | `IconButton` × 4 | 复用 Material3 图标，contentDescription 写中文 |
| 地址栏 | `OutlinedTextField` + 末尾粘贴 / 清除 `IconButton` + 「前往」`Button` | 支持 IME `ImeAction.Go` |
| 网页区 | `AndroidView(factory = { SnifferWebViewFactory.create(ctx, client, bridge) })` | `DisposableEffect` 里 `destroy()`，防泄漏 |
| 结果胶囊 | `ExtendedFloatingActionButton`（右下） | 文案「已嗅探到 N 个资源」，N=0 时隐藏 |
| 结果抽屉 | `ModalBottomSheet` + `LazyColumn` | 条目：格式标签 / 文件名 / `source · HH:mm:ss` / 预览·复制·外部打开 |
| 空态 | 「暂未嗅探到媒体资源 / 播放视频后结果会出现在这里」 | 沿用 UZN 文案 |
| UA 弹窗 | `AlertDialog` 单选列表 + 自定义输入 | 六项对齐 UZN |
| 更多菜单 | `DropdownMenu` 三项 | 清空列表 / 外部浏览器打开 / 清空网页 Cookie（红色） |

### 6.3 状态机

```
IDLE ──load(url)──> LOADING ──onPageStarted──> SNIFFING
                       │                          │
                       │ 加载失败                  ├─ accept() → SNIFFING（累计 N）
                       ▼                          ├─ reload() → nextGeneration() → LOADING
                     ERROR                        └─ clear() → SNIFFING（N=0）
                       │                          │
                       └── 重试 ──> LOADING        └── onBack / dispose → DESTROYED
```

所有状态变更只在 `SnifferViewModel` / `SniffSession` 内发生，UI 只读 `StateFlow`（`code.md:91-109` UDF）。

### 6.4 视觉差异表（明确不照搬的部分）

| 项 | UZN | MewBox |
| :--- | :--- | :--- |
| 主题 | 暗色 | **浅色锁死**（`MainActivity.kt:149` `darkTheme = false`，`code.md:19` 强制不受系统深色影响） |
| 强调色 | `#8B5CF6` 紫 | `#217AF4` 蓝（`Theme.kt:9`），角标 `OrangeBadge = #FA8C16` |
| 背景 | 深色 | `#EDF1F7`（`Theme.kt:27-40`） |
| 组件 | Flutter Material | Material3 + `libs/backdrop` + `Capsule` |
| 播放器 | MPV 原生 Activity | Media3 ExoPlayer 内联视窗 / `DirectPlayScreen` |
| 下载 | 有 | **无**（砍掉，见第 10 节） |
| 文案 | 部分繁体（「輸入自訂 User-Agent」） | 简体中文（`code.md:13,35-38`） |

---

## 7. 分阶段实施 WBS

> 每阶段独立可编译、可回退；回退点 = 上一阶段结束时的 git commit。

| 阶段 | 目标 | 主要改动 | 交付物 | 验收 | 回退点 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **P0 内核** | 采集与判定能力，无 UI | 新增 1-8 号文件 + `assets/sniffer_inject.js` + `proguard-rules.pro` | 可单测的 `SniffSession` | `./gradlew testDebugUnitTest` 全绿；新增 5 个单测通过 | 只加文件，删除即回退 |
| **P1 MVP 工具页** | 形态 A 最小可用：输入网址 → 浏览 → 结果列表 → 预览 | 新增 9-15 号文件；改 `MainActivity.kt` + 设置页 | 可见嗅探页 | 真机输入 `https://www.w3schools.com/html/html5_video.asp` → 出现 `mov_bbb.mp4` / `mov_bbb.ogg` → 点预览能播 | 注释掉路由与入口 |
| **P2 交互补全** | UA / 立即嗅探 / 菜单 / 复制 / 外部打开 / 自动播放开关 | 新增 16 号文件；扩展 ViewModel | 对齐 UZN 全部非下载功能 | 8 项逐条走查（见 8.3） | 关开关即降级 |
| **P3 形态 B** | 打通 `parse == 1` 自动解析 | 改 `DetailViewModel.kt:402-404`；启用 `SnifferPlayUrlResolver` | P3 技术债清零 | 找一条返回 `parse=1` 的线路，能自动起播；20s 超时友好提示 | 保留旧 Toast 分支作为 fallback |
| **P4 可选** | 下载（仅 MP4）/ 广告拦截 / `rules`/`ads` 配置 / HTML 源也支持嗅探 | `DownloadManager` / `AdBlocker` / `ResultParser` + Room 迁移 / `HtmlWebController` 加转发 | 增强项 | 单独评估，**默认不做** | — |

### 7.1 P0 详细步骤

1. 从 `uzn_probe/sniffer_deob.js` 复制一份到 `app/src/main/assets/sniffer_inject.js`。
2. 改写平台分发段（第 182-244、958-961 行）：删除 `iaw` / `win` / `linux` 判定，统一 `report()` 走 `mewSnifferBridge`；把 `__UZN_SNIFFER_INSTALLED__` 改名为 `__MEW_SNIFFER_INSTALLED__`。
3. 新建 `bean/sniff/SniffModels.kt`（4.5 代码）。
4. 新建 `MediaResourceMatcher.kt`：正则表抄 `AVBox/util/DefaultConfig.kt:59-82` 的 `snifferMatch` + `MewPlayer/utils/Sniffer.java:26` 的 `SNIFFER`，补 `#EXTM3U` 与 16 字节 key 判定。
5. 新建 `SniffEventParser.kt`：`Json { ignoreUnknownKeys = true; isLenient = true }`，脏字段一律降级不抛。
6. 新建 `SnifferJsBridge.kt`：`@JavascriptInterface fun report(json: String)` → `sink.onJsEvent(json)`；`fun ready()` → flush 队列。
7. 新建 `SniffSink.kt` 接口。
8. 新建 `SnifferWebViewClient.kt`：照 5.1 / 5.6 实现；注入脚本用 `context.assets.open("sniffer_inject.js").bufferedReader().use { it.readText() }`，结果在 `SnifferWebViewFactory` 里缓存一次，避免每页重复读。
9. 新建 `SniffSession.kt`：`ConcurrentHashMap<String, SniffResource>` + `AtomicInteger generation`，`accept()` 在 `Dispatchers.Default` 做判定、`_state.update` 回主线程。
10. 新建 `SnifferPlayUrlResolver.kt`（P0 只搭骨架，P3 接线）。
11. **改 `proguard-rules.pro`**（不可漏）。
12. 补 5 个单测（第 8 节），跑 `./gradlew testDebugUnitTest`。

### 7.2 P1 详细步骤

1. `SnifferWebViewFactory.kt`：复制 `HtmlWebController.kt:392-420` 的 `WebSettings` 配置块；`setBackgroundColor(Color.WHITE)`（对齐浅色主题）；`addJavascriptInterface(bridge, "mewSnifferBridge")` 在 `loadUrl` 之前。
2. `SnifferViewModel.kt`：`@HiltViewModel`，注入 `SniffSession` / `Players` / `MediaResourceMatcher`；暴露 `StateFlow<SnifferUiState>`；Action：`load(url)` / `reload()` / `scanNow()` / `setUa()` / `clear()` / `clearCookies()` / `preview(resource)` / `copy(resource)` / `openExternal()`。
3. `SnifferScreen.kt`：`BackHandler` + `AndroidView` + `DisposableEffect { onDispose { webView.destroy(); session.close() } }`。
4. `SniffResultSheet.kt`：条目右侧按钮按 `isVideo` 决定显示范围。
5. `MainActivity.kt` 加路由；设置页加入口。
6. `preview()` 实现：`CookieManager.getInstance().getCookie(url)` 并入 headers → `players.play(url, headers, title, format = ...)` → 导航到现有播放视窗（复用 `VideoPlayerViewport` 或 `navigate("play/...")`，按现有播放页承载方式决定）。

### 7.3 P3 详细步骤（形态 B）

1. `DetailViewModel.kt:402`：把 `if (result.parse == 1) { toast }` 改为 `if (result.needSniff) { 起 resolver，成功后 players.play；失败回落原 Toast }`，并跳过原来的 `players.play(result.url, ...)`。
2. `SnifferPlayUrlResolver.resolve()`：无头 WebView（照 `AVBox:564-621` `configWebViewSys`，`blockNetworkImage = true`）、`SniffOptions(firstOnly = true, timeoutMs = 20_000)`、`Handler(Looper.getMainLooper())` 超时、`withTimeoutOrNull` 包裹。
3. 命中后 `stopLoading()` → `loadUrl("about:blank")` → `destroy()`（照 `AVBox:529-544`），避免内存泄漏。
4. 更新 `docs/plan.md` P3 状态。

---

## 8. 测试与验证

### 8.1 新增单测（纯 JVM，不需要模拟器）

| 测试类 | 覆盖 | 断言要点 |
| :--- | :--- | :--- |
| `MediaResourceMatcherTest` | URL 正则 / `#EXTM3U` / 16 字节 key / blob 排除 / 广告域名 | 参数化 30+ 组，含 UZN 实测 `a.m3u8`、`clip.mp4`、`mov_bbb.ogg` |
| `SniffEventParserTest` | 10 种 kind JSON、脏数据、缺字段、非法 JSON | 不抛异常；`decode-m3u8` 的 `url` 被改写为合成 URL |
| `SniffSessionDedupeTest` | `(url, source)` 去重、200 上限、generation 防串轮 | 同 URL 不同 source 保留 2 条；旧 gen 事件被丢弃 |
| `UrlNormalizeTest` | `about:blank` / `http://` / 裸域名 / 中文关键词 | `about:blank` **不**被补成 `https://about:blank9/...`（UZN 缺陷回归） |
| `SniffFilterTest` | 非媒体资源过滤 | 16 字节 key / json / txt / mse 分片 / favicon 均不进列表 |

样本放 `app/src/test/resources/samples/sniff/`，直接复用 `uzn_probe/webroot/` 的 8 类请求 payload。

### 8.2 本地测试页复用（现成资产）

`C:\Users\admin\WorkBuddy\2026-11-10-14-55-49\uzn_probe\webroot\index.html` 已能主动发出 8 类请求并在页面内打印命中日志，是这次动态验证的产物。真机联调：

```bash
# 1) 宿主机起测试页（Python 3.12 锁定）
"C:/Users/admin/AppData/Local/Programs/Python/Python312/python.exe" -m http.server 8899 \
  --bind 127.0.0.1 --directory "C:/Users/admin/WorkBuddy/2026-11-10-14-55-49/uzn_probe/webroot"

# 2) 端口转发（真机/模拟器通用）
adb reverse tcp:8899 tcp:8899

# 3) MewBox 嗅探页输入
http://127.0.0.1:8899/index.html

# 4) 预期：8 类请求日志全部命中，结果抽屉约 5 条（fetch/native/xhr/decode/native）

# 5) 收尾
adb reverse --remove tcp:8899
```

### 8.3 真机验收清单

| # | 用例 | 预期 |
| :-- | :--- | :--- |
| 1 | 打开嗅探页 | 空白页 + 地址栏占位「输入网址或搜索关键词」，无结果胶囊 |
| 2 | 输入 `www.w3schools.com/html/html5_video.asp` | 自动补 `https://` 并加载 |
| 3 | 等 5s | 出现「已嗅探到 2 个资源」，抽屉含 `MP4 mov_bbb.mp4` / `OGG mov_bbb.ogg` |
| 4 | 点 MP4「预览」 | ExoPlayer 起播，**不崩溃**（对照 UZN 的 MPV `SIGTRAP`） |
| 5 | 切 UA 为 iPhone 后刷新 | `settings.userAgentString` 生效，页面布局变化 |
| 6 | 点「立即嗅探」 | Toast「已触发嗅探」，DOM 重扫 |
| 7 | 菜单「清空列表」 | 胶囊消失 |
| 8 | 菜单「清空网页 Cookie」→ 刷新 | Cookie 清空，`CookieBridge` 状态一致 |
| 9 | 输入 `about:blank` | 显示空白页，**不**跳到 `https://about:blank9/...` |
| 10 | 输入本地测试页 | 8 类请求日志全命中，抽屉出现 `fetch` / `xhr` / `native` 多来源条目 |
| 11 | 反复切换网址 5 次 | 结果不串页（generation 生效），无内存增长 |
| 12 | **Release 包**重复用例 3 | 嗅探仍生效（验证 R8 keep 规则） |
| 13 | P3：触发 `parse == 1` 线路 | 20s 内自动起播；超时给友好提示，不把网页 URL 喂给 ExoPlayer |

### 8.4 回归清单（改动影响面）

- 订阅导入 → 首页 → 搜索 → 详情 → 播放 → 切线路 → 换源 → 历史续播（`docs/plan.md:435` 原有核心回归）
- HTML 网页订阅源 `HtmlSourceScreen` 显示与 `fongmiBridge` 30+ action（本计划不改该 Controller，应完全无影响）
- `adb shell dumpsys meminfo com.mewbox.tv` 反复进出嗅探页 10 次，确认 WebView 已 destroy、无 Activity 泄漏

---

## 9. 风险登记与对策

| # | 风险 | 根因 / 现象 | 对策 | 验证方式 |
| :-- | :--- | :--- | :--- | :--- |
| R1 | **Release 包嗅探全废，Debug 正常** | `isMinifyEnabled = true`，R8 重命名/移除 `@JavascriptInterface` 方法 | `proguard-rules.pro` 加 keep 规则（4.3） | 必须打 `assembleRelease` 真机跑用例 12 |
| R2 | 页面卡顿 / ANR | `shouldInterceptRequest` 每资源回调，若在其中读响应体或高频打日志 | 方法体只做 O(1) 字符串判定；日志仅打命中项；命中统计采样 | 本地测试页 200 请求，帧率与耗时对比 |
| R3 | 结果串页 | 上一页延迟到达的 XHR/fetch 混入新页 | `AtomicInteger generation`，回调携带 gen 校验（AVBox:525-527） | 单测 `SniffSessionDedupeTest` + 真机用例 11 |
| R4 | WebView 内存泄漏 | 可见 WebView 未 `destroy()` / 无头 WebView 命中后未清理 | `DisposableEffect.onDispose`；resolver 命中/超时统一 `stopLoading → about:blank → destroy`（AVBox:529-544） | `dumpsys meminfo` 反复进出 10 次 |
| R5 | m3u8 预览 403 | 缺 Referer / UA / Cookie | 原生拦截时收集三头；命中后补 `CookieManager.getCookie`；必要时走 `WebResourceGateway` 代理 | 用带防盗链的测试页验证 |
| R6 | blob / MSE 抓不到 | 原生只看到 `blob:` URL | 必须保留 UZN JS 探针的 `MediaSource.appendBuffer` + `TextDecoder` Hook | 本地测试页 `mse 64` 日志命中 |
| R7 | `decode-m3u8` 显示成 HTML | UZN 上报 `location.href` 的缺陷 | 合成 `mewsniff://inline/*.m3u8` + 存全文 snippet + 本地代理喂 ExoPlayer（5.4） | 单测 `SniffEventParserTest` |
| R8 | 自动点击播放误触页面 | UZN 探针批量点播放按钮 | 默认开，工具页提供开关；无头模式恒开；选择器限定播放类 | 常见站点人工走查 |
| R9 | 形态 B 改动影响播放主链路 | `DetailViewModel.kt:402` 是起播必经分支 | 新逻辑包在 `runCatching` + 超时回落原 Toast；先灰度（只对 `parse == 1` 生效） | 回归清单 8.4 全量 |
| R10 | 架构违规 | 嗅探模块若放 `ui` 或让 `player` 反向依赖 `ui` | 按 4.1 落点；`SniffSink` 接口解耦；`code.md` 补 1 条 | PR 自查 import：grep `com.mewbox.tv.ui` 在 `player/sniff/` 下应为 0 |
| R11 | WebView 版本差异 | 雷电模拟器是 WebView 91，真机可能更高；`shouldInterceptRequest` 行为略有差异 | 只用 API 21+ 稳定签名；不依赖 `WebMessageListener` 等新 API | 模拟器 + 真机双跑 |
| R12 | R8 混淆 JS 资产 | `assets/sniffer_inject.js` 一般不受影响，但需确认未被 `shrinkResources` 剔除 | 确认 `isShrinkResources` 对 assets 行为；必要时放 `res/raw/` | Release 包 `unzip -l` 检查文件存在 |

---

## 10. 明确不做的事（克制原则，依据 `code.md:21`）

| 不做 | 理由 |
| :--- | :--- |
| 内置下载器 / m3u8 分片合并 | MewBox 零下载基建；改造成本远高于功能价值；用「复制 + 外部打开」交给第三方工具即可。若 P4 只要 MP4，用系统 `DownloadManager`（30 行内） |
| 引入新框架 / 新依赖 | `code.md:17` 明令禁止；现有 Hilt + Media3 + OkHttp 完全够用 |
| 新增底部 Tab | 工具页放进设置页即可，不动导航结构 |
| 多语言 / 字符串资源抽取 | `code.md:13` 不做多语言 |
| 深色模式适配 | `code.md:19` 强制不受系统深色影响，项目全局锁浅色 |
| `parses` / `rules` / `ads` 全量配置解析 | 涉及 `ResultParser` + `Entities.Config` + Room 迁移 `MIGRATION_7_8`，`docs/plan.md:340` 本就规划 P3 之后再做；首版用内置正则足够 |
| 让 HTML 网页订阅源也具备嗅探 | 需改 `HtmlWebController` 的 WebViewClient，可能影响现有 30+ `fongmiBridge` action 的稳定性；P4 单独评估 |
| 照搬 UZN 暗色紫 UI / MPV 播放器 | 视觉与播放器内核均不匹配 MewBox |
| 任何绕过付费 / DRM / 加密流的能力 | 仅做「浏览器本已加载的公开媒体地址」的采集与展示 |

---

## 11. 需要老大拍板的 4 个决策点

| # | 决策 | 选项 | 小弟建议 |
| :-- | :--- | :--- | :--- |
| D1 | **范围** | ① 只做形态 A（UZN 工具页）｜② A + B（顺带还 P3 债）｜③ 只做形态 B（无头，改播放链路） | **② A + B**。内核共用，B 只多约 100 行 + 改 3 行 `DetailViewModel`，性价比最高 |
| D2 | **入口位置** | ① 设置页新增一行｜② 首页顶部工具图标｜③ 独立底部 Tab | **① 设置页**。改动最小，符合克制原则；若老大天天用，再升到 ② |
| D3 | **下载要不要做** | ① 砍掉（只复制 / 外部打开）｜② 只做 MP4（`DownloadManager`）｜③ 连 m3u8 合并一起做 | **① 砍掉**。③ 需要引入分片下载 + 合并，是独立项目级工作量，与嗅探本身无关 |
| D4 | **配置规则时机** | ① 首版只内置正则｜② 同步解析 `rules/ads/parses` 并做 Room 迁移 | **① 首版内置正则**。等真遇到需要自定义脚本的站点再加，避免一上来就动数据库 |

> 老大回一句「按建议来」或直接指定选项，小弟就按 P0 → P1 开工（先出可单测内核，不碰现有业务逻辑）。

---

## 12. 附录

### A. 参考文件索引

| 用途 | 绝对路径 |
| :--- | :--- |
| UZN 功能梳理（本文档依据） | `C:\Users\admin\WorkBuddy\2026-11-10-14-55-49\UZN_网页视频嗅探功能梳理.md` |
| UZN 反混淆探针（可直接移植） | `C:\Users\admin\WorkBuddy\2026-11-10-14-55-49\uzn_probe\sniffer_deob.js` |
| UZN 字符串表 | `C:\Users\admin\WorkBuddy\2026-11-10-14-55-49\uzn_probe\strings_valid.txt` |
| 8 类请求本地测试页 | `C:\Users\admin\WorkBuddy\2026-11-10-14-55-49\uzn_probe\webroot\index.html` |
| UZN 结果抽屉实测截图 | `...\uzn_probe\screen_probe_results.png` |
| 主蓝本 AVBox 嗅探解析器 | `D:\project\AVBox\app\src\main\java\com\github\avbox\osc\player\PlayUrlResolver.kt` |
| AVBox 媒体正则 / 规则 / 广告 | `D:\project\AVBox\app\src\main\java\com\github\avbox\osc\util\{DefaultConfig,VideoParseRuler,AdBlocker}.kt` |
| 上游 MewPlayer 嗅探 WebView | `D:\project\MewPlayer\app\src\main\java\com\fongmi\android\tv\ui\custom\CustomWebView.java` |
| MewPlayer 正则 / 规则 | `D:\project\MewPlayer\app\src\main\java\com\fongmi\android\tv\utils\{Sniffer,WebSniffHeaders}.java`、`api\config\RuleConfig.java` |
| MewBox 架构规范 | `D:\project\MewBox\.agents\rules\code.md` |
| MewBox 原开发计划（含 P3 待办） | `D:\project\MewBox\docs\plan.md` |

### B. 关键构建命令

```bash
cd /d/project/MewBox
./gradlew testDebugUnitTest        # P0 单测门禁
./gradlew assembleDebug            # 真机联调包
./gradlew assembleRelease          # R8 混淆验证（必做，见 R1）
```

### C. 术语表

| 术语 | 含义 |
| :--- | :--- |
| kind | JS 探针上报的原始事件类型（10 种） |
| source | MewBox 归一化后的采集通道标签（6 种），UI 显示用 |
| generation | 每轮加载的轮次号，用于丢弃上一页的迟到事件 |
| 形态 A / B | 独立工具页 / 播放链路无头自动嗅探 |
| 双通道 | 原生 `shouldInterceptRequest` + JS 探针，缺一不可 |
| `parse == 1` | TVBox 系源协议中「返回的是网页地址、需嗅探真实流」的标记 |

---

**文档结束 ｜ 未修改 `D:\project\MewBox` 任何文件**
