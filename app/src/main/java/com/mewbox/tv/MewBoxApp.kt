package com.mewbox.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.api.net.LocalMessageBridge
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MewBoxApp : Application() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var messageBridge: LocalMessageBridge

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        if (!isMainProcess()) return
        // 在首次网络请求前装载本地地址映射（DataStore 读取很快）
        runBlocking { settings.load() }
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
