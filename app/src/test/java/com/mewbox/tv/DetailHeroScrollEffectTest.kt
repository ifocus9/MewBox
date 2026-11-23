package com.mewbox.tv

import com.mewbox.tv.ui.detail.HeroFadeDistanceRatio
import com.mewbox.tv.ui.detail.HeroParallaxFactor
import com.mewbox.tv.ui.detail.HeroTopScrimEndRatio
import com.mewbox.tv.ui.detail.HeroTopScrimStartRatio
import com.mewbox.tv.ui.detail.computeHeroPosterAlpha
import com.mewbox.tv.ui.detail.computeHeroPosterScale
import com.mewbox.tv.ui.detail.computeHeroPosterTranslationY
import com.mewbox.tv.ui.detail.computeTopScrollScrimAlpha
import com.mewbox.tv.ui.detail.inverseRubberBandOffset
import com.mewbox.tv.ui.detail.resolveHeroScrollOffsetPx
import com.mewbox.tv.ui.detail.rubberBandOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailHeroScrollEffectTest {

    @Test
    fun `rubberBandOffset is zero for non-positive input, strictly monotonic, and bounded below maxPullPx`() {
        val maxPull = 900f
        assertEquals(0f, rubberBandOffset(0f, maxPull), 1e-6f)
        assertEquals(0f, rubberBandOffset(-120f, maxPull), 1e-6f)
        assertEquals(0f, rubberBandOffset(300f, 0f), 1e-6f)

        val samples = listOf(50f, 200f, 600f, 1500f, 5000f, 20000f)
        var prev = 0f
        for (raw in samples) {
            val mapped = rubberBandOffset(raw, maxPull)
            assertTrue("expected $mapped > $prev for raw=$raw", mapped > prev)
            assertTrue("expected $mapped < $maxPull for raw=$raw", mapped < maxPull)
            prev = mapped
        }
    }

    @Test
    fun `inverseRubberBandOffset accurately inverts rubberBandOffset across pull ranges`() {
        val maxPull = 850f
        assertEquals(0f, inverseRubberBandOffset(0f, maxPull), 1e-6f)
        assertEquals(0f, inverseRubberBandOffset(-10f, maxPull), 1e-6f)
        assertEquals(0f, inverseRubberBandOffset(100f, 0f), 1e-6f)

        val rawSamples = listOf(12f, 80f, 260f, 750f, 1800f, 4500f)
        for (raw in rawSamples) {
            val visual = rubberBandOffset(raw, maxPull)
            val recovered = inverseRubberBandOffset(visual, maxPull)
            assertEquals("raw=$raw visual=$visual", raw, recovered, 1e-2f)
        }
    }

    @Test
    fun `computeHeroPosterScale returns 1 at rest or invalid height and proportional scale on pull`() {
        val height = 1000f
        assertEquals(1f, computeHeroPosterScale(0f, height), 1e-6f)
        assertEquals(1f, computeHeroPosterScale(-50f, height), 1e-6f)
        assertEquals(1f, computeHeroPosterScale(200f, 0f), 1e-6f)
        assertEquals(1f, computeHeroPosterScale(200f, -100f), 1e-6f)

        assertEquals(1.25f, computeHeroPosterScale(250f, height), 1e-6f)
        assertEquals(1.60f, computeHeroPosterScale(600f, height), 1e-6f)
    }

    @Test
    fun `computeHeroPosterTranslationY stays zero when pulling and applies capped parallax on scroll up`() {
        val height = 1000f
        assertEquals(0f, computeHeroPosterTranslationY(0f, 0f, height), 1e-6f)
        assertEquals(0f, computeHeroPosterTranslationY(300f, 40f, height), 1e-6f)
        assertEquals(0f, computeHeroPosterTranslationY(-20f, 0f, height), 1e-6f)

        assertEquals(-400f * HeroParallaxFactor, computeHeroPosterTranslationY(400f, 0f, height), 1e-4f)

        val maxOffset = -(height * HeroParallaxFactor * 1.25f)
        assertEquals(maxOffset, computeHeroPosterTranslationY(5000f, 0f, height), 1e-4f)
    }

    @Test
    fun `computeHeroPosterAlpha follows smoothstep fade on scroll up and resets to 1 when pulling`() {
        val height = 1000f
        assertEquals(1f, computeHeroPosterAlpha(0f, 0f, height), 1e-6f)
        assertEquals(1f, computeHeroPosterAlpha(500f, 10f, height), 1e-6f)

        val fadeDist = height * HeroFadeDistanceRatio
        assertEquals(0.5f, computeHeroPosterAlpha(fadeDist * 0.5f, 0f, height), 1e-5f)

        val alphaQuarter = computeHeroPosterAlpha(fadeDist * 0.25f, 0f, height)
        val alphaHalf = computeHeroPosterAlpha(fadeDist * 0.5f, 0f, height)
        val alphaThreeQuarters = computeHeroPosterAlpha(fadeDist * 0.75f, 0f, height)
        assertTrue(alphaQuarter > alphaHalf && alphaHalf > alphaThreeQuarters)

        assertEquals(0f, computeHeroPosterAlpha(fadeDist, 0f, height), 1e-6f)
        assertEquals(0f, computeHeroPosterAlpha(fadeDist * 1.5f, 0f, height), 1e-6f)
    }

    @Test
    fun `computeTopScrollScrimAlpha stays zero until threshold and smoothsteps to 1`() {
        val height = 1000f
        val start = height * HeroTopScrimStartRatio
        val end = height * HeroTopScrimEndRatio

        assertEquals(0f, computeTopScrollScrimAlpha(0f, 0f, height), 1e-6f)
        assertEquals(0f, computeTopScrollScrimAlpha(start, 0f, height), 1e-6f)
        assertEquals(0f, computeTopScrollScrimAlpha(end, 12f, height), 1e-6f)

        val mid = (start + end) * 0.5f
        assertEquals(0.5f, computeTopScrollScrimAlpha(mid, 0f, height), 1e-5f)

        val q1 = computeTopScrollScrimAlpha(start + (end - start) * 0.25f, 0f, height)
        val q3 = computeTopScrollScrimAlpha(start + (end - start) * 0.75f, 0f, height)
        assertTrue(q1 > 0f && q1 < 0.5f && q3 > 0.5f && q3 < 1f)

        assertEquals(1f, computeTopScrollScrimAlpha(end, 0f, height), 1e-6f)
        assertEquals(1f, computeTopScrollScrimAlpha(end * 2f, 0f, height), 1e-6f)
    }

    @Test
    fun `resolveHeroScrollOffsetPx uses item offset on index 0 and saturates when scrolled past hero`() {
        val height = 960f
        assertEquals(0f, resolveHeroScrollOffsetPx(0, 0, height), 1e-6f)
        assertEquals(0f, resolveHeroScrollOffsetPx(0, -15, height), 1e-6f)
        assertEquals(320f, resolveHeroScrollOffsetPx(0, 320, height), 1e-6f)
        assertEquals(height, resolveHeroScrollOffsetPx(1, 0, height), 1e-6f)
        assertEquals(height, resolveHeroScrollOffsetPx(3, 180, height), 1e-6f)
    }
}
