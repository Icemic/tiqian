plugins {
    id("com.android.application")
}

fun gitOutput(vararg arguments: String): String = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", *arguments)
    }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

val collectorGitRevision = gitOutput("rev-parse", "--short=12", "HEAD")
val collectorGitDirty = gitOutput(
    "status",
    "--porcelain",
    "--",
    "demo/font-diagnostics",
).let { status -> status != "unknown" && status.isNotEmpty() }

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
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "COLLECTOR_GIT_REVISION", "\"$collectorGitRevision\"")
        buildConfigField("boolean", "COLLECTOR_GIT_DIRTY", collectorGitDirty.toString())
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // FileProvider：完整 ZIP 证据包通过 content URI 分享。
    implementation("androidx.core:core:1.16.0")
    testImplementation(kotlin("test-junit"))
}
