plugins {
    id("com.android.application")
}

/**
 * 独立的一次性字体诊断 app。
 *
 * 刻意不依赖 `:demo`、`:frontend:compose` 或任何引擎模块：报告观测的是 Android 平台自身的
 * 字体行为，引擎一行代码都用不上。带上引擎会把四套 ABI 的 native 库打进来（实测 154MB），
 * 而这个 APK 是要发给外部设备的人装的，体积必须小到能随手传。UI 用纯 View 而不是 Compose，
 * 同样是为了体积，也避免在老 OEM 设备上引入 Compose 自身的兼容变量。
 */
android {
    namespace = "org.tiqian.diagnostics"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.tiqian.diagnostics"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // FileProvider：报告按文件分享，避免长文本被消息应用截断。
    implementation("androidx.core:core:1.16.0")
}
