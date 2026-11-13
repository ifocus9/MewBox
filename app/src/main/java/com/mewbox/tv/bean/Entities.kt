package com.mewbox.tv.bean

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 站点配置，对齐 config.json 的 sites；重新导入订阅时保留用户本地开关。 */
@Entity(tableName = "site")
data class Site(
    @PrimaryKey val key: String,
    val name: String,
    /** 4 = T4 远程源；3 = CSP/JS/Node 脚本源（首版不支持，仅入库） */
    val type: Int,
    val api: String,
    val ext: String = "",
    val jar: String = "",
    /** 0 = 源不支持搜索；1 = 支持且启用；2 = 支持但被手动禁用（对齐 FongMi TV / MewPlayer） */
    val searchable: Int = 1,
    val quickSearch: Boolean = true,
    val filterable: Boolean = true,
    /** 对应 indexs == 1，首页聚合索引，与换源无关 */
    val indexs: Boolean = false,
    /** 是否参与换源（配置缺省时默认 true，由用户控制） */
    val changeable: Boolean = true,
    /** 对应 hide == 1，不在站点列表展示 */
    val hide: Boolean = false,
    val enabled: Boolean = true,
    val isHome: Boolean = false,
    val priority: Int = 0,
) {
    /** 源本身是否支持搜索（源配置未显式声明 searchable = 0） */
    val isSearchSupport: Boolean get() = searchable != 0

    /** 当前是否启用了搜索（值为 1 为启用，2 为手动禁用，0 为源不支持） */
    val isSearchable: Boolean get() = searchable == 1
}

/** 订阅配置记录。name 为空表示用户未命名，展示时直接以订阅链接作为订阅名称。 */
@Entity(tableName = "config")
data class Config(
    @PrimaryKey val url: String,
    val name: String = "",
    val home: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /** 0 = 普通 VOD 订阅源；1 = HTML 网页源 */
    val type: Int = TYPE_VOD,
    /** 订阅唯一标识：编辑订阅更新链接时保持不变，用于关联播放历史与音视频轨偏好。 */
    val id: String = newId(),
) {
    /** 展示名：未设置名称时回退到订阅链接 */
    val desc: String
        get() = name.ifBlank { url }

    /** 是否为 HTML 网页订阅源 */
    val isHtml: Boolean
        get() = type == TYPE_HTML

    companion object {
        const val TYPE_VOD = 0
        const val TYPE_HTML = 1

        fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

        /** 早期版本自动生成的「订阅 N」占位名，一律视为“未设置名称” */
        fun isAutoName(value: String): Boolean {
            val text = value.trim()
            if (!text.startsWith("订阅")) return false
            val index = text.removePrefix("订阅").trim()
            return index.isNotEmpty() && index.all { it.isDigit() }
        }
    }
}

/** 播放历史：同一订阅源同一站点同一部剧仅一条记录；换源后沿用原记录并更新 siteKey/vodId。 */
@Entity(tableName = "history")
data class History(
    @PrimaryKey val key: String,
    val configId: String = "",
    val configUrl: String = "",
    val configName: String = "",
    val siteKey: String,
    val siteName: String,
    val vodId: String,
    val vodName: String,
    val vodPic: String,
    val vodFlag: String,
    val episodeName: String,
    /** 只保存 play_id，直链有时效，续播时需重新请求 play */
    val episodeUrl: String,
    val episodeIndex: Int,
    val position: Long = 0L,
    val duration: Long = 0L,
    val opening: Long = 0L,
    val ending: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun key(siteKey: String, vodId: String, configId: String = ""): String =
            if (configId.isBlank()) "${siteKey}_$vodId" else "${configId}_${siteKey}_$vodId"
    }
}
