package com.mewbox.tv

import android.app.Application
import com.mewbox.tv.api.config.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MewBoxApp : Application() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // 在首次网络请求前装载本地地址映射（DataStore 读取很快）
        runBlocking { settings.load() }
    }
}
