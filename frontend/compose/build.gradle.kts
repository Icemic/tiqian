plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.compose"
        compileSdk = 36
        minSdk = 23
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":layout"))
            // runtime + ui carry public-signature types (@Composable, Modifier,
            // AnnotatedString, TextUnit/Color/FontFamily via CjkTextStyle) → api
            // so consumers resolve the Tiqian API without re-declaring them.
            api("org.jetbrains.compose.runtime:runtime:1.11.1")
            api("org.jetbrains.compose.ui:ui:1.11.1")
            // ScrollState is part of CjkSelectionContainer's public auto-scroll contract.
            api("org.jetbrains.compose.foundation:foundation:1.11.1")
        }

        jvmMain.dependencies {
            implementation(project(":shaping:skia"))
        }

        androidMain.dependencies {
            implementation(project(":shaping:android-adapter"))
            // Host font catalogs are part of the Android artifact contract.
            api(project(":shaping:native-font"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Native Skiko runtime belongs to the test/application runtime, not the library POM.
            implementation(compose.desktop.currentOs)
        }
    }
}
