import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ==========================================
// 个人自用极简版本控制
// ==========================================
val appVersionCode = 4
val appVersionName = "1.0.3"

// 构建时间戳（例如 202611041147）
val buildTimestamp = LocalDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))

android {
    namespace = "com.mewbox.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mewbox.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // 仅支持 64 位手机
        ndk { abiFilters += "arm64-v8a" }
    }

    androidResources {
        // 仅打包中文语言资源，剥离第三方库中 80+ 种海外语言字符串表（ARSC 减小 90%）
        localeFilters += listOf("zh", "zh-rCN")
    }

    packaging {
        jniLibs {
            // 启用 so 压缩打包：libnode 等 so 库以 Deflate 压缩存放于 APK 中
            // 可大幅降低 APK 下载与分发体积（由 ~55MB 降至 ~24MB）
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "DebugProbesKt.bin"
            )
        }
    }

    signingConfigs {
        // 密钥材料随仓库提交（.key/ 目录），路径与口令见根目录 gradle.properties
        val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as String? ?: ".key/app-release.jks"
        val storeFileResolved = rootProject.file(storeFilePath)
        if (storeFileResolved.exists()) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
                // minSdk 26（Android 8.0）已远超 v2 签名支持的 Android 7.0 下限，
                // AGP 会自动省略 v1 JAR 签名，无需也无法强制开启
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Debug 包自动追加时间戳后缀（例：1.0.0-debug.202611041146）
            versionNameSuffix = "-debug.$buildTimestamp"
            // Debug 和 Release 统一使用正式签名，实现双向无缝覆盖安装，绝不丢订阅和播放记录
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 密钥缺失时不设签名，产出 unsigned 包，避免静默回退成 debug 签名
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_compiler_config.conf"))
}

androidComponents {
    onVariants { variant ->
        val timeTag = if (variant.buildType == "debug") "-$buildTimestamp" else ""
        variant.outputs.forEach { output ->
            output.outputFileName.set("MewBox-v${appVersionName}-${variant.name}${timeTag}.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion(libs.versions.kotlin.get())
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(project(":libs:backdrop"))
    implementation(libs.capsule)
    implementation(libs.materialkolor)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
