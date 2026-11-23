package com.mewbox.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.LocalMessageBridge
import com.mewbox.tv.ui.theme.applyApplicationNightMode
import com.mewbox.tv.util.AppToast
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class MewBoxApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var messageBridge: LocalMessageBridge
    @Inject @Named("player") lateinit var playerClient: OkHttpClient

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val clientFactory = {
            if (::playerClient.isInitialized) playerClient else OkHttpClient()
        }
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(clientFactory))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        AppToast.init(this)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        if (!isMainProcess()) return
        // 在首次网络请求与首帧渲染前装载本地地址映射与主题偏好（DataStore 读取很快）
        runBlocking { settings.load() }
        applyApplicationNightMode(this, settings.currentThemeMode)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        AppToast.setDarkTheme(settings.currentThemeMode.isDark(systemDark))
        // 启动对齐 FongMi 规范的爬虫宿主通信桥接服务
        messageBridge.start()
    }

    private fun isMainProcess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageName == Application.getProcessName()
        } else {
            val pid = Process.myPid()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName == packageName
        }
    }
}
