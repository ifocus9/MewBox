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
    val action: String = "",
) {
    /** 纯推荐源条目（如豆瓣）：包含 msearch，详情为空，需直接按片名跨源搜索。 */
    val isMsearch: Boolean get() = vodId.startsWith(MSEARCH_PREFIX) || vodId.contains("msearch", ignoreCase = true)

    /** 严格对齐 FongMi：判断是否属于直接唤起网页/动作的配置项条目（如 baseset 网盘配置、网盘扫码等） */
    val isAction: Boolean get() = action.isNotEmpty() || isActionId(vodId)

    /** 实际执行的 Action 命令（优先 action 字段，其次 vodId） */
    val effectiveAction: String get() = action.ifEmpty { vodId }

    companion object {
        const val MSEARCH_PREFIX = "msearch:"

        fun isActionId(id: String?): Boolean {
            if (id.isNullOrEmpty()) return false
            val trimmed = id.trim()
            val lower = trimmed.lowercase()
            return lower.startsWith("openinternal") ||
                lower.startsWith("openweb") ||
                lower.startsWith("openscan") ||
                lower.startsWith("action:") ||
                (trimmed.startsWith("{") && (trimmed.contains("\"actionId\"") || trimmed.contains("\"action\"") || trimmed.contains("\"mode\":\"browser\"")))
        }
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
    val format: String = "",
    /** 0 = 直链；1 = 需网页嗅探 */
    val parse: Int = 0,
    val jx: Int = 0,
    val flag: String = "",
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
    val msg: String = "",
    val web: WebOptions? = null,
) {
    val needSniff: Boolean get() = parse == 1 || jx == 1
}
