package com.mewbox.tv.ui.detail

/**
 * 片头/片尾跳过限制与校验规则（对齐 FongMi Constant.getOpEdLimit 规范）
 */
object DetailOpEdPolicy {

    /**
     * 片头/片尾可设置的最大时长限制（完全参考 FongMi Constant.getOpEdLimit）
     * - 15分钟以下短视频：最多3分钟
     * - 30分钟以下中视频：最多6分钟
     * - 30分钟以上长视频：最多10分钟
     */
    fun getOpEdLimit(duration: Long): Long {
        if (duration < 15 * 60 * 1000L) return 3 * 60 * 1000L
        if (duration < 30 * 60 * 1000L) return 6 * 60 * 1000L
        return 10 * 60 * 1000L
    }
}

