plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.compose.material3"
        compileSdk = 36
        minSdk = 23
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":frontend:compose"))
            implementation(compose.material3)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
        }
    }
}
