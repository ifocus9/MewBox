package com.mewbox.tv.di

import android.content.Context
import androidx.room.Room
import com.mewbox.tv.api.net.HostInterceptor
import com.mewbox.tv.api.net.HostMapping
import com.mewbox.tv.api.net.SSLCompat
import com.mewbox.tv.api.net.T4Service
import com.mewbox.tv.catvod.SpiderLoader
import com.mewbox.tv.db.AppDatabase
import com.mewbox.tv.db.ConfigDao
import com.mewbox.tv.db.HistoryDao
import com.mewbox.tv.db.SiteDao
import com.mewbox.tv.engine.t4.T4SpiderLoader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** 源 API 专用：宽松超时 + 忽略自签名证书，仅此 Client 使用 SSLCompat。 */
    @Provides @Singleton @Named("api")
    fun apiClient(hostMapping: HostMapping): OkHttpClient =
        SSLCompat.apply(OkHttpClient.Builder())
            .addInterceptor(HostInterceptor(hostMapping))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** 播放器/图片专用：做 127.0.0.1 映射、自动跟随重定向并具备 SSL 容错（兼容网盘多级 302 节点与 CDN）。
     * 配置专属连接池与连接重试，复用与网盘 CDN 节点的 TCP/TLS 链路，大幅减少 Range 串行分片请求的重连开销。
     */
    @Provides @Singleton @Named("player")
    fun playerClient(hostMapping: HostMapping): OkHttpClient =
        SSLCompat.apply(OkHttpClient.Builder())
            .addInterceptor(HostInterceptor(hostMapping))
            .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton
    fun retrofit(@Named("api") client: OkHttpClient): Retrofit =
        Retrofit.Builder().baseUrl("http://localhost/").client(client).build()

    @Provides @Singleton
    fun t4Service(retrofit: Retrofit): T4Service = retrofit.create(T4Service::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mewbox.db").build()

    @Provides fun siteDao(db: AppDatabase): SiteDao = db.siteDao()
    @Provides fun configDao(db: AppDatabase): ConfigDao = db.configDao()
    @Provides fun historyDao(db: AppDatabase): HistoryDao = db.historyDao()
}

/** 引擎注册：新增 Node.js / QuickJS 引擎时只需在此再加一个 @IntoSet 绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds @IntoSet
    abstract fun bindT4(loader: T4SpiderLoader): SpiderLoader
}
