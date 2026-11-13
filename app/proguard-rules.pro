-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Retrofit & OkHttp
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.mewbox.tv.api.net.T4Service { *; }
-dontwarn okhttp3.**

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
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }

# Hilt
-keep class * extends androidx.hilt.lifecycle.ViewModelAssistedFactory
-dontwarn dagger.hilt.**

# Node.js engine & JNI bridge
-keep class com.fongmi.nodejs.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
