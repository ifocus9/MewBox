package com.mewbox.tv.bean

import androidx.room.Entity

/**
 * 视频轨道用户偏好（字幕、音轨、视轨选择记录）。
 * 主键为 (key, type)，确保同一部剧每类轨道仅一条偏好。
 */
@Entity(
    tableName = "track_preference",
    primaryKeys = ["key", "type"]
)
data class TrackPreference(
    val key: String,
    /** C.TRACK_TYPE_TEXT, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO */
    val type: Int,
    val name: String = "",
    val format: String = "",
    val language: String? = null,
    val label: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val isDisabled: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DISABLED_FORMAT = "__disabled__"
    }
}

