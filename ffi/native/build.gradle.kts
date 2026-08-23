plugins {
    kotlin("multiplatform")
}

kotlin {
    // Engine C ABI exit targets (ADR 0050 amendment): each produces a static
    // library plus the C header generated from the @CName exports. macosX64
    // stays out per ADR 0045.
    macosArm64 { binaries { staticLib() } }
    linuxX64 { binaries { staticLib() } }
    linuxArm64 { binaries { staticLib() } }
    mingwX64 { binaries { staticLib() } }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":engine"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
