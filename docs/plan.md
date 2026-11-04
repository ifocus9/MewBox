# T4 源与可扩展插件引擎 Android 播放器开发计划文档

> 版本：v0.1（基于 FongMi/TV 架构精简重构 + 可插拔多引擎扩展设计）  
> 目标平台：Android (Mobile)  
> 文档性质：开发计划 / 架构设计 / 里程碑与验收基线  
> 架构基准：全面参考 `FongMi/TV` 核心链路（`VodConfig` + `SpiderRouter` + `SiteRepository` + `Bean` + `Players`）

---

## 1. 项目概述

开发一款基于现代 Android 技术栈（Kotlin、Jetpack Compose、Media3、Hilt、Coroutines/Flow、Room、Kotlinx Serialization）的播放器 App。支持加载 TVBox 标准聚合订阅配置（`config.json`），首版通过 T4 HTTP API（`type == 4`）获取分类、详情、搜索和播放直链，并使用 Media3 ExoPlayer 完成播放。

在架构设计上，1:1 借鉴 `FongMi/TV` 的业务分层与领域模型，采用 **“Spider 统一契约 + Loader 插件化路由”** 设计：首版聚焦轻量级 T4 远程源，同时在底层预留标准扩展槽位，方便后续无缝接入 **Node.js（内置本地微服务或直接执行 JS 爬虫）**、QuickJS 等本地脚本引擎。

### 1.1 核心目标

1. **订阅与站点管理**：支持导入 TVBox 聚合 `config.json` 订阅地址或 JSON 文本，全量入库并与本地用户偏好（启用、搜索、换源开关）自动合并。
2. **可插拔引擎架构**：业务层面向抽象 `Spider` 接口编程，首版实现 `T4SpiderLoader`（`type == 4`），后续可通过 Hilt 多绑定零侵入扩展 `NodeJsSpiderLoader`。
3. **纯片单源与播放源联动**：兼容 `豆瓣[荐]`（`t4_douban`）等纯推荐源与主力播放源的权限隔离，点击无播放地址的推荐片单时自动触发跨源快速搜索（`quickSearch`）并播放。
4. **全链路浏览与并发搜索**：支持分类筛选、分页加载及多源 `channelFlow` 并发流式搜索。
5. **同源切线路与跨源换源**：详情页支持同源零开销切换线路（`Flag`），以及跨源按片名搜索匹配同集数无缝续播。
6. **Media3 增强播放与历史**：支持 HLS / MP4 直链播放、动态注入 Headers（Referer/UA）、明文 HTTP 与忽略自签名 SSL 证书；按影片维度聚合保存观看历史，支持毫秒级断点续播。

---

## 2. 技术选型

| 层级       | FongMi 原版选型                  | 本项目现代化选型                           | 说明                                                                |
| :--------- | :------------------------------- | :----------------------------------------- | :------------------------------------------------------------------ |
| 语言       | Java                             | **Kotlin**                                 | 全面使用协程、扩展函数、密封类与空安全                              |
| UI 框架    | XML + ViewBinding                | **Jetpack Compose + Material 3**           | 声明式 UI，契合播放器状态与多源流式搜索结果的高频更新               |
| 架构模式   | MVVM + EventBus + 单例           | **MVI / MVVM + Hilt + StateFlow**          | 去除 EventBus，统一使用响应式数据流收敛播放与换源状态               |
| 引擎路由   | Loader 单例硬编码分发            | **Spider 接口 + Hilt `@IntoSet` 多绑定**   | 解耦具体协议，支持未来平滑扩展 Node.js / QuickJS 模块               |
| 播放器内核 | Media3 + IJK + MPV               | **Media3 ExoPlayer + `datasource-okhttp`** | 官方维护，复用全局兼容 SSL/Host 替换的 `OkHttpClient`               |
| 网络请求   | OkHttp 直接封装                  | **Retrofit (`@Url`) + OkHttp**             | 支持动态完整 URL、局域网 `127.0.0.1` 重写拦截器与 `&extend=` 注入   |
| JSON 解析  | Gson + 自定义 TypeAdapter        | **Kotlinx Serialization (`JsonElement`)**  | 利用树形 API 编写高容错解析器，防御野生源各种奇葩脏数据             |
| 本地数据库 | Room (`Config`/`Site`/`History`) | **Room + DataStore**                       | Room 存订阅配置、站点状态与播放历史；DataStore 存全局偏好与 IP 映射 |
| 图片加载   | Glide                            | **Coil Compose**                           | 复用自定义 OkHttp 解决封面图防盗链（Referer）与 HTTP 明文加载       |
| 日志与测试 | Logger + JUnit                   | **Timber + JUnit + MockWebServer**         | 聚焦 `ResultParser` 脏数据容错单元测试与网络层测试                  |

---

## 3. 架构设计（FongMi 风格 + 插件化扩展）

### 3.1 模块与包结构设计

首版采用单模块分包结构以保证极速编译（包边界严格隔离，未来需要引入 `libnode.so` 时可直接将 `engine` 拆分为独立 Gradle Module）：

```text
app/src/main/java/com/mewbox/tv
├── catvod                        // 【抽象契约层】未来可独立为 :core-catvod 模块
│   ├── Spider.kt                 // 所有爬虫/源引擎的统一挂起函数接口
│   ├── SpiderLoader.kt           // 引擎加载器接口 (support / getSpider / clear)
│   ├── SpiderRouter.kt           // 注入 Set<SpiderLoader>，按 Site 属性动态路由分发
│   └── LocalRuntimeService.kt    // 本地守护进程生命周期接口（为内置 Node.js 9988 服务预留）
├── engine                        // 【具体引擎实现层】
│   ├── t4                        // 首版实现：处理 type == 4 的远程 HTTP T4 源
│   │   ├── T4Spider.kt           // 将 Spider 五大方法映射为 T4 HTTP Query 请求
│   │   └── T4SpiderLoader.kt     // 缓存并管理 T4Spider 实例
│   └── nodejs                    // 未来扩展预留：处理内置 Node.js 微服务或 type == 3 JS 脚本
│       └── (NodeJsSpiderLoader.kt)
├── api
│   ├── config
│   │   └── VodConfig.kt          // 点播配置总控：拉取 config.json、合并本地站点状态、管理默认首页源
│   ├── net
│   │   ├── T4Service.kt          // Retrofit 动态 @Url 请求接口
│   │   ├── HostInterceptor.kt    // 将 127.0.0.1:9988 动态重写为局域网 IP 或 10.0.2.2
│   │   └── SSLCompat.kt          // 忽略自签名/过期 HTTPS 证书校验
│   └── parser
│       └── ResultParser.kt       // 基于 JsonElement 的高容错解析器（处理 Result/Vod/Flag/$$$ 切割）
├── bean                          // 【领域与持久化模型】1:1 对齐 FongMi 核心实体
│   ├── Config.kt                 // Room Entity：订阅源地址记录
│   ├── Site.kt                   // Room Entity：站点配置（含 searchable, quickSearch, changeable）
│   ├── Class.kt                  // 分类模型 (type_id, type_name)
│   ├── Vod.kt                    // 影片详情与摘要模型
│   ├── Flag.kt                   // 播放线路模型 (vod_play_from)
│   ├── Episode.kt                // 单集模型 (vod_play_url)
│   ├── Result.kt                 // 引擎统一返回包装类 (classes, list, url, header, parse, jx)
│   └── History.kt                // Room Entity：按影片聚合的观看历史
├── db
│   ├── AppDatabase.kt            // Room Database
│   └── dao                       // ConfigDao, SiteDao, HistoryDao
├── domain
│   └── SiteRepository.kt         // 核心业务调度层（原 SiteViewModel）：只调 SpiderRouter，与底层协议解耦
├── player
│   ├── Players.kt                // Media3 ExoPlayer 生命周期封装、状态流与进度分发
│   ├── ExoUtil.kt                // 构建 MediaItem、动态注入 Headers、推断流媒体 MIME 类型
│   └── PlayUrlResolver.kt        // 播放地址解析器接口（首版直链，预留本地代理流与 WebView 嗅探扩展）
├── ui
│   ├── home                      // 首页（分类 Tab + 视频网格 + 快捷切源弹窗）
│   ├── search                    // 搜索页（channelFlow 多源并发流式展示）
│   ├── detail                    // 播放详情一体页（播放器 + 简介 + 线路栏 + 选集网格 + 跨源换源面板）
│   ├── history                   // 历史记录页
│   ├── setting                   // 设置页（订阅导入、站点开关、局域网 IP 映射设置）
│   └── components                // 通用 Compose 组件（视频卡片、加载/错误/空状态）
└── di                            // Hilt 依赖注入模块 (NetworkModule, DatabaseModule, EngineModule, PlayerModule)
```

### 3.2 插件化引擎扩展机制（面向 Node.js 演进）

```text
UI Layer (Home / Search / Detail ViewModels)
       │  (StateFlow / Flow)
       ▼
SiteRepository (统一业务调度层，与具体协议 100% 解耦)
       │
       ▼
SpiderRouter (通过 Hilt @IntoSet 动态路由匹配站点对应的引擎)
       ├──► T4SpiderLoader     ──► T4Spider (首版实现：处理 type==4 HTTP 远程接口)
       ├──► NodeJsSpiderLoader ──► NodeJsSpider (未来扩展：内置 Node.js 运行时/本地微服务)
       └──► QuickJsLoader      ──► QuickJsSpider (未来扩展：处理轻量级 type==3 JS 脚本)
```

1. **统一 `Spider` 契约**：所有引擎统一实现 `init`、`homeContent`、`categoryContent`、`detailContent`、`searchContent`、`playerContent` 六个 `suspend` 方法，统一返回 `Result` 模型。
2. **全量入库、按需激活**：`VodConfig` 解析 `config.json` 时，将所有 `sites`（含 `type == 3/4` 及携带 `ext`/`jar` 的站点）完整存入 Room 数据库；UI 层与搜索层通过 `spiderRouter.isSupported(site)` 动态过滤当前已加载引擎所支持的站点。
3. **Node.js 两种演进路径兼容**：
   - **路径 A（Node.js 作为本地 9988 端口 T4 网关）**：实现 `LocalRuntimeService`，在 App 启动时通过 `libnode.so` 在后台拉起本地 `127.0.0.1:9988` 服务，站点请求继续走 `T4Spider`，业务代码零改动。
   - **路径 B（Node.js 直接加载单站点 `.js` 脚本）**：新增 `NodeJsSpiderLoader` 注册到 Hilt `@IntoSet` 中，接管 `type == 3` 的 JS 站点，返回 JSON 交给同一个 `ResultParser` 解析。

### 3.3 状态管理与横竖屏原则

- 每个页面一个 `ViewModel`，暴露 `StateFlow<UiState>`。
- 播放器状态独立为 `PlaybackUiState`：`Idle`、`Loading`、`Playing`、`Buffering`、`Error`、`SwitchingSource`。
- Activity 配置 `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"`，由 Compose 播放页自行接管全屏横竖屏切换与系统栏隐藏，避免 Activity 重建导致播放中断。

---

## 4. 功能需求

### 4.1 首页 (`HomeScreen`)

| 功能                 | 说明                                                                           | 优先级 |
| :------------------- | :----------------------------------------------------------------------------- | :----- |
| 分类与推荐展示       | 调用当前默认源 `homeContent()` 获取分类 Tab（`classes`）与推荐列表             | P0     |
| 分类分页列表         | 按分类 `typeId` 与页码 `pg` 调用 `categoryContent()` 分页加载                  | P0     |
| 下拉刷新/加载更多    | 基础列表分页交互                                                               | P0     |
| 封面、标题、备注     | 展示 `vodPic`、`vodName`、`vodRemarks`                                         | P0     |
| 纯片单源自动换源跳转 | 点击影片进入详情页；若当前源无播放列表（如 `t4_douban`），自动触发跨源搜索播放 | P0     |
| 顶部快捷切换首页源   | 点击顶部源名称弹窗切换其他已启用且支持的站点                                   | P0     |

### 4.2 搜索 (`SearchScreen`)

| 功能             | 说明                                                                                      | 优先级 |
| :--------------- | :---------------------------------------------------------------------------------------- | :----- |
| 多源并发流式搜索 | 并发调用所有 `enabled && searchable` 站点的 `searchContent()`，搜到一个源立即上屏一批结果 | P0     |
| 搜索结果卡片     | 展示影片封面、名称、备注及**所属站点名称角标**                                            | P0     |
| 并发限流控制     | 使用 `Semaphore(6~8)`（可配置）限制并发，单源 8 秒超时独立兜底，首批结果目标 ≤ 3 秒       | P0     |
| 空结果与搜索历史 | 无结果状态提示；本地保存最近搜索关键词                                                    | P1     |

### 4.3 历史记录 (`HistoryScreen`)

| 功能              | 说明                                                                    | 优先级 |
| :---------------- | :---------------------------------------------------------------------- | :----- |
| 按影片聚合列表    | 同一站点同一部剧只保留最新一条记录，按观看时间倒序排列                  | P0     |
| 断点续播          | 点击直接恢复上次观看的站点、线路（`Flag`）、剧集（`Episode`）与毫秒进度 | P0     |
| 删除单条/一键清空 | 基础历史记录管理                                                        | P1     |

### 4.4 播放详情一体页 (`DetailScreen`)

| 功能                    | 说明                                                                                  | 优先级 |
| :---------------------- | :------------------------------------------------------------------------------------ | :----- |
| 影片详情展示            | 展示名称、封面、年代、地区、演员、导演、简介                                          | P0     |
| 同源切线路（切 `Flag`） | 展示 `playFlags` 横向标签栏，点击直接切换剧集列表并保持当前集数续播                   | P0     |
| 剧集网格选集            | 展示当前线路下的 `Episode` 列表，高亮当前播放集数，支持正序/倒序排列                  | P0     |
| Media3 播放器区域       | 支持播放/暂停、进度条拖动、缓冲提示、倍速播放、全屏横竖屏切换                         | P0     |
| 跨源换源（切 `Site`）   | 点击“换源”并发搜索所有 `quickSearch && changeable` 站点，点击候选源自动对齐同集数续播 | P0     |
| 异常与嗅探拦截提示      | 明确区分网络超时、直链 403、以及 `parse == 1`（需网页嗅探暂不支持）                   | P0     |

### 4.5 设置页 (`SettingScreen`)

| 功能             | 说明                                                                                   | 优先级 |
| :--------------- | :------------------------------------------------------------------------------------- | :----- |
| 订阅配置导入     | 输入 `config.json` URL 或粘贴 JSON 文本，批量解析 `sites` 入库并与本地状态合并         | P0     |
| 站点管理列表     | 展示站点列表，支持独立开关“启用”、“参与搜索 (`searchable`)”、“参与换源 (`changeable`)” | P0     |
| 默认首页源设置   | 选择任意站点作为 App 启动默认首页源（如 `豆瓣[荐]`）                                   | P0     |
| 本地 IP 映射设置 | **开发联调必备**：将配置中的 `127.0.0.1` 动态映射为模拟器 `10.0.2.2` 或指定局域网 IP   | P0     |
| 手动添加单源     | 支持手动输入单个 T4 站点的名称、`api` 地址与 `ext` 参数                                | P1     |

---

## 5. 核心数据模型设计 (`bean` 包)

### 5.1 爬虫统一契约 (`Spider.kt`)

```kotlin
interface Spider {
    suspend fun init(ext: String = "") {}
    suspend fun homeContent(filter: Boolean = true): Result
    suspend fun homeVideoContent(): Result = Result()
    suspend fun categoryContent(tid: String, pg: String, filter: Boolean = true, extend: Map<String, String> = emptyMap()): Result
    suspend fun detailContent(ids: List<String>): Result
    suspend fun searchContent(keyword: String, quick: Boolean = false, pg: String = "1"): Result
    suspend fun playerContent(flag: String, id: String, vipFlags: List<String> = emptyList()): Result
    fun destroy() {}
}
```

### 5.2 站点配置实体 (`Site.kt`，对齐 `config.json`)

```kotlin
@Entity(tableName = "site")
data class Site(
    @PrimaryKey val key: String,        // 唯一标识，如 "t4_douban"
    val name: String,                   // 显示名称，如 "豆瓣[荐]"
    val type: Int,                      // 站点类型：4=T4远程源, 3=CSP/JS/Node脚本源
    val api: String,                    // 接口地址，如 "http://127.0.0.1:9988/t4/api/douban"
    val ext: String = "",               // 扩展参数（T4 请求时自动附带 &extend=）
    val jar: String = "",               // 预留自定义包字段
    // 以下状态支持用户在设置页修改，重新拉取订阅时保留用户本地设置
    val searchable: Boolean = true,     // 对应 searchable == 1（是否参与全局搜索）
    val quickSearch: Boolean = true,    // 对应 quickSearch == 1（是否支持快速搜索）
    val filterable: Boolean = true,     // 对应 filterable == 1（是否支持分类筛选）
    val indexs: Boolean = false,        // 对应 indexs == 1（首页聚合索引，与换源无关）
    val changeable: Boolean = true,     // 是否参与换源（配置缺省时默认 true，由用户在设置页控制）
    val hide: Boolean = false,          // 对应 hide == 1（如 push_agent，不在站点列表展示）
    val enabled: Boolean = true,        // 用户是否启用该站点
    val isHome: Boolean = false,        // 是否为当前默认首页源
    val priority: Int = 0               // 排序权重
)
```

### 5.3 统一响应与影片剧集模型 (`Result.kt` / `Vod.kt` / `Flag.kt` / `Episode.kt`)

```kotlin
data class Result(
    val classes: List<Class> = emptyList(),       // 对应 "class" 分类列表
    val list: List<Vod> = emptyList(),            // 对应 "list" 影片列表
    val url: String = "",                         // 播放接口返回的直链地址
    val header: Map<String, String> = emptyMap(), // 播放接口返回的自定义请求头
    val parse: Int = 0,                           // 0=直链，1=需网页嗅探
    val jx: Int = 0,
    val flag: String = ""
)

data class Class(
    val typeId: String,
    val typeName: String,
    val typeFlag: String = ""
)

data class Vod(
    val vodId: String = "",
    val vodName: String = "",
    val vodPic: String = "",
    val vodRemarks: String = "",
    val vodYear: String = "",
    val vodArea: String = "",
    val vodActor: String = "",
    val vodDirector: String = "",
    val vodContent: String = "",
    val vodPlayFrom: String = "",           // 原始线路字符串，以 $$$ 分隔
    val vodPlayUrl: String = "",            // 原始剧集字符串，以 $$$、#、$ 分隔
    val siteKey: String = "",               // 所属站点 Key
    val siteName: String = "",              // 所属站点名称
    val playFlags: List<Flag> = emptyList() // 解析后直接供 UI 渲染的线路与剧集结构
)

data class Flag(
    val flag: String,                       // 线路名称，如 "量子线路"
    val episodes: List<Episode> = emptyList()
)

data class Episode(
    val name: String,                       // 剧集名称，如 "第01集"
    val url: String,                        // 传给 playerContent 的播放 ID
    val index: Int                          // 剧集索引（用于切线路/跨源换源时对齐集数）
)
```

### 5.4 播放历史实体 (`History.kt`)

```kotlin
@Entity(tableName = "history")
data class History(
    @PrimaryKey val key: String,        // 主键格式："{siteKey}_{vodId}"，保证一部剧仅一条记录
    val siteKey: String,
    val siteName: String,
    val vodId: String,
    val vodName: String,
    val vodPic: String,
    val vodFlag: String,                // 上次观看的线路名
    val episodeName: String,            // 上次观看的集数名
    val episodeUrl: String,             // 上次观看的播放 ID
    val episodeIndex: Int,              // 上次观看的集数序号
    val position: Long = 0L,            // 播放进度（毫秒）
    val duration: Long = 0L,            // 视频总时长（毫秒）
    val updatedAt: Long = System.currentTimeMillis()
)
```

---

## 6. T4 引擎适配与容错解析规范

### 6.1 `T4Spider` 请求参数映射

所有请求通过 `T4Service`（使用 Retrofit `@Url`）发出。若 `site.ext.isNotEmpty()`，自动追加 Query 参数 `"extend" to site.ext`：

| `Spider` 标准方法               | 映射的 T4 HTTP Query 参数               | 说明                                                 |
| :------------------------------ | :-------------------------------------- | :--------------------------------------------------- |
| `homeContent(filter)`           | 无参请求或 `ac=home`（M0 实测）         | 获取分类 `class`、`filters` 与可选的推荐 `list`      |
| `categoryContent(tid, pg, ...)` | `ac=list&t={tid}&pg={pg}`               | 获取指定分类的分页列表（兼容 `ac=videolist`）        |
| `detailContent(ids)`            | `ac=detail&ids={ids.joinToString(",")}` | 获取详情并触发 `vod_play_from` / `vod_play_url` 拆解 |
| `searchContent(keyword, quick)` | `wd={keyword}&quick={1或0}`             | 关键词搜索                                           |
| `playerContent(flag, id)`       | `play={id}&flag={flag}`                 | 获取播放直链 `url`、请求头 `header` 及 `parse` 标记  |

### 6.2 `ResultParser` 核心容错规则

1. **两级剧集分割算法**：
   - 先用 `$$$` 将 `vod_play_from` 与 `vod_play_url` 拆分为对应的线路数组。
   - 每个线路内部用 `#` 拆分单集，再用 `$`（`limit = 2`）拆分为 `集数名$播放ID`（若无 `$`，集数名默认为 `"正片"`）。
2. **脏数据类型防御**：
   - 全程使用 `kotlinx.serialization.json.JsonElement` 树形解析，严禁强类型 DTO 直接反序列化整个响应。
   - `vod_id`、`type_id` 兼容 `Int` 与 `String`；`list`、`class` 兼容空字符串 `""`、`false` 或 `null`；`header` 兼容 JSON 对象与嵌套 JSON 字符串。
3. **本地回环与网络兼容**：
   - `AndroidManifest.xml` 开启 `android:usesCleartextTraffic="true"`。
   - `HostInterceptor` 自动读取 DataStore 中的目标 IP，将 API 请求以及 **ExoPlayer 播放地址**（源会返回 `http://127.0.0.1:9988/m3u8-proxy?...` 形式的本地代理直链）中的 `127.0.0.1` / `localhost` 动态替换为局域网服务端 IP（或模拟器 `10.0.2.2`）。
   - 真机开发联调另有最简方案：`adb reverse tcp:9988 tcp:9988`，此时无需任何 IP 映射。
   - `SSLCompat`（忽略证书校验）仅作用于“源 API 请求专用”的 `OkHttpClient`，不得全局生效，播放器与图片加载使用独立 Client。

### 6.3 `config.json` 顶层字段处理范围

首版 `VodConfig` **只读取 `sites`**，其余顶层字段一律忽略（解析时使用 `JsonElement` 或 `ignoreUnknownKeys = true`，不得因多余字段报错）。各字段用途与后续阶段如下：

| 字段          | 用途                                                                                      | 首版 | 对应阶段 |
| :------------ | :---------------------------------------------------------------------------------------- | :--- | :------- |
| `sites`       | 站点列表（`type=4` 为 T4 远程源）                                                         | 处理 | M1       |
| `spider`      | JAR 爬虫地址（示例中为空）                                                                | 忽略 | 不计划   |
| `parses`      | 解析接口列表。播放返回 `parse=1` / `jx=1` 时，将网页地址拼到解析接口后换取真实直链        | 忽略 | P3       |
| `flags`       | 需走解析的 VIP 线路名单（如 `qq`、`iqiyi`），对应 `playerContent` 的 `vipFlags`           | 忽略 | P3       |
| `rules`       | 播放处理规则：`regex` 过滤 m3u8 广告片段，`script` 嗅探时自动点击，`proxy` 指定域名走代理 | 忽略 | P3       |
| `ads`         | 广告域名黑名单，仅 WebView 嗅探时拦截                                                     | 忽略 | P3       |
| `doh`         | DNS over HTTPS 服务器列表，用于绕过 DNS 污染                                              | 忽略 | P1       |
| `ijk`         | IJK 播放器解码参数（本项目使用 Media3，无需）                                             | 忽略 | 不计划   |
| `lives`       | 直播源、EPG、台标                                                                         | 忽略 | 另立项   |
| `wallpaper`   | 壁纸接口（示例中指向 `127.0.0.1:9978`，与 T4 的 `9988` 不是同一服务）                     | 忽略 | 不计划   |
| `sites_count` | 非标准字段，不可作为校验依据                                                              | 忽略 | 不计划   |

> 约定：被忽略的字段不入库、不报错；若后续实现 `parse == 1` 嗅探，再扩展 `Config` 实体保存 `parses` / `flags` / `rules` / `ads`。

---

## 7. 换源与切线路策略

### 7.1 同源切线路（切 `Flag`）

- 详情接口已返回所有线路 `List<Flag>`。
- 用户点击新线路 Tab 时，直接切换本地剧集列表；若当前正在播放，自动选取新线路下相同 `episodeIndex` 的剧集并携带当前 `position` 续播。

### 7.2 跨源换源（切 `Site`）与纯推荐源自动兜底

1. **触发时机**：
   - **自动触发**：从 `豆瓣[荐]` 等纯推荐源进入详情页，发现 `vod_id` 以 `msearch:` 开头（M0 实测，此类条目 `ac=detail` 返回空列表）或 `playFlags.isEmpty()` 时自动触发，且前者应跳过 `detailContent` 直接搜索。
   - **手动触发**：用户在详情页点击“换源”按钮时触发。
2. **执行链路**：
   - 筛选所有 `enabled && changeable && (quickSearch || searchable)` 且受 `SpiderRouter` 支持的站点（示例配置中所有站点 `quickSearch` 均为 0，实际靠 `searchable` 兜底）。
   - 并发调用 `searchContent(keyword = currentVodName, quick = true)`。
   - 按**片名匹配规则**过滤候选 `Vod`（附带所属 `Site` 信息）。
   - 用户点击某个候选源（或自动兜底选中第一个返回结果的源）后，调用该源的 `detailContent(newVodId)` 获取新 `playFlags`，自动对齐当前 `episodeIndex` 与 `position` 请求 `playerContent` 完成无缝续播。
3. **片名匹配规则**（首版）：
   - 归一化：去除空格、标点、`[]()【】` 内的备注，统一繁简与大小写。
   - 完全相等优先；其次“包含”关系；若原片名带“第N季”，候选必须包含相同季号。
   - 若豆瓣源提供年份，年份不一致的候选降权，但不直接丢弃。
4. **历史合并**：换源成功后沿用原历史记录的 `vodName`，更新 `siteKey`/`vodId`，避免同一部剧在不同站点产生多条历史。

---

## 8. 开发阶段与里程碑（3 周 MVP 排期）

| 里程碑                         | 时间                          | 核心交付物                                                                                                                                         |
| :----------------------------- | :---------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------- |
| **M0：协议摸底与解析器验证**   | 第 1 周 (Day 1–2)             | 跑通本地 `9988` 端口服务；抓取 `t4_douban` 与主力源真实 JSON 样本（以实测确认 `homeContent` 参数）；完成 `ResultParser` 与 `$$$` 拆解的 JUnit 测试 |
| **M1：可插拔骨架与配置总控**   | 第 1 周 (Day 3–7)             | 搭建 Compose+Hilt+Room+Retrofit 骨架；实现 `Spider`/`SpiderRouter`/`T4Spider`；完成 `VodConfig` 订阅导入、状态合并、设置页与 IP 映射开关           |
| **M2：首页、流式搜索与详情页** | 第 2 周 (Day 8–12)            | 实现 `SiteRepository`；完成首页分类分页加载、快捷切首页源、多源并发流式搜索页、详情页静态线路与选集 UI                                             |
| **M3：Media3 播放与跨源换源**  | 第 2 周末–第 3 周 (Day 13–17) | 封装 `Players` 与 `ExoUtil`（动态 Headers 注入、全屏横竖屏）；打通豆瓣源自动跨源搜片播放、同源切线路、手动换源链路                                 |
| **M4：历史续播与打包发布**     | 第 3 周 (Day 18–21)           | 实现 `HistoryDao` 按影片聚合保存与断点续播；完善非直链嗅探拦截提示（`parse == 1`）；配置 R8 混淆规则并输出签名 Release APK                         |

> 工期偏紧时的可砍项：手动添加单站点、倍速播放、顶部快速切首页源、倒序选集。

---

## 9. 任务拆解 WBS

### 9.1 阶段一：M0 技术验证与解析器先行

- [x] 验证 `http://127.0.0.1:9988` 服务连通性，保存 `t4_douban` 及 2 个主力播放源的分类、详情、搜索、播放响应 JSON 为本地测试资源。
- [x] 编写 `ResultParser`：实现 `config.json`、`Result`、`Vod`、`$$$` 线路剧集拆解的容错解析。
- [x] 编写 JUnit 单元测试验证所有样本 JSON 解析通过。

### 9.2 阶段二：项目骨架与插件化引擎底座

- [x] 初始化 Android 工程，配置 Kotlin、Compose、Hilt、Room、DataStore、Retrofit、Media3、Serialization；确定 `minSdk` 与 `abiFilters`（仅 `arm64-v8a`）。
- [x] 配置 `AndroidManifest.xml`（开启 `usesCleartextTraffic`，设置 Activity `configChanges`）。
- [x] 定义 `Spider`、`SpiderLoader`、`LocalRuntimeService` 接口及 `SpiderRouter`（Hilt `@IntoSet` 注入）。
- [x] 实现 `HostInterceptor`（支持 `127.0.0.1` 动态替换）、`SSLCompat` 与 `T4Spider` / `T4SpiderLoader`。

### 9.3 阶段三：`VodConfig` 与设置页源管理

- [x] 实现 Room `AppDatabase`（`ConfigDao`、`SiteDao`、`HistoryDao`）。
- [x] 实现 `VodConfig`：拉取订阅 JSON、全量保存 `Site`（`hide:1` 的站点默认不在列表展示）并与本地已存开关状态合并、维护默认首页源。
- [x] 实现 `SettingScreen`：支持导入订阅链接/JSON、配置局域网 IP 映射、管理站点开关与默认首页源。

### 9.4 阶段四：首页与多源流式搜索

- [x] 实现 `SiteRepository`：封装五大标准请求与 `channelFlow` 并发搜索（含并发限流）。
- [x] 实现 `HomeScreen`：展示默认首页源的分类 Tab、视频分页网格、顶部快速切源弹窗。
- [x] 实现 `SearchScreen`：流式展示多源搜索结果，卡片标注站点名称角标。

### 9.5 阶段五：播放详情页、Media3 与换源闭环

- [x] 实现 `Players` 与 `ExoUtil`：封装 `OkHttpDataSource.Factory`，支持每次播放动态设置 `MediaItem` 专属 Headers，优化大体积 MKV 抽取器配置。
- [x] 实现 `DetailScreen`：集成播放器视图、全屏切换、影片简介、`Flag` 线路切换栏、清晰度选择、`Episode` 选集网格。
- [x] 实现换源逻辑：支持 `playFlags` 为空时（如豆瓣源）自动触发跨源搜索播放，以及手动点击“换源”弹窗切换站点并对齐集数续播。
- [x] 实现 `History` 进度定时保存与进入详情页自动定位线路、集数并 `seekTo`。

### 9.6 阶段六：异常处理、混淆与构建交付

- [x] 拦截 `parse == 1` 的非直链响应，UI 提示“该线路需网页嗅探，请切换线路或换源”。
- [x] 完善弱网超时、直链 403、空数据的 UI 引导与重试按钮。
- [x] 配置 R8 / ProGuard 规则（Retrofit、Room、Serialization、Hilt、Media3），配置签名并构建 Release APK。
- [ ] 真机上手实测验证（当前阶段）。

---

## 10. 测试计划与关键验收标准

### 10.1 测试重点

| 测试维度 | 测试内容                                                                              | 方式             |
| :------- | :------------------------------------------------------------------------------------ | :--------------- |
| 单元测试 | `config.json` 解析、`$$$` 与 `#`/`$` 剧集分割、异常脏数据 JSON 容错、片名匹配         | JUnit + 本地样本 |
| 网络测试 | 动态 `@Url` 请求、`ext` 参数传递、`127.0.0.1` 局域网 IP 替换拦截器                    | MockWebServer    |
| 播放测试 | HLS (m3u8)、MP4、自定义 Headers (Referer/UA)、明文 HTTP 流、横竖屏切换                | 真机联调         |
| 核心回归 | 订阅导入 → 豆瓣首页浏览 → 点击自动跨源匹配播放 → 同源切线路 → 手动跨源换源 → 历史续播 | 真机手动清单     |

### 10.2 MVP 验收标准

1. **订阅导入**：在设置页输入 `config.json` 地址或文本并配置好局域网 IP 后，能成功解析入库 30 个站点。
2. **首页浏览**：首页默认加载 `豆瓣[荐]`（或其他指定源）的分类与海报列表，支持分页加载与顶部快速切换首页源。
3. **无感跨源起播**：在首页点击任意豆瓣推荐影片，进入详情页后能自动搜出可用播放源并自动播放第 1 集。
4. **切线路与换源**：播放页支持点击不同 `Flag` 切换同源线路；支持点击“换源”切换其他站点并自动对齐当前集数与进度续播。
5. **多源流式搜索**：搜索页输入关键词后，各启用站点的结果能流式动态刷出，点击任意结果可正常进入播放。
6. **聚合历史续播**：观看同一部剧的多集后，历史页仅保留该剧最新一条记录，点击可精准恢复到上次观看的集数与时间点。
7. **扩展性验证**：`SiteRepository` 与所有 ViewModel 中无任何直接耦合 `T4Service` 的代码，全部通过 `SpiderRouter` 调度。

---

## 11. 风险与应对

| 风险                            | 影响                 | 应对策略                                                                                                |
| :------------------------------ | :------------------- | :------------------------------------------------------------------------------------------------------ |
| 配置含 `127.0.0.1` 真机无法访问 | 所有接口请求拒绝连接 | 内置 `HostInterceptor`，设置页可随时配置映射的目标局域网 IP                                             |
| 豆瓣等推荐源无播放地址          | 点击影片无法播放     | 详情页检测 `msearch:` 前缀或 `playFlags.isEmpty()` 时自动触发 `quickSearch` 跨源匹配播放                |
| 多源并发搜索打挂本地 9988 服务  | 服务端卡死或大量超时 | 使用 `Semaphore(6~8)` 限制并发度（瓶颈在本地 Node 服务，需实测调优），单站点设置 8 秒独立超时与异常隔离 |
| 部分源返回 `parse: 1` 网页地址  | ExoPlayer 报格式错误 | 解析层与 `PlayUrlResolver` 提前拦截，友好提示用户切换线路或换源                                         |
| 横竖屏切换导致 Activity 重建    | 播放黑屏、进度丢失   | Manifest 配置 `configChanges`，由 Compose 状态控制全屏布局切换                                          |
| 后续接入 Node.js 引发架构返工   | 改动范围过大         | 首版严格执行 `Spider` + `SpiderLoader` 接口隔离与 `Site` 全量入库策略                                   |

---

## 12. 后续迭代演进路线

- **P1（体验打磨）**：源连通性批量测速、搜索历史、播放器手势控制（亮度/音量/快进倍速）、片头片尾跳过。
- **P2（Node.js 引擎扩展）**：实现 `LocalRuntimeService` 与 `:engine-nodejs` 模块，内置 `libnode.so` 在 App 本地启动 9988 端口服务或直接运行 `type == 3` 的 JS 爬虫脚本。
- **P3（高级播放特性）**：实现 `SnifferPlayUrlResolver`（内置轻量级 WebView 拦截嗅探 `parse == 1` 的真实媒体流）、本地代理流支持、弹幕与外挂字幕、画中画（PiP）与后台播放。
