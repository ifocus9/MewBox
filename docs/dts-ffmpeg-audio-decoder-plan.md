# MewBox × FFmpeg 音频软解（DTS / TrueHD）移植计划

> 文档版本：v1.0 ｜ 编制日期：2026-11-11  
> 目标仓库：`D:\project\MewBox`（`com.mewbox.tv`，minSdk 26 / targetSdk 35，严格维持纯 64 位 `arm64-v8a`）  
> 参考基准：[MewPlayer](file:///D:/project/MewPlayer)（`nextlib-media3ext` + `CompatFfmpegAudioRenderer`）与 [TV](file:///D:/project/TV)（`FfmpegMotionRenderersFactory`）  
> **状态：技术储备规划文档。当前 MewBox 已具备完整的不可播轨道感知、防假死拦截与自愈机制；本计划用于后续有蓝光原盘/单 DTS 音轨播放需求时随时启动接入。**

---

## 0. 一页纸决策摘要

| 维度 | 决策 / 结论 |
| :--- | :--- |
| **功能定位** | 为 Media3 ExoPlayer 注入轻量级 **FFmpeg 音频软解码器**，解决 Android 设备系统硬解缺失导致的 **DTS / DTS-HD / DTS:X / TrueHD (Dolby Atmos) / E-AC-3 JOC** 无声或播放假死问题。 |
| **架构与 64 位硬约束** | **严格维持 `arm64-v8a` 纯 64 位**。对 AAR 进行过滤，剔除所有 32 位（`armeabi-v7a`、`x86`）so 库，坚决不引入任何 32 位依赖。 |
| **包体积影响** | FFmpeg 64 位音频解码相关 so 库（`libavcodec`、`libavutil`、`libswresample`、`libswscale`、`libmedia3ext`）压缩打包后，APK 增量约 **+2.6MB**（从 ~24MB 增至 ~26.6MB），完全在可接受范围内。 |
| **解码策略** | **硬件优先 + 软解保底（Hardware-First Fallback）**：AAC、AC-3、MP3 等日常格式优先使用系统 MediaCodec 硬解，零额外功耗；遇到设备无法硬解的 DTS/TrueHD 时自动无缝降级给 FFmpeg 输出 PCM。 |
| **可复用成熟资产** | ① `D:\project\MewPlayer\third_party\maven` 中现成的 64 位 `nextlib-media3ext` AAR；② MewPlayer 成熟的 [`CompatFfmpegAudioRenderer.java`](file:///D:/project/MewPlayer/app/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/CompatFfmpegAudioRenderer.java)（已解决声卡多声道下混与防爆音问题）。 |
| **预计工时** | **约 15 ~ 25 分钟**即可落地完成编译与验证。 |

---

## 1. 业务痛点与技术现状

### 1.1 痛点场景
* **蓝光原盘 (Blu-ray) 与 BD Remux 片源**：发烧友在网盘中分享的高清原盘视频，为了追求原汁原味的声音动态，经常**只封装了一条主 DTS-HD MA / DTS:X 音轨**，并未压制第二条供网络分发的 AC-3 或 AAC 兼容音轨。
* **硬件专利缺失**：绝大多数 Android 手机厂商（小米、华为、OPPO、vivo、三星、Google Pixel 等）在芯片系统层（MediaCodec）均**未购买 DTS 专利授权**，导致系统层根本不具备 DTS 硬解能力。
* **此前表现**：用户切换至 `VND.DTS`（DTS）音轨后，ExoPlayer 找不到匹配的音频解码器，导致音频输出中断、视频画面在等待时钟时卡顿假死，且由于无异常抛出而没有任何提示。

### 1.2 当前已完成的防假死保护（第一阶段，已合入 main）
在启动完整 FFmpeg 软解移植前，当前代码已经完成了严密的安全兜底与展示修复：
1. **名称标准化**：[`PlayerTrackFormatter`](file:///d:/project/MewBox/app/src/main/java/com/mewbox/tv/player/PlayerTrackFormatter.kt#L288) 规范识别所有 DTS MIME（包括 `audio/vnd.dts`），准确展示为 `DTS` / `DTS-HD` / `DTS:X`，不再出现 `VND.DTS`。
2. **支持度感知**：[`PlayerTrack`](file:///d:/project/MewBox/app/src/main/java/com/mewbox/tv/player/PlayerTrack.kt#L16) 注入 `isSupported` 状态。
3. **弹窗防御**：[`TrackSelectionDialog`](file:///d:/project/MewBox/app/src/main/java/com/mewbox/tv/ui/player/TrackSelectionDialog.kt#L225) 对当前设备不支持的音轨追加 `设备不支持` 红色微胶囊，点击时弹出 Toast 并拦截切换，**维持原音频流（如 AC-3）正常播放**。
4. **历史偏好与故障自愈**：[`Players.kt`](file:///d:/project/MewBox/app/src/main/java/com/mewbox/tv/player/Players.kt#L215) 在换集恢复偏好时跳过不支持轨道，若发生解码器报错自动清除 Override 并恢复默认流。

### 1.3 启动第二阶段（FFmpeg 软解）的触发条件
当日常在网盘中遇到**只有单条 DTS 音轨、没有提供 AC-3 备用音轨**的片源，且产生明确观影诉求时，按照本方案执行无缝升级。

---

## 2. 参考上游与同构项目（MewPlayer / TV）实现剖析

### 2.1 解码链路对比

```
[媒体流 (MKV / MP4 / TS)]
         │
         ▼
[ExoPlayer / Extractor 解封装] ──> 获取 audio/vnd.dts 格式 Format
         │
         ├───> [当前 MewBox 现状]
         │        └─> 查询系统 MediaCodec ──> 无解码器 ──> isTrackSupported = false ──> 弹窗标红拦截，保持 AC-3 播放
         │
         └───> [引入 FFmpeg 扩展后（TV / MewPlayer 方案）]
                  ├─> 1. 查询系统 MediaCodec (硬解优先) ──> 失败/不支持
                  └─> 2. 自动降级至 CompatFfmpegAudioRenderer (扩展软解)
                           │
                           ├─> FFmpeg Native 库调用 libavcodec (dca 解码器)
                           ├─> 声道自适应处理 (5.1/7.1 自动下混为立体声 PCM，避免丢失人声中置)
                           └─> 输出标准 PCM 16-bit 音频数据 ──> Android AudioTrack 播放发声
```

### 2.2 上游核心资产
1. **AAR 资源**：`D:\project\MewPlayer\third_party\maven\io\github\anilbeesetti\nextlib-media3ext\1.10.0-0.12.1-fongmi-softload-av3a-avs3-ffmpeg901-r5\`
   - 包含针对 FongMi / MewPlayer 深度调优的 FFmpeg 动态库与 JNI 桥接层。
2. **源码实现**：[`D:\project\MewPlayer\app\src\main\java\io\github\anilbeesetti\nextlib\media3ext\ffdecoder\CompatFfmpegAudioRenderer.java`](file:///D:/project/MewPlayer/app/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/CompatFfmpegAudioRenderer.java)
   - 继承自 Media3 `DecoderAudioRenderer<FfmpegAudioDecoder>`。
   - 关键能力：
     - 正确响应 `supportsFormatInternal(Format)`，当遇到 `audio/vnd.dts`、`audio/vnd.dts.hd`、`audio/true-hd` 时返回 `C.FORMAT_HANDLED`。
     - 支持浮点输出与 16-bit 整数 PCM 输出。
     - 支持智能立体声下混（Stereo Downmixing），避免手机双扬声器听 5.1/7.1 电影时人声对白太小而环境声过大的问题。

---

## 3. 架构不变量与设计准则

1. **64 位硬约束**：
   - 严格在 `app/build.gradle.kts` 中维持 `ndk { abiFilters += "arm64-v8a" }`。
   - 引入 AAR 时，自动剔除其中附带的 `armeabi-v7a` 文件夹，避免包体积冗余。
2. **分层清晰与单一职责**：
   - 新增的软解渲染器与解码封装类全部收敛在 `com.mewbox.tv.player.exo.decoder` 目录下。
   - `Players.kt` 负责在构建 `ExoPlayer` 时通过 `renderersFactory` 注入，外部 UI（`VideoPlayerViewport`、`TrackSelectionDialog`）与 `ViewModel` **完全不感知底层是硬解还是 FFmpeg 软解**。
3. **响应式状态自动生效**：
   - 当 FFmpeg 注册完成后，ExoPlayer 的 `group.isTrackSupported(trackIndex)` 对 DTS 轨道会自动返回 `true`。
   - `TrackSelectionDialog` 会自动移除 `设备不支持` 标签，用户可直接勾选 DTS 正常出声，整体代码无需在 UI 层写任何死代码。

---

## 4. 详细实施步骤清单

### 步骤 1：处理依赖与 AAR 引入

在 `app/libs` 目录下放置裁剪后的 `nextlib-media3ext-arm64-release.aar`（约 6.8MB，解压包含 5 个 arm64-v8a 动态库与 classes.jar）。

在 [`app/build.gradle.kts`](file:///d:/project/MewBox/app/build.gradle.kts) 中引入本地库：

```kotlin
dependencies {
    // ... 原有依赖 ...
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
```

### 步骤 2：移植 `CompatFfmpegAudioRenderer`

在 `app/src/main/java/com/mewbox/tv/player/exo/decoder/` 下建立 Kotlin 类 `CompatFfmpegAudioRenderer.kt`，移植 MewPlayer 的核心逻辑：

```kotlin
package com.mewbox.tv.player.exo.decoder

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioDecoder
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

/**
 * 兼容型 FFmpeg 音频扩展渲染器
 * 优先支持 DTS、DTS-HD、TrueHD 等系统 MediaCodec 无法硬解的高级音频流
 */
@UnstableApi
class CompatFfmpegAudioRenderer(
    private val context: Context,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<FfmpegAudioDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "CompatFfmpegAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        val mime = format.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mime)) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        // 专门拦截 DTS、DTS-HD、TrueHD 等软解强需求格式
        val isDtsOrHd = mime.contains("dts", ignoreCase = true) ||
                mime.equals(MimeTypes.AUDIO_TRUEHD, ignoreCase = true) ||
                mime.equals(MimeTypes.AUDIO_ALAC, ignoreCase = true)

        if (!FfmpegLibrary.supportsFormat(mime) && !isDtsOrHd) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        return if (format.cryptoType == C.CRYPTO_TYPE_NONE) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_DRM
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FfmpegAudioDecoder {
        val initialInputBufferSize = if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else 5760
        // 双声道手机扬声器/耳机自动下混为立体声输出，避免丢失中置对白
        val outputChannelCount = if (format.channelCount > 2) 2 else format.channelCount
        return FfmpegAudioDecoder(
            format,
            16,
            16,
            initialInputBufferSize,
            false, // 16-bit PCM 输出
            outputChannelCount
        )
    }

    override fun getOutputFormat(decoder: FfmpegAudioDecoder): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(decoder.channelCount)
            .setSampleRate(decoder.sampleRate)
            .setPcmEncoding(decoder.encoding)
            .build()
    }
}
```

### 步骤 3：在 `Players.kt` 中挂载音频渲染器

修改 [`Players.kt`](file:///d:/project/MewBox/app/src/main/java/com/mewbox/tv/player/Players.kt) 的渲染工厂逻辑：

```kotlin
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: androidx.media3.exoplayer.audio.AudioSink,
                eventHandler: Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                // 1. 优先装载系统默认的 MediaCodec 硬解渲染器（保证 AAC/AC-3 等低功耗硬解）
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
                // 2. 挂载 FFmpeg 音频扩展渲染器（保底软解 DTS、TrueHD）
                try {
                    out.add(
                        com.mewbox.tv.player.exo.decoder.CompatFfmpegAudioRenderer(
                            context,
                            eventHandler,
                            eventListener,
                            audioSink
                        )
                    )
                } catch (t: Throwable) {
                    Timber.w(t, "挂载 FFmpeg 音频软解渲染器失败")
                }
            }
        }.apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            experimentalSetEnableMediaCodecVideoRendererPrewarming(true)
        }
```

---

## 5. 验收标准与测试用例

| 编号 | 测试用例 | 预期表现 |
| :--- | :--- | :--- |
| **TC-01** | 播放单 DTS 5.1 音轨的 MKV 蓝光原盘片源 | 视频起播顺畅，声音正常输出，无爆音、无杂音，人声对白清晰（立体声下混正常）。 |
| **TC-02** | 播放同时包含 AC-3 与 DTS 双音轨的片源 | 音轨选择弹窗中两条音轨均显示正常、无“设备不支持”置灰；自由切换音轨，秒切出声且画面不卡顿。 |
| **TC-03** | 纯 64 位安装包体积与 ABI 校验 | 使用 `apkanalyzer` 校验 APK，仅包含 `lib/arm64-v8a`，无任何 32 位 so 库；APK 体积增量在 3MB 以内。 |
| **TC-04** | 长时间播放音画同步检测 | 播放 30 分钟以上 DTS 音轨视频，声画完全同步，无音频漂移或延迟累积。 |
| **TC-05** | 换集与历史记录偏好恢复 | 选中 DTS 音轨后切换到下一集，正确继承 DTS 偏好并无缝起播。 |

