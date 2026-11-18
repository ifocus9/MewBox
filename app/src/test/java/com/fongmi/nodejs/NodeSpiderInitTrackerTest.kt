package com.fongmi.nodejs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSpiderInitTrackerTest {

    @Test
    fun sameGeneration_tracksPerSiteIndependently() {
        val tracker = NodeSpiderInitTracker()
        val guazi = "node:/spider/guazi/3"
        val gulu = "node:/spider/gulu/3"

        assertFalse(tracker.isInitialized(1L, guazi))
        assertFalse(tracker.isInitialized(1L, gulu))

        tracker.markInitialized(1L, guazi)
        assertTrue(tracker.isInitialized(1L, guazi))
        assertFalse(tracker.isInitialized(1L, gulu))

        tracker.markInitialized(1L, gulu)
        assertTrue(tracker.isInitialized(1L, guazi))
        assertTrue(tracker.isInitialized(1L, gulu))
    }

    @Test
    fun generationAdvance_invalidatesPreviousProcessInitState() {
        val tracker = NodeSpiderInitTracker()
        val guazi = "node:/spider/guazi/3"
        val gulu = "node:/spider/gulu/3"

        tracker.markInitialized(1L, guazi)
        tracker.markInitialized(1L, gulu)
        assertTrue(tracker.isInitialized(1L, guazi))

        // 模拟 :nodejs 后台被杀或重连后进入新代际 2
        assertFalse(tracker.isInitialized(2L, guazi))
        assertFalse(tracker.isInitialized(2L, gulu))

        tracker.markInitialized(2L, guazi)
        assertTrue(tracker.isInitialized(2L, guazi))
        assertFalse(tracker.isInitialized(2L, gulu))
        assertFalse(tracker.isInitialized(1L, guazi))
    }

    @Test
    fun clear_resetsTrackedGenerationAndSiteLocks() {
        val tracker = NodeSpiderInitTracker()
        val guazi = "node:/spider/guazi/3"
        val gulu = "node:/spider/gulu/3"

        val guaziLock1 = tracker.lockFor(guazi)
        val guaziLock2 = tracker.lockFor(guazi)
        val guluLock = tracker.lockFor(gulu)
        assertSame(guaziLock1, guaziLock2)
        assertNotSame(guaziLock1, guluLock)

        tracker.markInitialized(3L, guazi)
        tracker.clear()

        assertFalse(tracker.isInitialized(3L, guazi))
    }
}
