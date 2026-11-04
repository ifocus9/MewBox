package com.mewbox.tv.bean

/**
 * TMDB 刮削配置项
 * @property enable 是否启用 TMDB 功能（总开关）
 * @property apiKey TMDB API Key（v3）或 读访问令牌（v4 Bearer Token）
 * @property apiBase TMDB API 代理 Base URL（默认留空使用官方 api.tmdb.org）
 * @property imageBase TMDB 图片 CDN 代理 Base URL（默认留空使用官方 image.tmdb.org/t/p）
 */
data class TmdbConfig(
    val enable: Boolean = false,
    val apiKey: String = "",
    val apiBase: String = "",
    val imageBase: String = "",
)
