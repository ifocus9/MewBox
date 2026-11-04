package com.mewbox.tv.api.tmdb.bean

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** TMDB search/multi 或 discover 接口返回的分页根对象 */
@Serializable
data class TmdbResult(
    @SerialName("results") val results: List<TmdbMedia> = emptyList(),
    @SerialName("total_results") val totalResults: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
)

/** TMDB search/multi 结果列表中的一条媒体记录（movie 或 tv） */
@Serializable
data class TmdbMedia(
    @SerialName("id") val id: Int = 0,
    @SerialName("media_type") val rawMediaType: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("overview") val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("popularity") val popularity: Double = 0.0,
) {
    val mediaType: String
        get() = rawMediaType ?: if (title != null) "movie" else "tv"

    /** 统一片名（中文优先） */
    val displayTitle: String
        get() = when {
            !title.isNullOrEmpty() -> title
            !name.isNullOrEmpty() -> name
            !originalTitle.isNullOrEmpty() -> originalTitle
            !originalName.isNullOrEmpty() -> originalName
            else -> ""
        }

    val date: String
        get() = releaseDate ?: firstAirDate.orEmpty()

    val year: String
        get() = if (date.length >= 4) date.substring(0, 4) else ""
}

@Serializable
data class TmdbGenre(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
)

@Serializable
data class TmdbImageItem(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

@Serializable
data class TmdbImages(
    @SerialName("backdrops") val backdrops: List<TmdbImageItem> = emptyList(),
    @SerialName("posters") val posters: List<TmdbImageItem> = emptyList(),
    @SerialName("logos") val logos: List<TmdbImageItem> = emptyList(),
)

@Serializable
data class TmdbCastMember(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("character") val character: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("order") val order: Int = 0,
)

@Serializable
data class TmdbCrewMember(
    @SerialName("id") val id: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("job") val job: String = "",
    @SerialName("department") val department: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCredits(
    @SerialName("cast") val cast: List<TmdbCastMember> = emptyList(),
    @SerialName("crew") val crew: List<TmdbCrewMember> = emptyList(),
)

/**
 * TMDB 影片/剧集详情（含 append_to_response=credits,images,recommendations,similar）。
 */
@Serializable
data class TmdbDetail(
    @SerialName("id") val id: Int = 0,
    @SerialName("media_type") val rawMediaType: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("overview") val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("popularity") val popularity: Double = 0.0,
    @SerialName("genres") val genres: List<TmdbGenre> = emptyList(),
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("status") val status: String? = null,
    @SerialName("credits") val credits: TmdbCredits? = null,
    @SerialName("images") val images: TmdbImages? = null,
    @SerialName("recommendations") val recommendations: TmdbResult? = null,
    @SerialName("similar") val similar: TmdbResult? = null,
) {
    val mediaType: String
        get() = rawMediaType ?: if (title != null) "movie" else "tv"

    val displayTitle: String
        get() = when {
            !title.isNullOrEmpty() -> title
            !name.isNullOrEmpty() -> name
            !originalTitle.isNullOrEmpty() -> originalTitle
            !originalName.isNullOrEmpty() -> originalName
            else -> ""
        }

    val date: String
        get() = releaseDate ?: firstAirDate.orEmpty()

    val year: String
        get() = if (date.length >= 4) date.substring(0, 4) else ""

    val runtimeMinutes: Int
        get() = runtime?.takeIf { it > 0 }
            ?: episodeRunTime.firstOrNull { it > 0 }
            ?: 0
}
