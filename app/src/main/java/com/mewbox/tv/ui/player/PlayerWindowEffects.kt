package com.mewbox.tv.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mewbox.tv.player.PlaybackState
import com.mewbox.tv.player.PlayerState

/**
 * 播放窗口全局副作用管理：
 * 1. 智能屏幕旋转（横屏/竖屏自适应）
 * 2. 沉浸式系统栏隐藏/展示与刘海区延伸
 * 3. 屏幕防息屏常亮控制
 * 4. 页面退出/销毁时安全恢复系统状态
 */
@Composable
fun PlayerWindowEffects(
    isFullscreen: Boolean,
    playerState: PlayerState,
    keepScreenOnExtra: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // 智能屏幕旋转策略（感知视频真实宽高比例：进入全屏默认横屏避免慢半拍；若检测为竖屏短剧则自适应保持竖屏）
    LaunchedEffect(isFullscreen, playerState.videoWidth, playerState.videoHeight) {
        val currentActivity = activity ?: return@LaunchedEffect
        if (!isFullscreen) {
            currentActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            return@LaunchedEffect
        }
        val width = playerState.videoWidth
        val height = playerState.videoHeight
        // 明确检测到是竖屏视频（如竖屏短剧）才切竖屏，否则（包括初始加载尚未获取到尺寸时）一律立即横屏
        val isPortraitVideo = width > 0 && height > 0 && height > width
        currentActivity.requestedOrientation = if (isPortraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // 全屏沉浸式系统栏控制
    LaunchedEffect(isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }
            insetsController.isAppearanceLightStatusBars = false
        }
    }

    // 防息屏常亮控制：处于全屏且正在播放、缓冲或额外解析中时保持常亮
    val shouldKeepScreenOn = isFullscreen && (
        playerState.isPlaying ||
        playerState.playbackState == PlaybackState.BUFFERING ||
        keepScreenOnExtra
    )
    DisposableEffect(shouldKeepScreenOn) {
        val window = activity?.window
        if (shouldKeepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (shouldKeepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // 页面进入与销毁时的系统状态维护
    DisposableEffect(Unit) {
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val prevLightStatusBars = insetsController?.isAppearanceLightStatusBars ?: true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.attributes?.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes?.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            insetsController?.isAppearanceLightStatusBars = prevLightStatusBars
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
