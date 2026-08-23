plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.tiqian.demo.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.tiqian.demo.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    implementation(project(":demo"))
    "benchmarkImplementation"(project(":platforms:compose:compose"))
    implementation("androidx.activity:activity-compose:1.11.0")
    // FileProvider：诊断报告按文件分享，避免长文本被消息应用截断。
    implementation("androidx.core:core:1.16.0")

    // 字体诊断界面只用平台 API 与 Compose，刻意不依赖提椠引擎：报告观测的是平台行为本身。
    implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    implementation("org.jetbrains.compose.material:material:1.11.1")
}
