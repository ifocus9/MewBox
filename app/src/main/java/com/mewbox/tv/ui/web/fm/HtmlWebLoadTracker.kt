package com.mewbox.tv.ui.web.fm

/**
 * HTML 网页源 WebView 加载状态阶段：
 * 显式隔离「旧网页清场 (`about:blank`) → 等待新文档首包 → 新文档已开始未提交渲染 → 新文档已可见」状态流转，
 * 避免切换慢速在线源时被旧页中止回调或 `about:blank` 进度 `100%` 误关 Loading 遮罩。
 */
internal enum class HtmlLoadPhase {
    IDLE,
    RESETTING_TO_BLANK,
    WAITING_START,
    LOADING_BEFORE_COMMIT,
    COMMITTED_LOADING,
}

internal class HtmlWebLoadTracker {

    sealed interface BeginDecision {
        data object AlreadyLoaded : BeginDecision
        data object AlreadyLoading : BeginDecision
        data class ResetToBlankThenLoad(val generation: Long, val switched: Boolean) : BeginDecision
        data class LoadDirectly(val generation: Long, val switched: Boolean) : BeginDecision
    }

    enum class PageStartAction {
        IGNORE,
        PROCESS,
    }

    sealed interface CommitAction {
        data object IGNORE : CommitAction
        data class BLANK_READY(val generation: Long) : CommitAction
        data class COMMITTED(val shouldClearHistory: Boolean) : CommitAction
    }

    sealed interface ProgressAction {
        data object IGNORE : ProgressAction
        data class UPDATE(
            val progress: Int,
            val isLoading: Boolean,
            val clearInitialLoading: Boolean,
            val injectSdk: Boolean,
        ) : ProgressAction
    }

    sealed interface FinishAction {
        data object IGNORE : FinishAction
        data class BLANK_READY(val generation: Long) : FinishAction
        data class FINISHED(val shouldClearHistory: Boolean) : FinishAction
    }

    sealed interface HistoryAction {
        data object IGNORE : HistoryAction
        data class UPDATE(val shouldClearHistory: Boolean) : HistoryAction
    }

    var loadGeneration: Long = 0L
        private set

    var targetRootUrl: String = ""
        private set

    var previousRootUrl: String = ""
        private set

    private var previousPageUrl: String = ""

    var committedRootUrl: String = ""
        private set

    var clearHistoryPending: Boolean = false
        private set

    var clearHistoryArmed: Boolean = false
        private set

    var phase: HtmlLoadPhase = HtmlLoadPhase.IDLE
        private set

    fun beginSubscriptionLoad(
        targetUrl: String,
        currentWebViewUrl: String?,
        forceReload: Boolean,
    ): BeginDecision {
        if (!forceReload && targetRootUrl == targetUrl && phase != HtmlLoadPhase.IDLE) {
            return BeginDecision.AlreadyLoading
        }
        if (
            !forceReload &&
            targetRootUrl == targetUrl &&
            committedRootUrl == targetUrl &&
            !currentWebViewUrl.isNullOrBlank() &&
            !isBlankUrl(currentWebViewUrl)
        ) {
            return BeginDecision.AlreadyLoaded
        }

        val switched = targetRootUrl != targetUrl
        val hasActiveDocument = !currentWebViewUrl.isNullOrBlank() && !isBlankUrl(currentWebViewUrl)
        val oldTarget = targetRootUrl
        loadGeneration++

        if (switched && hasActiveDocument) {
            previousRootUrl = oldTarget
            previousPageUrl = currentWebViewUrl.orEmpty()
            targetRootUrl = targetUrl
            committedRootUrl = ""
            clearHistoryPending = true
            clearHistoryArmed = false
            phase = HtmlLoadPhase.RESETTING_TO_BLANK
            return BeginDecision.ResetToBlankThenLoad(generation = loadGeneration, switched = true)
        }

        if (switched || forceReload || committedRootUrl != targetUrl) {
            clearHistoryPending = true
            clearHistoryArmed = false
            committedRootUrl = ""
        }
        previousRootUrl = if (switched) oldTarget else ""
        previousPageUrl = if (switched) currentWebViewUrl.orEmpty() else ""
        targetRootUrl = targetUrl
        phase = HtmlLoadPhase.WAITING_START
        return BeginDecision.LoadDirectly(generation = loadGeneration, switched = switched)
    }

    fun onBlankResetComplete(generation: Long): String? {
        if (generation != loadGeneration || phase != HtmlLoadPhase.RESETTING_TO_BLANK) {
            return null
        }
        phase = HtmlLoadPhase.WAITING_START
        return targetRootUrl.takeIf { it.isNotBlank() }
    }

    fun beginReload() {
        loadGeneration++
        previousRootUrl = ""
        previousPageUrl = ""
        phase = HtmlLoadPhase.WAITING_START
    }

    fun shouldReloadRoot(currentWebViewUrl: String?): Boolean {
        return currentWebViewUrl.isNullOrBlank() ||
            isBlankUrl(currentWebViewUrl) ||
            phase == HtmlLoadPhase.RESETTING_TO_BLANK ||
            clearHistoryPending ||
            clearHistoryArmed ||
            (targetRootUrl.isNotBlank() && committedRootUrl != targetRootUrl)
    }

    fun onPageStarted(url: String?): PageStartAction {
        if (phase == HtmlLoadPhase.RESETTING_TO_BLANK || isBlankUrl(url) || isOldPageInWaitingStart(url)) {
            return PageStartAction.IGNORE
        }
        if (clearHistoryPending) {
            clearHistoryPending = false
            clearHistoryArmed = true
        }
        if (phase == HtmlLoadPhase.WAITING_START) {
            phase = HtmlLoadPhase.LOADING_BEFORE_COMMIT
        }
        return PageStartAction.PROCESS
    }

    fun onPageCommitVisible(url: String?): CommitAction {
        if (phase == HtmlLoadPhase.RESETTING_TO_BLANK) {
            return if (isBlankUrl(url)) CommitAction.BLANK_READY(loadGeneration) else CommitAction.IGNORE
        }
        if (isBlankUrl(url) || isOldPageInWaitingStart(url)) {
            return CommitAction.IGNORE
        }
        val shouldClearHistory = clearHistoryPending || clearHistoryArmed
        clearHistoryPending = false
        clearHistoryArmed = false
        if (targetRootUrl.isNotBlank()) {
            committedRootUrl = targetRootUrl
        }
        if (phase == HtmlLoadPhase.WAITING_START || phase == HtmlLoadPhase.LOADING_BEFORE_COMMIT) {
            phase = HtmlLoadPhase.COMMITTED_LOADING
        }
        return CommitAction.COMMITTED(shouldClearHistory)
    }

    fun onProgressChanged(currentWebViewUrl: String?, newProgress: Int): ProgressAction {
        if (phase == HtmlLoadPhase.RESETTING_TO_BLANK || phase == HtmlLoadPhase.WAITING_START || isBlankUrl(currentWebViewUrl)) {
            return ProgressAction.IGNORE
        }
        val injectSdk = newProgress >= 20
        return when (phase) {
            HtmlLoadPhase.LOADING_BEFORE_COMMIT -> ProgressAction.UPDATE(
                progress = newProgress.coerceAtMost(84),
                isLoading = true,
                clearInitialLoading = false,
                injectSdk = injectSdk,
            )
            HtmlLoadPhase.COMMITTED_LOADING -> {
                val clearInitialLoading = newProgress >= 85
                if (clearInitialLoading) {
                    phase = HtmlLoadPhase.IDLE
                }
                ProgressAction.UPDATE(
                    progress = newProgress,
                    isLoading = newProgress < 100,
                    clearInitialLoading = clearInitialLoading,
                    injectSdk = injectSdk,
                )
            }
            HtmlLoadPhase.IDLE -> ProgressAction.UPDATE(
                progress = newProgress,
                isLoading = newProgress < 100,
                clearInitialLoading = true,
                injectSdk = injectSdk,
            )
            HtmlLoadPhase.RESETTING_TO_BLANK, HtmlLoadPhase.WAITING_START -> ProgressAction.IGNORE
        }
    }

    fun onPageFinished(url: String?): FinishAction {
        if (phase == HtmlLoadPhase.RESETTING_TO_BLANK) {
            return if (isBlankUrl(url)) FinishAction.BLANK_READY(loadGeneration) else FinishAction.IGNORE
        }
        if (isBlankUrl(url) || isOldPageInWaitingStart(url)) {
            return FinishAction.IGNORE
        }
        val shouldClearHistory = clearHistoryPending || clearHistoryArmed
        clearHistoryPending = false
        clearHistoryArmed = false
        if (targetRootUrl.isNotBlank()) {
            committedRootUrl = targetRootUrl
        }
        phase = HtmlLoadPhase.IDLE
        return FinishAction.FINISHED(shouldClearHistory)
    }

    fun doUpdateVisitedHistory(url: String?): HistoryAction {
        if (phase == HtmlLoadPhase.RESETTING_TO_BLANK || isBlankUrl(url) || isOldPageInWaitingStart(url)) {
            return HistoryAction.IGNORE
        }
        val shouldClearHistory = clearHistoryArmed
        if (shouldClearHistory && targetRootUrl.isNotBlank()) {
            committedRootUrl = targetRootUrl
        }
        return HistoryAction.UPDATE(shouldClearHistory)
    }

    fun onMainFrameError(failingUrl: String?): Boolean {
        if (phase == HtmlLoadPhase.RESETTING_TO_BLANK || isBlankUrl(failingUrl) || isOldPageInWaitingStart(failingUrl)) {
            return false
        }
        phase = HtmlLoadPhase.IDLE
        return true
    }

    fun onReceivedTitle(title: String?): Boolean {
        if (phase == HtmlLoadPhase.RESETTING_TO_BLANK || phase == HtmlLoadPhase.WAITING_START || clearHistoryPending) {
            return false
        }
        val clean = title?.trim().orEmpty()
        if (
            clean.isBlank() ||
            clean.equals(BLANK_URL, ignoreCase = true) ||
            clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true) ||
            clean.startsWith("file://", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    fun canNavigateBack(webViewCanGoBack: Boolean): Boolean {
        if (
            phase == HtmlLoadPhase.RESETTING_TO_BLANK ||
            clearHistoryPending ||
            clearHistoryArmed ||
            committedRootUrl != targetRootUrl
        ) {
            return false
        }
        return webViewCanGoBack
    }

    fun reset() {
        loadGeneration++
        targetRootUrl = ""
        previousRootUrl = ""
        previousPageUrl = ""
        committedRootUrl = ""
        clearHistoryPending = false
        clearHistoryArmed = false
        phase = HtmlLoadPhase.IDLE
    }

    private fun isOldPageInWaitingStart(url: String?): Boolean {
        if (phase != HtmlLoadPhase.WAITING_START || url.isNullOrBlank()) return false
        val matchesPrevRoot = previousRootUrl.isNotBlank() && previousRootUrl != targetRootUrl && url == previousRootUrl
        val matchesPrevPage = previousPageUrl.isNotBlank() && previousPageUrl != targetRootUrl && url == previousPageUrl
        return matchesPrevRoot || matchesPrevPage
    }

    companion object {
        const val BLANK_URL = "about:blank"

        fun isBlankUrl(url: String?): Boolean = url?.trim()?.equals(BLANK_URL, ignoreCase = true) == true
    }
}
