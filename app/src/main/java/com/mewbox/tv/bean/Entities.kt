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
    val searchable: Boolean = true,
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
)

/** 订阅配置记录。 */
@Entity(tableName = "config")
data class Config(
    @PrimaryKey val url: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 播放历史：同一站点同一部剧仅一条记录；换源后沿用原记录并更新 siteKey/vodId。 */
@Entity(tableName = "history")
data class History(
    @PrimaryKey val key: String,
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
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun key(siteKey: String, vodId: String) = "${siteKey}_$vodId"
    }
}
