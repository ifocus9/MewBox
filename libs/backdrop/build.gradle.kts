import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 本地 fork 自 io.github.kyant0:backdrop 2.0.1(Apache-2.0,见 NOTICE),
// 目的:高光/阴影/内阴影层"参数未变跳过 record"等内部性能优化 + HighlightStyle.Ambient 加 angle 参数
// + AmbientHighlightShaderString 背光侧改全透明;上游升级需手工合并。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kyant.backdrop"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.annotation)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.kyant.shapes)
}

