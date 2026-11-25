# MewBox 开发规范与架构准则

## 一、项目定位与决策原则

1. **项目定位**：个人自用的 Android 影视播放器，核心定位为**纯正 Node.js 源播放器**（配套源仓：`D:\project\CatPawOpen\nodejs`）。
   - 排查源解析、站点协议或运行时问题时，优先对照配套 Node.js 源仓定位与适配。
   - 从外部项目移植功能时，必须结合本仓 Node.js 源架构落地，避免照搬异构实现。
2. **参考项目**：新增功能或排查源解析、Node.js/T4 协议、播放器嗅探、视频格式、动效交互等问题时，优先参考 `D:\project\webhtv` 与 `D:\project\AVBox` 对应模块的成熟实现。
3. **技术栈与权威标准**：基于 Android 现代技术栈（Kotlin、Jetpack Compose、Material 3、Media3 ExoPlayer、Hilt、Coroutines/Flow、Room、DataStore、OkHttp、Kotlinx Serialization、Coil 3）。
   - 技术依据优先级：`官方文档 > 官方最佳实践 > 稳定版本文档`。
   - 优先复用现有技术栈，无充分理由严禁引入新框架；新增代码统一采用现代写法，禁止新旧风格混用。
4. **架构演进原则**：
   - 除非明确要求，不随意推翻现有架构；但发现设计缺陷时优先根治或局部重构，不在缺陷设计上持续打补丁。
   - 保持高内聚、低耦合与单一职责，避免 God Class、超长函数与超长 Composable。

---

## 二、硬性红线（不可违反）

- **仅支持 64 位 (`arm64-v8a`)**：严禁引入 `armeabi-v7a`、`x86` 等 32 位依赖或 Native 库；构建配置始终维持 `ndk { abiFilters += "arm64-v8a" }`。
- **仅维护中文**：界面文案直接使用标准中文，不做多语言抽象与国际化抽取；构建侧保持中文资源过滤（`localeFilters`）。
- **依赖与配置集中管理**：严禁硬编码环境地址、端口与版本号；第三方库及 Gradle 插件版本统一在 `gradle/libs.versions.toml` 维护。
- **禁用 EventBus 与全局可变单槽**：严禁引入 EventBus，禁止新增全局静态可变状态（如 `App.currentVod`）；全局事件与状态统一由 Hilt 作用域、ViewModel 状态树或响应式 Flow 承载。

---

## 三、产品与 UI 设计规范

- **克制设计**：面向个人自用，按最小需求实现，不过度抽象与堆砌功能，同时保证交互流畅与视觉精致。
- **复用全局设计体系**：新页面与模块优先使用 `ui/components/` 封装的公共组件与 `ui/theme/` 全局配色体系，保持视觉一致。
- **主题与深色模式统一受控**：明暗外观与配色统一通过 `MewBoxTheme`（`MaterialTheme.colorScheme`、`MewBoxTheme.colors`、`DynamicTheme`）与 `ThemeMode`（自动 / 浅色 / 深色）驱动；禁止在组件内硬编码固定色值，或绕过 `ThemeMode` 自行判断系统深色状态。
- **图标规范**：优先使用组件库内置图标（`material-icons-extended`，如 `Icons.Rounded.*`），不单独手写自定义 Vector XML。
- **精简界面文案**：避免不必要的次级标签与解释性副文案，依靠清晰的信息层级与布局本身传达含义。

---

## 四、架构分层与依赖边界（不可协商）

仓库采用分包模块化架构（预留平滑拆分为独立 Gradle Module 的能力），严格遵循单向依赖流：

```text
ui ──▶ domain / ViewModel ──▶ catvod / api / db ──▶ bean
                                 ▲
                    engine (+ com.fongmi.nodejs)  （仅经 di/@IntoSet 绑定，不反向渗透）
```

| 层级 / 包 | 核心职责 | 严禁依赖 |
| :--- | :--- | :--- |
| `bean/` | 领域实体、配置模型与纯数据结构（稳定类型集中配置于 `compose_compiler_config.conf`） | 任何上层业务包 |
| `db/` | Room 数据库、DAO 与 Migration 迁移逻辑 | `ui`、`domain`、`player`、`engine` |
| `catvod/` | 纯引擎抽象契约（`Spider`、`SpiderLoader`）与路由器（`SpiderRouter`） | 具体引擎实现（`engine/*`）、`ui`、`db`、`player`、`domain` |
| `engine/` & `com.fongmi.nodejs` | 具体源引擎实现：主引擎 `engine/nodejs`（对接 `:nodejs` 独立进程运行时）与 `engine/t4`；通过 `di/EngineModule`（`@Binds @IntoSet`）注册，禁止在 `SpiderRouter` 中硬编码 | `ui`、`domain`、`player` |
| `api/` | 订阅与设置（`config`，含 DataStore）、OkHttp 网络通信与桥接（`net`）、高容错解析（`parser`/`ResultParser`）、元数据（`tmdb`、`douban`） | `ui`、`player` |
| `player/` | Media3 ExoPlayer 封装（`Players`、`ExoBufferBudget`、`HttpEofRecoveryDataSource` 等），通过 `StateFlow<PlayerState>` 广播状态 | `ui` |
| `domain/` | 跨源编排与业务调度（`SiteRepository`、`HotRankingRepository`、`WebHomeInlineStore` 等），面向契约编程 | `ui`（严禁引入 Compose / View 视图元素） |
| `di/` | Hilt 模块集中声明（`NetworkModule`、`DatabaseModule`、`EngineModule`） | `ui` |
| `ui/` | Compose 声明式渲染与交互捕获；按功能拆分为 Screen / ViewModel / Components / Coordinator | Composable 严禁直调 `domain`、`api`、`db`，必须经 ViewModel 中转 |

> **禁止循环依赖**：严禁引入任何反向依赖或包间环形依赖。

---

## 五、单向数据流（UDF）与状态管理

状态仅在 ViewModel 或底层状态机（如 `Players`）中变更，UI 保持纯只读订阅：

- **可变状态私有化**：`MutableStateFlow` / `MutableSharedFlow` 必须为 `private`，对外一律暴露 `StateFlow` / `SharedFlow` 只读投影（`asStateFlow()` / `asSharedFlow()` / `stateIn(...)`）。
- **集中式状态容器**：页面与播放器状态收敛为不可变状态数据类（如 `PlayerState`、`HomeUiState`、`DetailUiState`、`SearchUiState`、`HistoryUiState`、`SettingsUiState` 等）。
- **UI 禁直写业务状态**：`ui/` 层禁止直接修改 ViewModel 或其他对象状态，所有业务变更必须通过 ViewModel 暴露的 Action/Intent 方法触发。
- **局部状态边界**：Composable 内的 `remember` / `rememberSaveable` 状态仅限纯局部 UI 交互（弹窗显隐、临时焦点、手势中间态），涉及业务数据必须上收至 ViewModel。
- **调度集中与整洁语法**：核心业务调度统一由 ViewModel 或控制器发起；不添加 `@Suppress("UNUSED_EXPRESSION")` 等 hack，不用 `override var` 暴露内部可变属性。

---

## 六、代码质量与工程实践

- **代码风格**：命名清晰、短函数、小类、`val` 优先、数据不可变；避免重复代码、过深嵌套与魔法数字/字符串。
- **空安全**：
  - 严禁使用裸 `!!` 断言。
  - 正常可空分支使用早退守卫（`?: return`、`takeIf`、`isNullOrEmpty()`）。
  - 预期必须存在的契约数据使用 `requireNotNull(x) { "上下文说明" }` 或 `checkNotNull`。
  - 严禁用 `?.` 将关键业务异常静默跳过；不随意变更公共契约的可空性。
- **错误处理与日志**：
  - 严禁空 `catch` 块或静默吞错；网络异常与源数据脏数据通过 `ResultParser` 等容错层安全降级。
  - 日志统一使用 `Timber`（`Timber.d` / `w` / `e`），Release 自动关闭调试输出。
- **并发与异步**：
  - 全面使用结构化并发（`viewModelScope` / `lifecycleScope`），严禁阻塞主线程；IO 操作切 `Dispatchers.IO`，密集计算切 `Dispatchers.Default`。
  - 多源并发流式搜索采用 `channelFlow` 流式发射，避免共享可变状态；对共享单线程运行时（如 `SpiderRouter.isRuntimeShared` 标记的 Node.js 站点）做好并发限流。
- **依赖注入与网络隔离**：
  - 组件统一经 Hilt 注入；`NetworkModule` 按用途隔离 `OkHttpClient`（`@Named("api")` / `@Named("player")` 启用 `SSLCompat` 与 `HostInterceptor`，公网标准接口如 `@Named("douban")` 严禁混用宽松 TLS）。
- **注释准则**：
  - 杜绝废话注释（如重复变量名或 getter 含义），提倡代码自解释。
  - 对核心算法、状态机流转、FongMi 逻辑对齐及网络容错 Hack（如 `SSLCompat`、`HostMapping`、`HttpEofRecoveryDataSource`）必须保留或补充高价值注释；严禁误删已有有效注释与协议头。
