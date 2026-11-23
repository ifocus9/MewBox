package com.mewbox.tv

import com.mewbox.tv.ui.web.fm.HtmlLoadPhase
import com.mewbox.tv.ui.web.fm.HtmlWebLoadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlWebLoadTrackerTest {

    @Test
    fun switchFromLocalToSlowOnlineHtml_resetsToBlankAndKeepsLoadingUntilCommitVisible() {
        val tracker = HtmlWebLoadTracker()
        val localUrl = "file:///data/user/0/com.mewbox.tv/files/local_html/music.html"
        val onlineUrl = "https://slow.example.com/"

        // 1. 首次加载本地 HTML 并完成提交
        val initialDecision = tracker.beginSubscriptionLoad(localUrl, currentWebViewUrl = null, forceReload = false)
        assertTrue(initialDecision is HtmlWebLoadTracker.BeginDecision.LoadDirectly)
        assertEquals(HtmlWebLoadTracker.PageStartAction.PROCESS, tracker.onPageStarted(localUrl))
        assertEquals(HtmlWebLoadTracker.CommitAction.COMMITTED(shouldClearHistory = true), tracker.onPageCommitVisible(localUrl))
        val finishLocal = tracker.onPageFinished(localUrl)
        assertEquals(HtmlWebLoadTracker.FinishAction.FINISHED(shouldClearHistory = false), finishLocal)
        assertEquals(localUrl, tracker.committedRootUrl)
        assertEquals(HtmlLoadPhase.IDLE, tracker.phase)
        assertTrue(tracker.canNavigateBack(true))

        // 2. 切往慢速在线 HTML：必须先切到 about:blank 清场
        val switchDecision = tracker.beginSubscriptionLoad(onlineUrl, currentWebViewUrl = localUrl, forceReload = false)
        assertTrue(switchDecision is HtmlWebLoadTracker.BeginDecision.ResetToBlankThenLoad)
        val gen = (switchDecision as HtmlWebLoadTracker.BeginDecision.ResetToBlankThenLoad).generation
        assertEquals(HtmlLoadPhase.RESETTING_TO_BLANK, tracker.phase)
        assertFalse(tracker.canNavigateBack(true))

        // 清场期间旧页停止加载触发的任何进度/完成/错误/标题回调都必须忽略
        assertEquals(HtmlWebLoadTracker.ProgressAction.IGNORE, tracker.onProgressChanged(localUrl, 100))
        assertEquals(HtmlWebLoadTracker.FinishAction.IGNORE, tracker.onPageFinished(localUrl))
        assertEquals(HtmlWebLoadTracker.PageStartAction.IGNORE, tracker.onPageStarted(localUrl))
        assertFalse(tracker.onReceivedTitle("本地旧页面"))
        assertFalse(tracker.onMainFrameError(localUrl))

        // 3. about:blank 提交可见：推进至 WAITING_START，开始加载在线 URL
        val blankCommit = tracker.onPageCommitVisible("about:blank")
        assertEquals(HtmlWebLoadTracker.CommitAction.BLANK_READY(gen), blankCommit)
        assertEquals(onlineUrl, tracker.onBlankResetComplete(gen))
        assertEquals(HtmlLoadPhase.WAITING_START, tracker.phase)

        // about:blank 随后的 onPageFinished / 100% 进度回调必须全部忽略
        assertEquals(HtmlWebLoadTracker.FinishAction.IGNORE, tracker.onPageFinished("about:blank"))
        assertEquals(HtmlWebLoadTracker.ProgressAction.IGNORE, tracker.onProgressChanged("about:blank", 100))
        // 延迟到达的旧页结束回调也必须忽略
        assertEquals(HtmlWebLoadTracker.FinishAction.IGNORE, tracker.onPageFinished(localUrl))

        // 等待首包期间重复触发同目标 URL 时不得打断在途加载
        assertEquals(
            HtmlWebLoadTracker.BeginDecision.AlreadyLoading,
            tracker.beginSubscriptionLoad(onlineUrl, currentWebViewUrl = "about:blank", forceReload = false),
        )

        // 4. 慢速在线页终于返回首包：onPageStarted
        assertEquals(HtmlWebLoadTracker.PageStartAction.PROCESS, tracker.onPageStarted(onlineUrl))
        assertEquals(HtmlLoadPhase.LOADING_BEFORE_COMMIT, tracker.phase)

        // 新文档未提交渲染前，即便进度达到 90% 也不得隐藏 loading 遮罩
        val preCommitProgress = tracker.onProgressChanged(onlineUrl, 90)
        assertEquals(
            HtmlWebLoadTracker.ProgressAction.UPDATE(
                progress = 84,
                isLoading = true,
                clearInitialLoading = false,
                injectSdk = true,
            ),
            preCommitProgress,
        )
        assertEquals(
            HtmlWebLoadTracker.HistoryAction.UPDATE(shouldClearHistory = true),
            tracker.doUpdateVisitedHistory(onlineUrl),
        )

        // 5. 新文档视觉提交并完成加载
        assertEquals(
            HtmlWebLoadTracker.CommitAction.COMMITTED(shouldClearHistory = true),
            tracker.onPageCommitVisible(onlineUrl),
        )
        assertEquals(HtmlLoadPhase.COMMITTED_LOADING, tracker.phase)
        val postCommitProgress = tracker.onProgressChanged(onlineUrl, 90)
        assertEquals(
            HtmlWebLoadTracker.ProgressAction.UPDATE(
                progress = 90,
                isLoading = true,
                clearInitialLoading = true,
                injectSdk = true,
            ),
            postCommitProgress,
        )
        assertEquals(
            HtmlWebLoadTracker.FinishAction.FINISHED(shouldClearHistory = false),
            tracker.onPageFinished(onlineUrl),
        )
        assertEquals(onlineUrl, tracker.committedRootUrl)
        assertEquals(HtmlLoadPhase.IDLE, tracker.phase)
        assertTrue(tracker.canNavigateBack(true))
    }

    @Test
    fun rapidConsecutiveSwitches_invalidateStaleBlankGeneration() {
        val tracker = HtmlWebLoadTracker()
        val urlA = "https://a.example.com/"
        val urlB = "https://b.example.com/"
        val urlC = "https://c.example.com/"

        tracker.beginSubscriptionLoad(urlA, currentWebViewUrl = null, forceReload = false)
        tracker.onPageStarted(urlA)
        tracker.onPageCommitVisible(urlA)
        tracker.onPageFinished(urlA)

        val switchB = tracker.beginSubscriptionLoad(urlB, currentWebViewUrl = urlA, forceReload = false)
            as HtmlWebLoadTracker.BeginDecision.ResetToBlankThenLoad
        val switchC = tracker.beginSubscriptionLoad(urlC, currentWebViewUrl = urlA, forceReload = false)
            as HtmlWebLoadTracker.BeginDecision.ResetToBlankThenLoad

        // 旧 generation（B）的兜底或空白回调必须被丢弃
        assertNull(tracker.onBlankResetComplete(switchB.generation))
        assertEquals(urlC, tracker.onBlankResetComplete(switchC.generation))
        assertEquals(urlC, tracker.targetRootUrl)
    }

    @Test
    fun mainFrameError_ignoresOldPageAndBlankErrors_andEndsLoadingOnTargetError() {
        val tracker = HtmlWebLoadTracker()
        val localUrl = "file:///data/local.html"
        val onlineUrl = "https://offline.example.com/"

        tracker.beginSubscriptionLoad(localUrl, currentWebViewUrl = null, forceReload = false)
        tracker.onPageStarted(localUrl)
        tracker.onPageFinished(localUrl)

        val decision = tracker.beginSubscriptionLoad(onlineUrl, currentWebViewUrl = localUrl, forceReload = false)
            as HtmlWebLoadTracker.BeginDecision.ResetToBlankThenLoad
        assertFalse(tracker.onMainFrameError(localUrl))

        assertEquals(onlineUrl, tracker.onBlankResetComplete(decision.generation))
        assertFalse(tracker.onMainFrameError(localUrl))
        assertFalse(tracker.onMainFrameError("about:blank"))

        assertTrue(tracker.onMainFrameError(onlineUrl))
        assertEquals(HtmlLoadPhase.IDLE, tracker.phase)
    }

    @Test
    fun reloadOnCommittedSubPage_preservesCommittedRootAndBlocksStaleProgressBeforeStart() {
        val tracker = HtmlWebLoadTracker()
        val rootUrl = "https://web.example.com/"
        val subUrl = "https://web.example.com/detail/42"

        tracker.beginSubscriptionLoad(rootUrl, currentWebViewUrl = null, forceReload = false)
        tracker.onPageStarted(rootUrl)
        tracker.onPageCommitVisible(rootUrl)
        tracker.onPageFinished(rootUrl)

        assertFalse(tracker.shouldReloadRoot(subUrl))
        tracker.beginReload()
        assertEquals(rootUrl, tracker.committedRootUrl)
        assertTrue(tracker.canNavigateBack(true))
        assertEquals(HtmlWebLoadTracker.ProgressAction.IGNORE, tracker.onProgressChanged(subUrl, 100))

        assertEquals(HtmlWebLoadTracker.PageStartAction.PROCESS, tracker.onPageStarted(subUrl))
        assertEquals(HtmlWebLoadTracker.CommitAction.COMMITTED(shouldClearHistory = false), tracker.onPageCommitVisible(subUrl))
        assertEquals(HtmlWebLoadTracker.FinishAction.FINISHED(shouldClearHistory = false), tracker.onPageFinished(subUrl))
    }

    @Test
    fun switchingBackToSameCommittedHtmlSource_returnsAlreadyLoaded() {
        val tracker = HtmlWebLoadTracker()
        val htmlUrl = "https://music.example.com/"

        tracker.beginSubscriptionLoad(htmlUrl, currentWebViewUrl = null, forceReload = false)
        tracker.onPageStarted(htmlUrl)
        tracker.onPageCommitVisible(htmlUrl)
        tracker.onPageFinished(htmlUrl)

        assertEquals(
            HtmlWebLoadTracker.BeginDecision.AlreadyLoaded,
            tracker.beginSubscriptionLoad(htmlUrl, currentWebViewUrl = htmlUrl, forceReload = false),
        )
    }
}
