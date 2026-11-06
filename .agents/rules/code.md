---
trigger: always_on
---

# MewBox 通用开发规范与架构准则

## 一、通用基本原则

1. **权威与技术标准**：基于 Android 现代技术栈（Kotlin、Jetpack Compose、Media3、Hilt、Coroutines/Flow、Room、Kotlinx Serialization）。优先遵循官方文档 > 官方最佳实践 > 稳定版本文档；与官方指导冲突时以官方为准。
2. **新增功能或者遇到功能bug优先参考 MewPlayer**：增加当前项目没有的功能时，或者遇到源解析、T4/Node.js 接口协议、播放器嗅探与视频格式解析等问题或 Bug 时，**优先参考 [MewPlayer](D:\project\MewPlayer) 对应模块的成熟实现方式**。
3. **64 位架构硬约束**：**所有功能只考虑 64 位（`arm64-v8a`），不考虑 32 位**；严禁引入 32 位（`armeabi-v7a`、`x86`）相关的包或 Native 库，NDK 过滤始终维持 `ndk { abiFilters += "arm64-v8a" }`。
4. **高内聚、低耦合**：代码编写必须严格遵循单一职责原则，**严禁将所有功能堆砌在单个文件中**（坚决避免 God Class 与超长 Composable）；合理拆分领域逻辑、数据流、UI 组件与 ViewModel。
5. **不做多语言**：项目专注于中文交互，**界面文本直接使用标准中文维护**，不做多语言资源抽取或国际化抽象。
6. **架构与技术决策**：
   - 保持架构模块化、单一职责、低耦合高内聚、避免循环依赖；除非明确要求，否则不随意改变现有架构。
   - 不要在已知缺陷的架构设计上持续打补丁，优先给出根治方案，必要时进行局部重构。
   - 优先使用项目已有技术（如 Hilt、Compose、Media3、Flow 等），没有充分理由严禁擅自引入新框架。
   - 新增代码必须与现有项目风格一致，统一为现代化新写法，禁止新旧风格混用。
7. **强制软件不受系统深色模式影响**
8. **图标优先使用组件库中的，不单独写xml**
9. **本地启动了雷神模拟器，每次修改完后，把最新测试包安装到模拟器里**
10. **APP仅为我个人使用，功能设计保持克制，优先按最小需求实现，不过度设计，考虑交互和视觉**

---

## 二、通用代码规范

- **代码风格**：
  - 清晰命名、小函数/小类、不可变数据（`val` 优先）、单一职责。
  - 避免超长方法、超长文件、重复代码、过深嵌套、魔法数字与字符串、不必要的全局状态。
- **分层职责**：
  - **UI 层 (`ui/`)**：负责声明式渲染与交互事件捕获，仅消费 ViewModel 暴露的 StateFlow；严禁直接访问数据源、网络接口或数据库。
  - **业务调度层 (`domain/`)**：负责业务逻辑编排、多源聚合与换源调度（如 `SiteRepository`、`HotRankingRepository`），面向接口和契约编程。
  - **契约与引擎层 (`catvod/`, `engine/`)**：`catvod` 定义统一抽象契约（`Spider` / `SpiderLoader` / `SpiderRouter`），具体引擎（如 `engine/t4`、未来扩展的 `engine/nodejs`）实现具体源协议，通过 Hilt 多绑定隔离。
  - **数据与网络层 (`db/`, `api/`, `bean/`)**：`db` 负责 Room 本地持久化，`api` 负责配置管理、OkHttp/Retrofit 网络请求与高容错 JSON 解析（`ResultParser`），`bean` 维护领域实体模型。
- **注释与文档**：
  - 杜绝无意义的废话注释（如 `// 获取名称`、`val name: String`），提倡代码自解释。
  - 对核心业务算法、多状态机流转、FongMi 逻辑对齐、网络层容错 Hack（如 `SSLCompat`、`HostMapping` 局域网重写、`HttpEofRecoveryDataSource` 网盘分片恢复）必须保留或编写高价值注释。
  - 严禁随意删除已有的有效注释与许可证/协议头声明。
- **错误处理与日志**：
  - 严禁静默忽略错误或使用空 `catch` 块；网络请求与源数据脏数据必须通过高容错解析器进行安全降级。
  - 日志统一使用 `Timber`（`Timber.d(...)` / `Timber.w(...)` / `Timber.e(...)`），Release 模式下自动关闭调试输出。
- **空安全规范**：
  - 严禁使用裸 `!!` 断言。
  - 正常业务可空场景：使用早退守卫（`?: return`、`takeIf`、`isNullOrEmpty()`）。
  - 预期必须存在的数据断言：使用 `requireNotNull(x) { "错误信息" }` 或 `checkNotNull`，并提供明确的上下文说明。
  - 严禁用 `?.` 把业务关键异常改成静默跳过；避免无故修改公共契约的可空性标注。
- **并发与异步**：
  - 全面使用 Kotlin 协程与 Flow，严格遵循结构化并发原则（`viewModelScope` / `lifecycleScope`）。
  - 严禁阻塞 Android 主线程，耗时 IO 操作切到 `Dispatchers.IO`，密集计算切到 `Dispatchers.Default`。
  - 多源并发流式搜索统一采用 `channelFlow` 流式发射结果，避免共享可变状态，确保线程安全。
- **依赖注入 (Hilt)**：
  - 统一通过 Hilt 进行依赖注入，各模块组件（OkHttpClient、Retrofit、Room DAO、Repository 等）由 `@Module` 集中声明。
  - 插件加载器统一通过 `@Binds @IntoSet` 注册到 `SpiderRouter`，确保扩展无侵入性。
- **配置与依赖集中管理**：
  - 严禁硬编码环境地址、端口、版本号；第三方库与 Gradle 插件版本一律集中在 `gradle/libs.versions.toml` 维护。

---

## 三、架构不变量（不可协商）

本仓库遵循分包模块化架构（预留平滑拆分为独立 Gradle Module 的能力），包与层级边界必须严格遵守以下禁止清单：

1. **`catvod` 属于纯契约与抽象路由器层**：
   - 仅定义抽象接口（`Spider`、`SpiderLoader`）与路由器（`SpiderRouter`）。
   - 严禁依赖任何具体引擎实现（`engine/*`），严禁依赖 `ui`、`db`、`player`、`domain`。
2. **`engine` 属于具体源引擎实现层**：
   - 实现 `catvod.SpiderLoader` 与 `catvod.Spider` 契约（当前为 `engine.t4`，未来扩展 `engine.nodejs`）。
   - 仅依赖 `catvod` 契约、`bean`、`api.net` 或解析器；严禁反向依赖 `ui`、`domain`、`player`。
   - 引擎注册统一通过 Hilt 的 `EngineModule`（`@Binds @IntoSet`）接入，严禁在 `SpiderRouter` 中硬编码具体引擎类。
3. **`bean` 与 `db` 位于依赖图底层**：
   - `bean` 存放领域实体与数据模型，不依赖任何上层业务包。
   - `db` 仅负责 Room 数据库、DAO 声明与数据库迁移逻辑，不得依赖 `ui`、`domain`、`player`、`engine`。
4. **`api` 负责配置、网络通信与容错解析**：
   - 包含 `config`（订阅与设置）、`net`（OkHttp/Retrofit/拦截器）、`parser`（高容错 `ResultParser`）、`tmdb`。
   - 严格禁止依赖 `ui` 与 `player`。
5. **`domain` 负责跨源编排与业务逻辑调度**：
   - 如 `SiteRepository` 调度 `SpiderRouter`、`VodConfig`、`SiteDao`，为 UI 提供纯业务数据接口。
   - 不得依赖 `ui`，严禁引入任何 Android View 或 Composable 视图元素。
6. **`player` 封装底层音视频播放能力**：
   - 封装 Media3 ExoPlayer（`Players`、`ExoBufferBudget` 等），只负责流媒体解析、缓冲策略与播放控制。
   - 对外通过 `StateFlow<PlayerState>` 广播播放进度与状态；严禁直接依赖 `ui`。
7. **`ui` (Composable) 纯渲染与交互驱动**：
   - Composable 组件只负责声明式 UI 渲染，通过 ViewModel 暴露的 StateFlow 消费状态。
   - Composable 函数严禁直接调用 `domain` 仓库、`api` 网络服务或 `db` DAO；所有业务数据必须通过 ViewModel 中转。
   - 页面必须按功能模块拆分（Screen、ViewModel、Components），严禁将视图与逻辑混在单个巨型文件中。
8. **严禁引入包级循环依赖**：
   - 严格遵循 `ui -> domain/viewModel -> catvod/api/db -> bean` 的依赖单向流向，严禁新增任何反向依赖或包间环形依赖。

---

## 四、单向数据流（UDF）与状态管理

状态只在 ViewModel / 状态机（如 `Players`）中产生与变更，UI 保持纯只读订阅：

- **可变状态私有化**：
  - `MutableStateFlow` / `MutableSharedFlow` 必须修饰为 `private`。
  - 对外一律暴露 `StateFlow` / `SharedFlow` 只读投影（通过 `asStateFlow()`、`asSharedFlow()` 或 `stateIn(...)`）。
  - `PlayerState`、`DetailUiState`、`HomeUiState`、`SearchUiState`、`SettingsUiState`、`HistoryUiState` 为标准认可的集中式状态容器。
- **UI 禁直写跨对象状态**：
  - `ui/` 层严禁直写其他对象或 ViewModel 的状态（如禁止 `vm.someState.value = ...`）；状态修改必须通过 ViewModel 暴露的 Action/Intent 方法进行。
  - Composable 内通过 `remember` / `rememberSaveable` 创建的 `mutableStateOf` 仅限用于纯局部 UI 交互状态（如弹窗显隐、输入框临时焦点、手势拖拽中间态），一旦涉及业务数据必须上收至 ViewModel。
- **调度集中管理**：
  - 核心业务调度一律经由 ViewModel 或对应的控制器发起，UI 组件不得越级操作底层调度器。
- **禁用 EventBus**：
  - 严禁引入 EventBus（`EventBus.getDefault().post` / `@Subscribe`），全局事件与通知统一采用响应式 Flow。
- **禁用全局单槽**：
  - 严禁新增任何全局静态可变单槽（如全局 `App.currentVod` 等静态可变量），状态流转必须由 Hilt 注入作用域或 ViewModel 状态树承载。
- **整洁语法**：
  - 不加 `@Suppress("UNUSED_EXPRESSION")` 这类 revision hack；不用 `override var` 暴露内部可变接口属性。
