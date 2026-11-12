package com.mewbox.tv.player

import androidx.media3.common.C
import com.mewbox.tv.bean.TrackPreference

/**
 * 播放器轨道信息实体（字幕、音轨、视轨）
 */
data class PlayerTrack(
    val id: String,
    val name: String,
    val type: Int, // C.TRACK_TYPE_TEXT, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO
    val isSelected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSupported: Boolean = true,
    val language: String? = null,
    val label: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val details: String? = null,
    val formatDescription: String = "",
) {
    val isText: Boolean get() = type == C.TRACK_TYPE_TEXT
    val isAudio: Boolean get() = type == C.TRACK_TYPE_AUDIO
    val isVideo: Boolean get() = type == C.TRACK_TYPE_VIDEO

    /**
     * 将当前选中的轨道转化为持久化偏好模型
     */
    fun toPreference(historyKey: String): TrackPreference = TrackPreference(
        key = historyKey,
        type = type,
        name = name,
        format = formatDescription,
        language = language,
        label = label,
        width = width,
        height = height,
        isDisabled = false,
        updatedAt = System.currentTimeMillis(),
    )
}
