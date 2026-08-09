plugins {
    kotlin("multiplatform")
}

// Apple-platform shaping + font metrics via Core Text. Uses Kotlin/Native's built-in
// platform.CoreText / platform.CoreFoundation bindings (no custom cinterop def needed).
kotlin {
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api(project(":core"))
            api(project(":font"))
            api(project(":shaping:api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
