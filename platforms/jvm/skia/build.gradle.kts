plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":engine"))
            api("org.jetbrains.skiko:skiko-awt:0.144.6")
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":platforms:jvm:shaping"))
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.144.6")
        }
    }
}
