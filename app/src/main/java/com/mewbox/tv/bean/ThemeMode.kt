package com.mewbox.tv.bean

/** 应用主题模式偏好 */
enum class ThemeMode(
    val storageValue: String,
    val label: String,
) {
    Auto("auto", "自动"),
    Dark("dark", "深色"),
    Light("light", "浅色"),
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        Auto -> systemDark
        Dark -> true
        Light -> false
    }

    companion object {
        fun fromStorage(raw: String?): ThemeMode {
            val normalized = raw?.trim()?.lowercase()
            return entries.firstOrNull { it.storageValue == normalized } ?: Auto
        }
    }
}
