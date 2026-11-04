# 历史记录直达播放：防闪烁 + 起播耗时埋点 计划

> 状态：待实施（2026-11-02 讨论定稿）
> 范围：仅解决「历史记录点击播放时海报页闪烁」与「起播耗时不可观测」两个问题；**不做快速起播**。

---

## 一、背景与问题

### 1.1 现象

- 点击历史记录播放：先出现海报详情页（虚化海报 + Loading），再进入全屏播放。
- 退出播放：先滑出露出海报页、转回竖屏，再返回历史记录页，中间闪一下海报页。

### 1.2 根因

历史起播复用了详情路由 `detail/{siteKey}?...&autoPlay=true`，本质仍是海报页 + 参数切换全屏：

| 阶段 | 根因                                                                                                | 代码位置                                        |
| ---- | --------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 进入 | `state.isLoading` 期间渲染 `PosterBackdrop` + `LoadingState`，播放器要等 `state.vod != null` 才挂载 | `DetailScreen.kt` 「统一界面容器」`when` 分支   |
| 进入 | `NavHost` 未配置转场，默认约 700ms 淡入淡出，历史页与海报叠加                                       | `MainActivity.kt` `NavHost`                     |
| 进入 | 视频宽高就绪后才旋转横屏，产生二次跳变                                                              | `DetailScreen.kt` 智能屏幕旋转 `LaunchedEffect` |
| 退出 | `exitPlayer()` 先播放 300ms 滑出动画，露出下层 `DetailContent`                                      | `DetailScreen.kt` 全屏滑动转场 `LaunchedEffect` |
| 退出 | 动画结束 `presentation = HIDDEN` → 转竖屏 → 才 `onBack()`，叠加默认淡出                             | 同上                                            |

### 1.3 决策记录

- **采用「独立播放路由 + 复用 `DetailViewModel`」**：海报层在播放页中根本不存在，从结构上消除闪烁。
- **不新建 `PlayerViewModel` / `PlaybackSession` / `ResumePlayUseCase`**：当前只有一个播放入口逻辑，拆分属于过度设计，且需重构约 900 行状态逻辑，风险大于收益。
- **不做快速起播（跳过详情并发解析）**：
  - 主要使用网盘源，`playerContent` 普遍依赖 `detailContent` 建立的分享 token / 会话 / 文件列表缓存，跳过详情容易失败回退，反而更慢；
  - 网盘直链有时效，直链缓存不可行；
  - 瓶颈尚未量化，先埋点再决策（见第四节）。
- **路由参数保持现状**（siteKey / vodId / vodName / vodPic），暂不改为 `historyKey`。

---

## 二、目标效果

```mermaid
flowchart LR
    H["历史记录页"] -->|"点击，150ms 淡入"| P["黑底全屏播放器（内置 Loading）"]
    P -->|"返回，无动画"| H
```

- 进入：点击后立即为黑底全屏 + 播放器 Loading，全程不出现海报。
- 退出：直接回到历史记录页，不出现海报、不出现滑出动画。
- 普通详情页（海报 → 点击播放 → 滑入全屏）行为**保持不变**。

---

## 三、实施步骤

### 步骤 1：抽取播放窗口副作用 `PlayerWindowEffects`

**新增** `ui/player/PlayerWindowEffects.kt`，从 `DetailScreen.kt` 中原样迁出以下 Effect（仅依赖 `Activity` 与 `PlayerState`，不依赖 `ui.detail`）：

| 迁出内容     | 说明                                                                      |
| ------------ | ------------------------------------------------------------------------- |
| 智能屏幕旋转 | 全屏时按视频宽高切换 `USER_PORTRAIT` / `SENSOR_LANDSCAPE`，非全屏恢复竖屏 |
| 沉浸式系统栏 | 全屏隐藏系统栏 + 刘海适配；非全屏恢复透明状态栏与浅色图标                 |
| 屏幕常亮     | 全屏且播放 / 缓冲 / 解析中保持 `FLAG_KEEP_SCREEN_ON`                      |
| 页面销毁恢复 | `onDispose` 恢复竖屏、系统栏、清除常亮                                    |

建议签名：

```kotlin
@Composable
fun PlayerWindowEffects(
    isFullscreen: Boolean,
    playerState: PlayerState,
    keepScreenOnExtra: Boolean, // isResolvingPlay || isAutoSearching
)
```

- `DetailScreen` 改为调用 `PlayerWindowEffects(presentation.isFullscreen, ...)`，**行为零变化**。
- 详情页专属的「透明状态栏 + 浅色图标」初始化仍保留在 `DetailScreen`。

> 依赖方向：`ui.detail → ui.player`（已存在），`ui.player` 不得反向依赖 `ui.detail`。

### 步骤 2：新增直达播放页 `DirectPlayScreen`

**新增** `ui/detail/DirectPlayScreen.kt`（放在 `ui.detail` 包内，因为它消费 `DetailViewModel`，避免 `ui.player → ui.detail` 反向依赖）。

结构：

```
DirectPlayScreen
├─ 黑色背景 Box（fillMaxSize）
├─ PlayerWindowEffects(isFullscreen = true, ...)
├─ VideoPlayerViewport（立即挂载，不等 vod 详情）
│   └─ Loading 由 isResolvingPlay / isAutoSearching / 播放器缓冲态承担
├─ FullScreenEpisodeDrawer（AnimatedVisibility 侧滑）
└─ SnackbarHost（错误提示）
```

要点：

1. `viewModel: DetailViewModel = hiltViewModel()`，复用全部播放、选集、进度保存逻辑。
2. `vod` 为空时使用 ViewModel 已有的占位 `Vod(vodId, vodName, vodPic)`（`DetailUiState` 初始值已构造），保证播放器标题可用；如 `VideoPlayerViewport` 要求非空 `vod`，以 `requireNotNull` 前先用占位兜底。
3. 从 `DetailScreen` **迁入**以下仅属于历史起播的逻辑：
   - `state.closeScreen` → 立即 `onBack()`（动作类条目）；
   - `state.error != null` → Snackbar 提示，延迟 1500ms 后 `onBack()`。
4. 退出逻辑（返回键与播放器返回按钮共用）：
   ```
   exit():
     if (drawer 打开) 关闭抽屉; return
     exiting = true            // 渲染一层纯黑遮罩，避免旋转过程露出任何内容
     viewModel.onExitFullscreen()  // 已起播则暂停并保存进度；未起播则取消解析
     viewModel.players.stop()
     onBack()
   ```
   竖屏恢复由 `PlayerWindowEffects` 的 `onDispose` 完成。

### 步骤 3：注册 `play` 路由与转场

修改 `MainActivity.kt`：

1. 新增路由：
   ```
   play/{siteKey}?vodId={vodId}&vodName={vodName}&vodPic={vodPic}
   ```

   - 参数定义同 `detail` 路由；
   - 额外声明 `navArgument("autoPlay") { type = NavType.BoolType; defaultValue = true }`，使 `DetailViewModel` 的 `savedStateHandle["autoPlay"]` 读到 `true`，**ViewModel 无需改动**。
2. `navigateToPlayFromHistory` 改为 `nav.navigate("play/...")`。
3. 转场（仅作用于 `play` 路由及其与 `main` 之间）：

   | 位置                                             | 设置                                               |
   | ------------------------------------------------ | -------------------------------------------------- |
   | `play.enterTransition`                           | `fadeIn(tween(150))`（从黑色淡入）                 |
   | `play.exitTransition` / `play.popExitTransition` | `ExitTransition.None`                              |
   | `main.exitTransition`（目标为 `play`）           | `ExitTransition.None` 或保持，避免与播放页叠加淡出 |
   | `main.popEnterTransition`（来源为 `play`）       | `EnterTransition.None`，立即显示历史页             |

   其余路由保持默认转场。

### 步骤 4：清理 `DetailScreen` 中的 `autoPlay` 分支

删除 / 简化：

- `presentation` 初始值的 `if (autoPlay) FULLSCREEN`；
- `slideAnim` 初始值的 `if (autoPlay) 1f`；
- 退出动画结束后的 `if (autoPlay) { stop(); onBack() }`；
- `startInFullscreen` 监听中的 `if (autoPlay) snapTo(1f)` 分支（保留 `enterPlayer()`，豆瓣 `msearch` 跨源起播仍需要）；
- 「历史记录详情失败自动退出」`LaunchedEffect`（已迁至 `DirectPlayScreen`）；
- `DetailScreen` 的 `autoPlay` 参数及 KDoc 中「历史记录点击」描述；`MainActivity` 的 `detail` 路由同步移除 `autoPlay` 传参（`navigateToDetail` 中的 `&autoPlay=false` 可删除，ViewModel 默认值为 `false`）。

> `DetailViewModel.autoPlay` 字段保留，由路由参数驱动。

### 步骤 5：起播耗时埋点

在 `DetailViewModel` 中增加轻量耗时日志（仅 `Timber.d`，Release 自动关闭），使用 `SystemClock.elapsedRealtime()`：

| 节点        | 位置                                                                             | 日志内容                              |
| ----------- | -------------------------------------------------------------------------------- | ------------------------------------- |
| T0 开始     | `loadDetail` 入口                                                                | 记录起点                              |
| T1 详情完成 | `repository.getDetailContent` 返回后                                             | `detail=${T1-T0}ms`                   |
| T2 解析开始 | `playEpisode` 中 `resolvePlay` 调用前                                            | 记录                                  |
| T3 解析完成 | `resolvePlay` 返回后                                                             | `resolve=${T3-T2}ms`                  |
| T4 首次播放 | `observePlayerState` 中首次 `READY && isPlaying`（`!session.hasSucceeded` 分支） | `buffer=${T4-T3}ms, total=${T4-T0}ms` |

- 统一 Tag 前缀：`[起播耗时]`，附带 `site.name` 与 `episode.name`，便于按源对比。
- 仅统计每个会话的首次起播；换集时以 `playEpisode` 为新起点（T2）重新计算 `resolve` 与 `buffer`。
- 计时状态放在 ViewModel 私有字段，不新增全局状态。

---

## 四、后续决策依据（埋点数据收集后）

用常用网盘源各起播 5～10 次，取平均：

| 结论               | 后续动作                                                                                     |
| ------------------ | -------------------------------------------------------------------------------------------- |
| `detail` 占比最大  | 针对该网盘源验证「跳过详情直接解析」是否可行，可行再评估快速起播（需带失败回退到原流程）     |
| `resolve` 占比最大 | 排查对应引擎 / 网盘接口（T4、Node 源实现），参考 FongMi 对应实现                             |
| `buffer` 占比最大  | 调整 `ExoBufferBudget` 起播缓冲阈值、检查 `HttpEofRecoveryDataSource` 与续播 seek 的分片请求 |

---

## 五、改动清单

| 文件                               | 类型 | 内容                                             |
| ---------------------------------- | ---- | ------------------------------------------------ |
| `ui/player/PlayerWindowEffects.kt` | 新增 | 旋转 / 沉浸式 / 常亮 / 销毁恢复                  |
| `ui/detail/DirectPlayScreen.kt`    | 新增 | 历史直达播放页                                   |
| `ui/detail/DetailScreen.kt`        | 修改 | 改用 `PlayerWindowEffects`，移除 `autoPlay` 分支 |
| `ui/detail/DetailViewModel.kt`     | 修改 | 仅新增耗时埋点                                   |
| `MainActivity.kt`                  | 修改 | 新增 `play` 路由与转场，历史点击改走 `play`      |

不涉及：`db` / `bean` / `domain` / `engine` / `player` 包，无数据库迁移，无新依赖。

---

## 六、验证清单

在雷电模拟器安装最新包后逐项验证：

- [ ] 历史记录点击：无海报出现，黑底淡入后直接全屏 Loading → 起播，续播进度正确
- [ ] 横屏视频：起播后自动转横屏；返回后历史页为竖屏，无海报闪现
- [ ] 竖屏短剧：保持竖屏全屏，返回正常
- [ ] 加载中（尚未起播）按返回：解析取消，不写入历史，直接回历史页
- [ ] 选集抽屉打开时按返回：先关闭抽屉，不退出
- [ ] 全屏内切换上下集、选集、自动下一集正常，历史进度正确保存
- [ ] 详情获取失败 / 站点不存在：Snackbar 提示后自动退回历史页
- [ ] 动作类条目（`Vod.isActionId`）与 `msearch` 条目：行为与改动前一致
- [ ] 普通详情页：海报 → 播放滑入全屏 → 返回滑出回海报，行为与改动前一致
- [ ] Logcat 过滤 `[起播耗时]` 可看到 `detail / resolve / buffer / total` 四段耗时
