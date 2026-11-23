package com.mewbox.tv.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sqrt

internal const val HeroMaxPullHeightRatio = 0.85f
internal const val HeroRubberBandCoefficient = 0.72f
internal const val HeroParallaxFactor = 0.30f
internal const val HeroFadeDistanceRatio = 0.88f
internal const val HeroTopScrimStartRatio = 0.15f
internal const val HeroTopScrimEndRatio = 0.70f

private const val ReboundStiffness = 320f
private const val FlingCushionStiffness = 340f
private const val FlingCushionMinVelocityPx = 500f
private const val FlingCushionVelocityFactor = 0.30f
private const val FlingCushionMaxVelocityPx = 2400f
private const val FlingCushionMaxPullRatio = 0.25f

/**
 * iOS 风格双曲线阻尼映射：随下拉手势距离增大平滑收敛至 [maxPullPx]，杜绝撞墙感
 */
internal fun rubberBandOffset(rawPullPx: Float, maxPullPx: Float): Float {
    if (rawPullPx <= 0f || maxPullPx <= 0f) return 0f
    val scaled = rawPullPx * HeroRubberBandCoefficient
    return (scaled * maxPullPx) / (maxPullPx + scaled)
}

/**
 * [rubberBandOffset] 的精确反函数：用于在回弹动画被手势打断时无跳变还原累计原始下拉量
 */
internal fun inverseRubberBandOffset(visualPullPx: Float, maxPullPx: Float): Float {
    if (visualPullPx <= 0f || maxPullPx <= 0f) return 0f
    val clamped = visualPullPx.coerceIn(0f, maxPullPx * 0.995f)
    return (clamped * maxPullPx) / (HeroRubberBandCoefficient * (maxPullPx - clamped))
}

/**
 * 将 LazyColumn 滚出偏移统一折算为相对于 Hero 海报高度的像素偏移
 */
internal fun resolveHeroScrollOffsetPx(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    imageHeightPx: Float,
): Float {
    val safeHeight = imageHeightPx.coerceAtLeast(0f)
    return if (firstVisibleItemIndex > 0) {
        safeHeight
    } else {
        firstVisibleItemScrollOffset.toFloat().coerceAtLeast(0f)
    }
}

/**
 * 下拉拉伸时背景大海报围绕顶部中心等比放大的倍率
 */
internal fun computeHeroPosterScale(pullDownPx: Float, imageHeightPx: Float): Float {
    if (pullDownPx <= 0f || imageHeightPx <= 0f) return 1f
    return (imageHeightPx + pullDownPx) / imageHeightPx
}

/**
 * 上滑浏览信息区时背景大海报的视差纵向位移（下拉或静止态保持 0）
 */
internal fun computeHeroPosterTranslationY(
    scrollOffsetPx: Float,
    pullDownPx: Float,
    imageHeightPx: Float,
): Float {
    if (pullDownPx > 0f || scrollOffsetPx <= 0f || imageHeightPx <= 0f) return 0f
    return -(scrollOffsetPx * HeroParallaxFactor).coerceAtMost(imageHeightPx * HeroParallaxFactor * 1.25f)
}

/**
 * 上滑浏览信息区时背景大海报的 Smoothstep 平滑淡出透明度
 */
internal fun computeHeroPosterAlpha(
    scrollOffsetPx: Float,
    pullDownPx: Float,
    imageHeightPx: Float,
): Float {
    if (pullDownPx > 0f || scrollOffsetPx <= 0f || imageHeightPx <= 0f) return 1f
    val p = (scrollOffsetPx / (imageHeightPx * HeroFadeDistanceRatio)).coerceIn(0f, 1f)
    return (1f - p * p * (3f - 2f * p)).coerceIn(0f, 1f)
}

/**
 * 上滑后顶部浮现的动态主题色渐变遮罩透明度，静止与下拉态保持完全透明
 */
internal fun computeTopScrollScrimAlpha(
    scrollOffsetPx: Float,
    pullDownPx: Float,
    imageHeightPx: Float,
): Float {
    if (pullDownPx > 0f || scrollOffsetPx <= 0f || imageHeightPx <= 0f) return 0f
    val start = imageHeightPx * HeroTopScrimStartRatio
    val end = imageHeightPx * HeroTopScrimEndRatio
    if (end <= start) return 0f
    val p = ((scrollOffsetPx - start) / (end - start)).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

/**
 * 详情页顶部弹性下拉放大与回弹状态控制器。
 *
 * 采用外层 [NestedScrollConnection] 而非 Compose `OverscrollEffect`：
 * 当详情页内容不足一屏（例如无演职员且仅单集）时，Compose `ScrollingLogic.shouldDispatchOverscroll`
 * 会因不可滚动而跳过 `OverscrollEffect`，而 `NestedScrollConnection` 在任意内容长度下均可稳定接管越界手势。
 */
@Stable
internal class DetailHeroScrollState(
    private val scope: CoroutineScope,
    private val maxPullPxProvider: () -> Float,
) {
    var pullDownPx by mutableFloatStateOf(0f)
        private set

    private var rawPullPx = 0f
    private var reboundJob: Job? = null

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && reboundJob?.isActive == true) {
                reboundJob?.cancel()
                reboundJob = null
                rawPullPx = inverseRubberBandOffset(pullDownPx, maxPullPxProvider())
            }

            if (source == NestedScrollSource.UserInput && rawPullPx > 0f && available.y < 0f) {
                val maxPull = maxPullPxProvider()
                val newRaw = (rawPullPx + available.y).coerceAtLeast(0f)
                val consumedY = newRaw - rawPullPx
                rawPullPx = newRaw
                pullDownPx = rubberBandOffset(newRaw, maxPull)
                return Offset(0f, consumedY)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) {
                val maxPull = maxPullPxProvider()
                if (reboundJob?.isActive == true) {
                    reboundJob?.cancel()
                    reboundJob = null
                    rawPullPx = inverseRubberBandOffset(pullDownPx, maxPull)
                }
                if (maxPull > 0f) {
                    rawPullPx += available.y
                    pullDownPx = rubberBandOffset(rawPullPx, maxPull)
                    return Offset(0f, available.y)
                }
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pullDownPx > 0.5f) {
                startRebound(available.y)
                return Velocity(0f, available.y)
            }
            if (pullDownPx > 0f) {
                pullDownPx = 0f
                rawPullPx = 0f
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (pullDownPx <= 0.5f && available.y > FlingCushionMinVelocityPx) {
                startFlingTopCushion(available.y)
                return Velocity(0f, available.y)
            }
            return Velocity.Zero
        }
    }

    private fun startRebound(initialVelocityY: Float) {
        reboundJob?.cancel()
        val startPull = pullDownPx
        reboundJob = scope.launch {
            val anim = Animatable(startPull)
            // 临界阻尼方程无负向过冲的数学下界：v0 >= -sqrt(k) * x0
            val minVelocity = -sqrt(ReboundStiffness) * startPull * 0.9f
            val initialVelocity = if (initialVelocityY < 0f) {
                initialVelocityY.coerceAtLeast(minVelocity)
            } else {
                (initialVelocityY * 0.12f).coerceAtMost(450f)
            }
            anim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = ReboundStiffness,
                ),
                initialVelocity = initialVelocity,
            ) {
                pullDownPx = value.coerceAtLeast(0f)
            }
            // 仅正常收敛完成时归零，避免被新触摸取消时清掉当前位置
            pullDownPx = 0f
            rawPullPx = 0f
        }
    }

    private fun startFlingTopCushion(remainingVelocityY: Float) {
        reboundJob?.cancel()
        val maxCushion = maxPullPxProvider() * FlingCushionMaxPullRatio
        if (maxCushion <= 0f) return
        reboundJob = scope.launch {
            val anim = Animatable(0f)
            val impulseVelocity = (remainingVelocityY * FlingCushionVelocityFactor)
                .coerceAtMost(FlingCushionMaxVelocityPx)
            anim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = FlingCushionStiffness,
                ),
                initialVelocity = impulseVelocity,
            ) {
                pullDownPx = value.coerceIn(0f, maxCushion)
            }
            pullDownPx = 0f
            rawPullPx = 0f
        }
    }
}

@Composable
internal fun rememberDetailHeroScrollState(
    maxPullPxProvider: () -> Float,
): DetailHeroScrollState {
    val scope = rememberCoroutineScope()
    val latestMaxPullPxProvider by rememberUpdatedState(maxPullPxProvider)
    return remember(scope) {
        DetailHeroScrollState(
            scope = scope,
            maxPullPxProvider = { latestMaxPullPxProvider() },
        )
    }
}
