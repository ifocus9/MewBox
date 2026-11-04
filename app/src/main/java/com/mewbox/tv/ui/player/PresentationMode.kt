package com.mewbox.tv.ui.player

/**
 * 播放器呈现形态。
 *
 * 拆分为独立维度，避免用单个 Boolean 表达「是否挂载」与「是否全屏」两件事：
 * - [HIDDEN]      未挂载：海报详情页 / 已退出播放
 * - [FULLSCREEN]  全屏沉浸播放（当前唯一挂载形态）
 * - [MINI]        应用内悬浮小窗（预留，待 PlayerView 提升到根 OverlayHost 后启用）
 */
enum class PresentationMode {
    HIDDEN,
    FULLSCREEN,
    MINI,
    ;

    /** 是否处于挂载（可见）状态 */
    val isMounted: Boolean
        get() = this != HIDDEN

    /** 是否全屏沉浸形态 */
    val isFullscreen: Boolean
        get() = this == FULLSCREEN
}
