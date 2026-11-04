package com.mewbox.tv.player

import androidx.media3.common.C

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
    val language: String? = null,
    val details: String? = null,
) {
    val isText: Boolean get() = type == C.TRACK_TYPE_TEXT
    val isAudio: Boolean get() = type == C.TRACK_TYPE_AUDIO
    val isVideo: Boolean get() = type == C.TRACK_TYPE_VIDEO
}

