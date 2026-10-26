package com.mewbox.tv.bean

/** 分类（plan 中的 Class，为避开 java.lang.Class 重命名）。 */
data class VodClass(
    val typeId: String,
    val typeName: String,
)

data class Episode(
    val name: String,
    /** 传给 playerContent 的播放 ID，视为不透明字符串，禁止二次解析 */
    val url: String,
    val index: Int,
)

data class Flag(
    val flag: String,
    val episodes: List<Episode> = emptyList(),
)

data class Vod(
    val vodId: String = "",
    val vodName: String = "",
    val vodPic: String = "",
    val vodRemarks: String = "",
    val vodYear: String = "",
    val vodArea: String = "",
    val vodActor: String = "",
    val vodDirector: String = "",
    val vodContent: String = "",
    val typeName: String = "",
    val vodPlayFrom: String = "",
    val vodPlayUrl: String = "",
    val siteKey: String = "",
    val siteName: String = "",
    val playFlags: List<Flag> = emptyList(),
) {
    /** 纯推荐源条目（如豆瓣）：详情为空，需按片名跨源搜索。 */
    val isMsearch: Boolean get() = vodId.startsWith(MSEARCH_PREFIX)

    companion object {
        const val MSEARCH_PREFIX = "msearch:"
    }
}

data class PlayUrl(
    val name: String,
    val url: String,
)

/** 引擎统一返回包装。 */
data class Result(
    val classes: List<VodClass> = emptyList(),
    val list: List<Vod> = emptyList(),
    val url: String = "",
    val playUrls: List<PlayUrl> = emptyList(),
    val header: Map<String, String> = emptyMap(),
    /** 0 = 直链；1 = 需网页嗅探 */
    val parse: Int = 0,
    val jx: Int = 0,
    val flag: String = "",
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
) {
    val needSniff: Boolean get() = parse == 1 || jx == 1
}
