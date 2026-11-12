package com.mewbox.tv.api.douban

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 想看分段的 UI 状态机（含首屏、翻页、同步、无 uid、空表与错误态）。 */
enum class WishStatus { Idle, Loading, LoadingMore, Refreshing, Empty, Error, NoUid }

data class WishUiState(
    val items: List<WishItem> = emptyList(),
    val total: Int = 0,
    val status: WishStatus = WishStatus.Idle,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val lastSyncAt: Long = 0L,
)

/**
 * 单条想看条目（UI 领域模型，与 Frodo 响应结构解耦）。
 * 注意：interestId 来自 interest.id（Long，稳定），不要用 subject.id（类型不稳定）。
 */
data class WishItem(
    val interestId: Long,
    val title: String,
    val year: String,
    val poster: String,
    /** 社区评分原始值（0~10），供 UI 绘制五星；无评分时为 null。 */
    val ratingValue: Double? = null,
    val markedAt: String,
    val isTv: Boolean,
    val subtitle: String,
    val tags: List<String> = emptyList(),
    val comment: String = "",
)

// ———— Frodo 响应 DTO（响应含大量未声明字段，解析方必须 ignoreUnknownKeys + isLenient） ——

@Serializable
data class DoubanInterestsResponse(
    val start: Int = 0,
    val count: Int = 0,
    val total: Int = 0,
    val interests: List<DoubanInterest> = emptyList(),
)

@Serializable
data class DoubanInterest(
    val id: Long = 0L,
    val status: String = "",
    @SerialName("create_time") val createTime: String = "",
    val comment: String = "",
    val tags: List<String> = emptyList(),
    val subject: DoubanSubject = DoubanSubject(),
)

@Serializable
data class DoubanSubject(
    val title: String = "",
    val year: String = "",
    val pic: DoubanPic? = null,
    val rating: DoubanRating? = null,
    @SerialName("card_subtitle") val cardSubtitle: String = "",
    val type: String = "",
    val subtype: String = "",
    @SerialName("is_released") val isReleased: Boolean? = null,
)

@Serializable
data class DoubanPic(val large: String = "", val normal: String = "")

/**
 * subject.rating 为社区评分（满分 10），与 interest.rating（个人评分，满分 5）是两套量纲，不要混用。
 */
@Serializable
data class DoubanRating(val value: Double? = null, val count: Int = 0)

/** DTO → UI 模型：片名为空的脏数据直接丢弃（返回 null 由调用方过滤）。 */
internal fun DoubanInterest.toWishItem(): WishItem? {
    val title = subject.title.trim()
    if (title.isEmpty()) return null
    val poster = subject.pic?.large?.takeIf { it.isNotBlank() }
        ?: subject.pic?.normal.orEmpty()
    val rating = subject.rating?.value?.takeIf { it > 0.0 }
    return WishItem(
        interestId = id,
        title = title,
        year = subject.year.orEmpty(),
        poster = poster,
        ratingValue = rating,
        markedAt = createTime.take(10),
        isTv = subject.type == "tv",
        subtitle = subject.cardSubtitle,
        tags = tags,
        comment = comment,
    )
}
