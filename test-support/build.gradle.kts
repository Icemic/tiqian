plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    js {
        browser()
        useEsModules()
    }
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
    }
}
