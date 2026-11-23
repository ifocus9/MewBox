package com.mewbox.tv.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/** 距底部的偏移，对齐系统 Toast.makeText 的默认高度；不随 app 的 windowInsets 变化 */
private const val TOAST_BOTTOM_OFFSET_DP = 64f

/**
 * 软件全局 Toast 提示分发器
 *
 * 用系统 Toast 窗口承载自绘圆角胶囊 View，跟随应用当前深浅主题配色，
 * 显隐动画、位置、时长、排队与窗口层级全部交回系统托管。
 */
object AppToast {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    @Volatile
    private var darkTheme: Boolean = false

    /** 由 MewBoxApp.onCreate() 注入，必须早于任何 show() 调用 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setDarkTheme(isDark: Boolean) {
        darkTheme = isDark
    }

    fun show(message: String?, isLong: Boolean = false) {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!::appContext.isInitialized) return
        // Toast 需要主线程 Looper：本地服务回调、ViewModel 协程等可能不在主线程
        mainHandler.post { showSystemToast(text, isLong) }
    }

    @Suppress("DEPRECATION")
    private fun showSystemToast(text: String, isLong: Boolean) {
        val density = appContext.resources.displayMetrics.density
        val isDark = darkTheme
        val textColor = if (isDark) Color.rgb(0xF2, 0xF4, 0xF8) else Color.rgb(0x16, 0x18, 0x1D)
        val bgColor = if (isDark) Color.rgb(0x25, 0x28, 0x31) else Color.WHITE
        val borderColor = if (isDark) Color.argb(0x1F, 0xFF, 0xFF, 0xFF) else Color.argb(0x16, 0x0F, 0x17, 0x2A)

        val view = TextView(appContext).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minWidth = (96 * density).toInt()
            maxWidth = (380 * density).toInt()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            val horizontal = (28 * density).toInt()
            val vertical = (14 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 16f * density
                setStroke(
                    (0.5f * density).toInt().coerceAtLeast(1),
                    borderColor,
                )
            }
        }
        Toast(appContext).apply {
            this.view = view
            duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            // 自定义 view toast 的 setGravity 有效（系统只对 text toast 置为 no-op）
            setGravity(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                (TOAST_BOTTOM_OFFSET_DP * density).toInt(),
            )
            show()
        }
    }
}
