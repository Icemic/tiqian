plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        outputModuleName.set("Tiqian-tiqian-web-precompute")
        nodejs()
        useEsModules()
        binaries.executable()
    }

    // Native precompute engine targets: each produces a static library plus the C header
    // generated from the @CName exports (ADR 0050). macosX64 stays out per ADR 0045.
    macosArm64 { binaries { staticLib() } }
    linuxX64 { binaries { staticLib() } }
    linuxArm64 { binaries { staticLib() } }
    mingwX64 { binaries { staticLib() } }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":font"))
            implementation(project(":shaping:api"))
            implementation(project(":linebreak"))
            implementation(project(":clreq"))
            implementation(project(":layout"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
