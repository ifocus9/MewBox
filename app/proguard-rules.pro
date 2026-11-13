-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp
-dontwarn okhttp3.**

# Release 构建移除 Timber 调用点（连同日志字符串一并被 R8 裁掉）
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class timber.log.Timber$Forest {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
    public *** wtf(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Domain Beans & Entities
-keep class com.mewbox.tv.bean.** { *; }

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Media3 ExoPlayer（无需全量 -keep，由 AAR 自带的 consumer rules 精确防混淆）
-dontwarn androidx.media3.**
# nextlib 仅用音频软解：native 方法名由下方 keepclasseswithmembernames 保留，
# 这里只额外保留 JNI 通过 FindClass/GetMethodID 反查的回调，其余视频解码类交给 R8 裁剪。
-keep class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# Hilt
-keep class * extends androidx.hilt.lifecycle.ViewModelAssistedFactory
-dontwarn dagger.hilt.**

# Node.js engine & JNI bridge（NodeService 自带 @Keep，JNI 入口由 native 规则保留类名与方法名）
-keepclasseswithmembernames class * {
    native <methods>;
}
